package com.cue.amlogicburn;

import java.io.IOException;
import java.io.InputStream;

/** Thrown when the user cancels a burn mid-operation. */
class BurnCancelledException extends IOException {
    BurnCancelledException() { super("Burn cancelled by user."); }
}

/** Thrown when a phase-2 command is answered with anything but OKAY. */
class UsbCommandException extends IOException {
    UsbCommandException(String message) { super(message); }
}

/**
 * Amlogic protocol operations, ported from aml-burn.py and
 * cross-checked against the real tool binary. All functions are
 * BLOCKING and must run on a background thread (the burn service uses
 * a worker thread). Cancellation is cooperative: {@code isCancelled}
 * is checked between chunks and between steps.
 *
 * <p>ROM phase (device in maskrom/burn mode, VID 1B8E PID C003):
 * <pre>
 *   chipId   = control read  (0xC0, req 0x40, 64 bytes)
 *   writeMem = control write (0x40, req 1, 64-byte chunks,
 *              wValue = addr&gt;&gt;16, wIndex = addr&amp;0xFFFF)
 *   readMem  = control read  (0xC0, req 2, same chunking/address split)
 *   bootInfo = control write (0x40, req 0x50, wValue 0x200) then a
 *              512-byte bulk read from EP_IN; stage bytes @0x10/@0x11
 *              map to 0xABCD..0xABD0
 * </pre>
 * Bulk phase (device-side u-boot alive): every command is ASCII text
 * + '\n' over bulk EP_OUT, answered by a 16-byte status block from
 * EP_IN that must start with "OKAY".</p>
 */
final class AmlTransfer {
    private AmlTransfer() { }

    /** Supplier checked between chunks/steps; true means abort. */
    interface CancelCheck {
        boolean isCancelled();
    }

    /** Per-chunk progress: (bytesDone, total). */
    interface ChunkProgress {
        void onChunk(long done, long total);
    }

    // ---------------------------------------------------------- ROM phase

    static byte[] chipId(UsbSession session) throws UsbError {
        return session.controlReadBytes(0xC0, AmlProtocol.REQ_CHIP_ID, 0, 0, 64, 5000);
    }

    static void writeMem(UsbSession session, long addr, byte[] data) throws UsbError {
        int done = 0;
        while (done < data.length) {
            int chunkLen = Math.min(AmlProtocol.MEM_CHUNK, data.length - done);
            byte[] chunk = new byte[chunkLen];
            System.arraycopy(data, done, chunk, 0, chunkLen);
            long a = addr + done;
            session.controlWrite(0x40, AmlProtocol.REQ_WRITE_MEM,
                    (int) ((a >> 16) & 0xFFFF), (int) (a & 0xFFFF), chunk, 5000);
            done += chunkLen;
        }
    }

    static byte[] readMem(UsbSession session, long addr, int length) throws UsbError {
        byte[] out = new byte[length];
        int done = 0;
        while (done < length) {
            int want = Math.min(AmlProtocol.MEM_CHUNK, length - done);
            long a = addr + done;
            byte[] chunk = session.controlReadBytes(0xC0, AmlProtocol.REQ_READ_MEM,
                    (int) ((a >> 16) & 0xFFFF), (int) (a & 0xFFFF), want, 5000);
            System.arraycopy(chunk, 0, out, done, want);
            done += want;
        }
        return out;
    }

    static final class BootInfo {
        final int stage;
        final long word04;
        final long word08;
        final long word0c;

        BootInfo(int stage, long word04, long word08, long word0c) {
            this.stage = stage;
            this.word04 = word04;
            this.word08 = word08;
            this.word0c = word0c;
        }
    }

    static BootInfo bootInfo(UsbSession session) throws UsbError {
        session.controlWriteNoData(0x40, AmlProtocol.REQ_BOOT_INFO, 0x200, 0, 3000);
        byte[] blk = session.bulkRead(0x200, 3000);
        if (blk.length < 0x12) {
            throw new UsbError("Boot-info block too short (" + blk.length + " bytes).");
        }
        int b10 = blk[0x10] & 0xFF;
        int b11 = blk[0x11] & 0xFF;
        return new BootInfo(
                AmlProtocol.bootStage(b10, b11),
                AmlImage.u32(blk, 0x0c),
                AmlImage.u32(blk, 0x08),
                AmlImage.u32(blk, 0x04));
    }

    // --------------------------------------------------------- bulk phase

    /** Sends one phase-2 text command, returns its status text.
     * Throws {@link UsbCommandException} when the answer is not OKAY. */
    static String sendCommand(UsbSession session, String cmd, int timeoutMs) throws IOException {
        byte[] bytes = (cmd + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        session.bulkWrite(bytes, 0, bytes.length, timeoutMs);
        byte[] st = session.bulkRead(AmlProtocol.STATUS_LEN, timeoutMs);
        if (!AmlProtocol.isOkayStatus(st)) {
            throw new UsbCommandException(
                    "command '" + cmd + "' answered: " + AmlProtocol.statusText(st));
        }
        return AmlProtocol.statusText(st);
    }

    static String sendCommand(UsbSession session, String cmd) throws IOException {
        return sendCommand(session, cmd, 30000);
    }

    /** Polls {@code cmd} (default "download get_status") until OKAY.
     * Long operations (erase, big downloads) need many tries. */
    static String pollCommand(UsbSession session, String cmd, int tries, long delayMs)
            throws IOException {
        IOException last = null;
        for (int i = 0; i < tries; i++) {
            try {
                return sendCommand(session, cmd);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while polling " + cmd, ie);
                }
            }
        }
        throw new UsbCommandException("device did not answer OKAY for '" + cmd + "': " + last);
    }

    static String pollCommand(UsbSession session) throws IOException {
        return pollCommand(session, "download get_status", 120, 1000L);
    }

    /**
     * Bulk download of {@code size} bytes from {@code stream}: per
     * 64 KiB chunk a 0x60 control preamble (wValue = offset&gt;&gt;9,
     * wIndex = length-1), the chunk over bulk EP_OUT, and a 16-byte
     * OKAY status; closed by the 512-byte AMLS tail so the DEVICE
     * verifies the checksum. The stream is consumed exactly
     * {@code size} bytes.
     */
    static void bulkDownload(UsbSession session, InputStream stream, long size,
                             ChunkProgress onProgress, CancelCheck isCancelled)
            throws IOException {
        AmlProtocol.AmlsChecksum checksum = new AmlProtocol.AmlsChecksum();
        byte[] buf = new byte[AmlProtocol.BULK_CHUNK];
        long off = 0;
        while (off < size) {
            if (isCancelled != null && isCancelled.isCancelled()) {
                throw new BurnCancelledException();
            }
            int want = (int) Math.min(AmlProtocol.BULK_CHUNK, size - off);
            readFully(stream, buf, want);
            checksum.update(buf, want);
            int[] preamble = AmlProtocol.chunkPreamble(off, want);
            session.controlWriteNoData(0x40, AmlProtocol.REQ_BULK_PREAMBLE,
                    preamble[0], preamble[1], 5000);
            session.bulkWrite(buf, 0, want, 30000);
            byte[] st = session.bulkRead(AmlProtocol.STATUS_LEN, 30000);
            if (!AmlProtocol.isOkayStatus(st)) {
                throw new UsbError("Device status not OKAY at offset " + off
                        + ": " + AmlProtocol.statusText(st));
            }
            off += want;
            if (onProgress != null) onProgress.onChunk(off, size);
        }
        byte[] tail = AmlProtocol.amlsTail(size, checksum.finish());
        session.bulkWrite(tail, 0, tail.length, 30000);
        byte[] st = session.bulkRead(AmlProtocol.STATUS_LEN, 30000);
        if (!AmlProtocol.isOkayStatus(st)) {
            throw new UsbError("Device checksum verification FAILED: "
                    + AmlProtocol.statusText(st));
        }
    }

    /** One partition write: download line + bulk stream + status poll. */
    static void downloadPartition(UsbSession session, String media, String part,
                                  String imgType, InputStream stream, long size,
                                  boolean hexSize, ChunkProgress onProgress,
                                  CancelCheck isCancelled) throws IOException {
        String cmd = AmlProtocol.downloadLine(media, part, imgType, size, hexSize);
        sendCommand(session, cmd);
        bulkDownload(session, stream, size, onProgress, isCancelled);
        pollCommand(session);
    }

    /** Reads exactly {@code len} bytes or throws (short stream = corrupt input). */
    static void readFully(InputStream stream, byte[] buf, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = stream.read(buf, got, len - got);
            if (n < 0) {
                throw new UsbError("Unexpected end of stream (" + got + "/" + len + " bytes).");
            }
            got += n;
        }
    }

    // --------------------------------------------------------- key phase

    /**
     * Key-phase command path. The vocabulary is confirmed from the
     * real tool binary ({@code key init}, {@code key can_write},
     * {@code key is_burned}, {@code key get_id}, {@code key uninit},
     * {@code download key}, {@code get keyid}), and key material is a
     * device-specific packed blob produced by the tool's KeysProvider
     * subsystem (per-key files: MAC, USID, HDCP, PlayReady, secure-boot
     * keys, ...). The Android app has no key-package picker and never
     * fabricates key material, so the key phase fails closed unless
     * the caller supplies a packed key blob — see README. The exact
     * argument, if any, after {@code "download key "} in the Windows
     * tool is still unverified (the string carries a trailing space,
     * suggesting an argument follows); this port sends the bare
     * command, which u-boot's whitespace splitting treats the same
     * when no argument is present. Hardware confirmation is required
     * before key burning is trusted.
     */
    static String keyInit(UsbSession session) throws IOException {
        return sendCommand(session, AmlProtocol.KeyCommands.INIT);
    }

    static String keyCanWrite(UsbSession session) throws IOException {
        return sendCommand(session, AmlProtocol.KeyCommands.CAN_WRITE);
    }

    static String keyIsBurned(UsbSession session) throws IOException {
        return sendCommand(session, AmlProtocol.KeyCommands.IS_BURNED);
    }

    static String keyGetId(UsbSession session) throws IOException {
        return sendCommand(session, AmlProtocol.KeyCommands.GET_ID);
    }

    static String keyUninit(UsbSession session) throws IOException {
        return sendCommand(session, AmlProtocol.KeyCommands.UNINIT);
    }

    static void downloadKey(UsbSession session, InputStream stream, long size,
                            ChunkProgress onProgress, CancelCheck isCancelled)
            throws IOException {
        sendCommand(session, AmlProtocol.KeyCommands.DOWNLOAD_KEY);
        bulkDownload(session, stream, size, onProgress, isCancelled);
        pollCommand(session);
    }
}
