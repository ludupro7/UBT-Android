package com.cue.amlogicburn;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Burn options, mirroring the original tool's checkboxes
 * (BurnCfgUI.ini: EraseFlash, EraseType, EraseBootLoader, Reset,
 * OverWriteKey) and the prototype's config panel.
 */
class BurnOptions {
    /** "Erase flash" checkbox -> disk_initial (level by eraseType). */
    boolean eraseFlash = true;
    /** Prototype erase-mode spinner: 0 = Normal erase, 1 = Force erase all. */
    int eraseType = 0;
    /** "Erase bootloader" checkbox -> erase_bootloader command. */
    boolean eraseBootloader = false;
    /** "Reset after success" -> burn_complete 1 (reboot) instead of 0. */
    boolean resetAfter = true;
    /** "Whether overwrite key" checkbox. Requires keyMaterial; see README. */
    boolean overwriteKey = false;
    /** Post-write verification pass (version + get_status; every
     * download was already device-verified by its AMLS checksum). */
    boolean verifyAfter = true;
    /** Refuse to burn when the image CRC does not match. */
    boolean strictCrc = true;
    /** Use hex download sizes (update.exe lineage) instead of decimal
     * (USB Burning Tool v2.x lineage). Default: decimal. */
    boolean hexDownloadSize = false;
    /** SPL/DDR load address for cold-ROM boot. Null = refuse cold-ROM. */
    Long splLoadAddr = null;
    /** U-Boot load address for cold-ROM boot. Null = refuse cold-ROM. */
    Long ubootLoadAddr = null;
    /** Device-specific PACKED key blob for the key phase, or null.
     * NEVER fabricated by this port. */
    byte[] keyMaterial = null;
}

/** Progress snapshot sent to the UI. overallPercent is -1 outside the
 * partition-download phase (indeterminate step). */
class BurnProgress {
    final String step;
    final String partition;
    final long partDone;
    final long partTotal;
    final int overallPercent;

    BurnProgress(String step, String partition, long partDone, long partTotal, int overallPercent) {
        this.step = step;
        this.partition = partition;
        this.partDone = partDone;
        this.partTotal = partTotal;
        this.overallPercent = overallPercent;
    }
}

class BurnOutcome {
    static final int SUCCESS = 0;
    static final int CANCELLED = 1;
    static final int FAILED = 2;

    final int kind;
    final String reason;

    private BurnOutcome(int kind, String reason) {
        this.kind = kind;
        this.reason = reason;
    }

    static BurnOutcome success() { return new BurnOutcome(SUCCESS, null); }
    static BurnOutcome cancelled() { return new BurnOutcome(CANCELLED, null); }
    static BurnOutcome failed(String reason) { return new BurnOutcome(FAILED, reason); }
}

/** Standalone SPL blob used in place of the image's DDR.USB item
 * during cold-ROM boot. U-Boot still comes from the AMLIMG image.
 * openStream is a factory (not a single stream) because the blob is
 * read twice: upload pass + read-back verify pass. */
class SplOverride {
    interface StreamFactory {
        InputStream open() throws IOException;
    }

    final StreamFactory openStream;
    final long size;

    SplOverride(StreamFactory openStream, long size) {
        this.openStream = openStream;
        this.size = size;
    }
}

/**
 * Supplies USB devices, permission, and sessions to the engine. The
 * service implements this (it owns the permission + attach/detach
 * receivers); the engine itself stays free of Android UI code.
 */
interface DeviceProvider {
    /** A currently-present Amlogic device, or null after timeoutMs. */
    UsbDevice waitForDevice(long timeoutMs) throws IOException;

    /** Blocks until the user grants/denies permission for device. */
    boolean ensurePermission(UsbDevice device) throws IOException;

    /** Opens + claims the device. Caller owns the session. */
    UsbSession openSession(UsbDevice device) throws IOException;

    /** Best-effort wait until no Amlogic device is on the bus. */
    void waitForDetach(long timeoutMs);
}

/**
 * The burn state machine. Runs on a background thread (the burn
 * service launches it on a worker thread); USB permission and
 * re-enumeration waits are blocking calls into {@link DeviceProvider}.
 *
 * <p>AMLIMG bulk flow (aml-burn.py interactive sequence, confirmed
 * against the real tool binary's command strings):
 * <pre>
 *   [erase_bootloader] -&gt; dtb to RAM -&gt; disk_initial -&gt; [key phase]
 *   -&gt; partitions (store) -&gt; save_setting -&gt; burn_complete
 * </pre>
 * Cold-ROM flow:
 * <pre>
 *   chipid -&gt; write DDR.USB -&gt; write UBOOT.USB -&gt; read-back verify
 *   -&gt; bootinfo -&gt; device-side handoff -&gt; bulk flow above
 * </pre></p>
 */
class BurnEngine {
    interface LogListener {
        void onLog(String line);
    }

    interface ProgressListener {
        void onProgress(BurnProgress progress);
    }

    private enum DeviceMode { BULK, ROM }

    private final UsbManager manager;
    private final DeviceProvider provider;
    private final LogListener onLog;
    private final ProgressListener onProgress;

    private volatile boolean cancelled;

    BurnEngine(UsbManager manager, DeviceProvider provider,
               LogListener onLog, ProgressListener onProgress) {
        this.manager = manager;
        this.provider = provider;
        this.onLog = onLog;
        this.onProgress = onProgress;
    }

    void cancel() {
        cancelled = true;
    }

    private void log(String msg) {
        onLog.onLog(msg);
    }

    private void progress(String step, String part, long done, long total, int pct) {
        onProgress.onProgress(new BurnProgress(step, part, done, total, pct));
    }

    private void progress(String step) {
        progress(step, null, 0, 0, -1);
    }

    private void checkCancelled() throws BurnCancelledException {
        if (cancelled) throw new BurnCancelledException();
    }

    private AmlTransfer.CancelCheck cancelCheck() {
        return new AmlTransfer.CancelCheck() {
            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        };
    }

    // ------------------------------------------------ device acquisition

    private UsbSession acquireSession() throws IOException {
        UsbDevice device = provider.waitForDevice(30000L);
        if (device == null) {
            throw new UsbError("No Amlogic device found (1B8E:C003). Put the target in "
                    + "burn/update mode and connect it via USB OTG.");
        }
        if (!provider.ensurePermission(device)) {
            throw new UsbError("USB permission denied by user.");
        }
        return provider.openSession(device);
    }

    /** Re-acquires after a reset: detach (tolerated if already gone),
     * then attach, permission, open, and a get_status handshake. */
    private UsbSession reacquireAfterReset() throws IOException {
        provider.waitForDetach(10000L);
        UsbSession session = acquireSession();
        AmlTransfer.pollCommand(session, "download get_status", 30, 1000L);
        return session;
    }

    private DeviceMode detectMode(UsbSession session) throws IOException {
        // Bulk first: a u-boot update-mode device answers get_status.
        // A maskrom device has no text channel, so this times out and
        // we fall through to the ROM probe (chip identify).
        try {
            AmlTransfer.sendCommand(session, "download get_status", 3000);
            return DeviceMode.BULK;
        } catch (IOException ignored) {
        }
        try {
            AmlTransfer.chipId(session);
            return DeviceMode.ROM;
        } catch (IOException e) {
            throw new UsbError("Device does not answer in bulk mode nor in ROM mode: "
                    + e.getMessage());
        }
    }

    // ------------------------------------------------------ ROM helpers

    private void writeMemStream(UsbSession session, long addr, InputStream stream,
                                long size, String label) throws IOException {
        byte[] buf = new byte[0x1000];
        long off = 0;
        while (off < size) {
            checkCancelled();
            int want = (int) Math.min(buf.length, size - off);
            AmlTransfer.readFully(stream, buf, want);
            byte[] chunk = new byte[want];
            System.arraycopy(buf, 0, chunk, 0, want);
            AmlTransfer.writeMem(session, addr + off, chunk);
            off += want;
            progress("ram:" + label, label, off, size, -1);
        }
    }

    private void verifyMemStream(UsbSession session, long addr, InputStream stream,
                                 long size, String label) throws IOException {
        byte[] buf = new byte[0x1000];
        long off = 0;
        while (off < size) {
            checkCancelled();
            int want = (int) Math.min(buf.length, size - off);
            AmlTransfer.readFully(stream, buf, want);
            byte[] chunk = new byte[want];
            System.arraycopy(buf, 0, chunk, 0, want);
            byte[] back = AmlTransfer.readMem(session, addr + off, want);
            if (!java.util.Arrays.equals(back, chunk)) {
                throw new UsbError("RAM read-back mismatch for " + label + " at offset " + off + ".");
            }
            off += want;
        }
    }

    // ------------------------------------------------------ AMLIMG flow

    BurnOutcome runAmlImage(AmlImage image, BurnOptions options, SplOverride splOverride) {
        UsbSession session = null;
        try {
            // ---- image-validation gate (before any write) ----
            progress("validate");
            for (String w : image.sanityWarnings()) log("WARNING: " + w);
            if (options.strictCrc) {
                log("Checking image CRC (streams the whole file)...");
                if (!image.crcOk()) {
                    return BurnOutcome.failed("Image CRC mismatch - refusing to burn a corrupt image.");
                }
                log("Image CRC OK.");
            } else {
                log("WARNING: image CRC check skipped by option.");
            }
            log("Image: version " + image.version + ", " + image.itemCount + " items.");

            session = acquireSession();
            log("Device opened: " + session.device.getDeviceName());
            DeviceMode mode = detectMode(session);
            log("Device mode: " + mode);
            if (mode == DeviceMode.ROM) {
                session = romPhase(session, image, options, splOverride);
            }
            bulkFlowAmlImage(session, image, options);
            return BurnOutcome.success();
        } catch (BurnCancelledException e) {
            log("Burn cancelled by user.");
            if (session != null) {
                try {
                    AmlTransfer.sendCommand(session, AmlProtocol.burnCompleteCommand(0), 3000);
                } catch (IOException ignored) {
                }
            }
            return BurnOutcome.cancelled();
        } catch (IOException e) {
            return BurnOutcome.failed(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (session != null) session.close();
        }
    }

    /** Cold-ROM phase. Returns the session to continue with (possibly
     * a fresh one after re-enumeration). */
    private UsbSession romPhase(UsbSession sessionIn, AmlImage image,
                                BurnOptions options, SplOverride splOverride) throws IOException {
        UsbSession session = sessionIn;
        progress("rom:identify");
        byte[] cid = AmlTransfer.chipId(session);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(16, cid.length); i++) {
            if (i > 0) hex.append(' ');
            hex.append(String.format("%02x", cid[i]));
        }
        log("Chip ID: " + hex);
        AmlTransfer.BootInfo info = AmlTransfer.bootInfo(session);
        log("Boot stage: 0x" + Integer.toHexString(info.stage));

        AmlImage.AmlItem[] boot = image.findBootItems();
        AmlImage.AmlItem ddr = boot[0];
        AmlImage.AmlItem uboot = boot[1];
        if (uboot == null) {
            throw new UsbError("Cold-ROM boot needs a UBOOT.USB item in the image, but none was found.");
        }
        if (ddr == null && splOverride == null) {
            throw new UsbError("Cold-ROM boot needs DDR.USB in the image or a standalone "
                    + "SPL file, but neither was provided.");
        }
        if (options.splLoadAddr == null) {
            throw new UsbError("Cold-ROM flashing refused: no SPL/DDR load address given. "
                    + "Load addresses are device-specific and must come from "
                    + "the official tool's log for this image "
                    + "('Download DDR.USB buffer at 0x...'). Guessing risks bricking.");
        }
        if (options.ubootLoadAddr == null) {
            throw new UsbError("Cold-ROM flashing refused: no U-Boot load address given "
                    + "(see the official tool's log: 'Download UBOOT.USB buffer at 0x...').");
        }
        final String ddrLabel = (splOverride != null) ? "SPL (standalone file)" : ddr.name();
        final long ddrSize = (splOverride != null) ? splOverride.size : ddr.size;
        progress("rom:ddr", ddrLabel, 0, ddrSize, -1);
        log("Writing " + ddrLabel + " (" + ddrSize + " B) to RAM @0x"
                + Long.toHexString(options.splLoadAddr) + "...");
        InputStream ddrStream = (splOverride != null)
                ? splOverride.openStream.open() : image.openItemStream(ddr);
        try {
            writeMemStream(session, options.splLoadAddr, ddrStream, ddrSize, ddrLabel);
        } finally {
            try { ddrStream.close(); } catch (IOException ignored) { }
        }
        log("Verifying " + ddrLabel + " in RAM...");
        InputStream ddrVerify = (splOverride != null)
                ? splOverride.openStream.open() : image.openItemStream(ddr);
        try {
            verifyMemStream(session, options.splLoadAddr, ddrVerify, ddrSize, ddrLabel);
        } finally {
            try { ddrVerify.close(); } catch (IOException ignored) { }
        }
        progress("rom:uboot", uboot.name(), 0, uboot.size, -1);
        log("Writing " + uboot.name() + " (" + uboot.size + " B) to RAM @0x"
                + Long.toHexString(options.ubootLoadAddr) + "...");
        try (InputStream ubootStream = image.openItemStream(uboot)) {
            writeMemStream(session, options.ubootLoadAddr, ubootStream, uboot.size, uboot.name());
        }
        log("Verifying " + uboot.name() + " in RAM...");
        try (InputStream ubootVerify = image.openItemStream(uboot)) {
            verifyMemStream(session, options.ubootLoadAddr, ubootVerify, uboot.size, uboot.name());
        }
        AmlTransfer.BootInfo info2 = AmlTransfer.bootInfo(session);
        log("Boot stage after upload: 0x" + Integer.toHexString(info2.stage));
        log("DDR + U-Boot are in RAM. Per aml-burn.py, execution handoff is "
                + "device-side (boot-info handshake); waiting for the bulk channel...");
        // Same session first (u-boot may keep the connection), then
        // re-enumeration if the device drops off the bus.
        boolean bulkReady = false;
        for (int i = 0; i < 5; i++) {
            try {
                AmlTransfer.sendCommand(session, "download get_status", 3000);
                bulkReady = true;
                break;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for bulk channel.", ie);
                }
            }
        }
        if (bulkReady) return session;
        log("Device did not answer on this connection; waiting for re-enumeration...");
        session.close();
        return reacquireAfterReset();
    }

    private void bulkFlowAmlImage(UsbSession session, AmlImage image, BurnOptions options)
            throws IOException {
        if (options.eraseBootloader) {
            checkCancelled();
            progress("erase_bootloader");
            log("erase_bootloader...");
            AmlTransfer.sendCommand(session, "erase_bootloader");
            AmlTransfer.pollCommand(session);
            log("erase_bootloader OK.");
        }
        // DTB to RAM BEFORE disk_initial (Amlogic official guide: the
        // flash driver parses the partition table from the DTB).
        AmlImage.AmlItem dtb = image.findDtb();
        if (dtb != null) {
            checkCancelled();
            progress("dtb", dtb.name(), 0, dtb.size, -1);
            log("Downloading DTB to RAM (required before disk_initial)...");
            try (InputStream stream = image.openItemStream(dtb)) {
                final String dtbName = dtb.name();
                final long dtbSize = dtb.size;
                AmlTransfer.downloadPartition(
                        session, "mem", "dtb", AmlImage.imgTypeName(dtb.fileType),
                        stream, dtb.size, options.hexDownloadSize,
                        new AmlTransfer.ChunkProgress() {
                            @Override
                            public void onChunk(long done, long total) {
                                progress("dtb", dtbName, done, total, -1);
                            }
                        },
                        cancelCheck());
            }
            log("DTB in RAM.");
        }
        if (options.eraseFlash) {
            checkCancelled();
            progress("erase_flash");
            // Normal erase -> disk_initial 1 (all except key area).
            // Force erase all -> disk_initial 2 (force except key).
            // The force level is the aml-burn.py documented level 2;
            // hardware confirmation is still pending (see README).
            int level = (options.eraseType == 1) ? 2 : 1;
            log("disk_initial " + level + " (erase flash"
                    + (options.eraseType == 1 ? ", force" : ", keep key area") + ")...");
            AmlTransfer.sendCommand(session, AmlProtocol.diskInitialCommand(level));
            AmlTransfer.pollCommand(session);
            log("disk_initial " + level + " OK.");
        }
        if (options.overwriteKey) {
            if (options.keyMaterial == null) {
                throw new UsbError("Overwrite-key is checked but no key material was supplied. "
                        + "Key material is device-specific and must be provided "
                        + "by the user; this port never fabricates it.");
            }
            checkCancelled();
            progress("key");
            log("Key phase: init -> can_write -> download key -> uninit...");
            AmlTransfer.keyInit(session);
            AmlTransfer.keyCanWrite(session);
            try (InputStream stream = new ByteArrayInputStream(options.keyMaterial)) {
                AmlTransfer.downloadKey(
                        session, stream, options.keyMaterial.length,
                        new AmlTransfer.ChunkProgress() {
                            @Override
                            public void onChunk(long done, long total) {
                                progress("key", "key", done, total, -1);
                            }
                        },
                        cancelCheck());
            }
            AmlTransfer.keyUninit(session);
            log("Key phase done (argument format of 'download key' is "
                    + "hardware-unverified - see README).");
        }
        java.util.List<AmlImage.AmlItem> parts = image.partitionItems();
        long totalBytes = 0;
        for (AmlImage.AmlItem it : parts) totalBytes += it.size;
        long doneBytes = 0;
        for (AmlImage.AmlItem it : parts) {
            checkCancelled();
            final String partName = it.type;
            final long partSize = it.size;
            progress("burn", partName, 0, partSize,
                    totalBytes > 0 ? (int) (doneBytes * 100 / totalBytes) : 0);
            log("Writing partition " + partName + " (" + partSize + " B, "
                    + AmlImage.imgTypeName(it.fileType) + ")...");
            try (InputStream stream = image.openItemStream(it)) {
                final long baseDone = doneBytes;
                final long total = totalBytes;
                AmlTransfer.downloadPartition(
                        session, "store", it.type, AmlImage.imgTypeName(it.fileType),
                        stream, it.size, options.hexDownloadSize,
                        new AmlTransfer.ChunkProgress() {
                            @Override
                            public void onChunk(long done, long tot) {
                                int overall = total > 0
                                        ? (int) ((baseDone + done) * 100 / total) : 0;
                                progress("burn", partName, done, tot, overall);
                            }
                        },
                        cancelCheck());
            }
            doneBytes += it.size;
            log("Partition " + partName + " OK.");
        }
        if (parts.isEmpty()) {
            log("No PARTITION items in this image - nothing to write to flash.");
        }
        checkCancelled();
        progress("save_setting");
        log("save_setting...");
        AmlTransfer.sendCommand(session, "save_setting");
        log("save_setting OK.");
        if (options.verifyAfter) {
            checkCancelled();
            progress("verify");
            log("Verification: version + get_status (each download was "
                    + "already device-verified by its AMLS checksum)...");
            String ver;
            try {
                ver = AmlTransfer.sendCommand(session, "version");
            } catch (IOException e) {
                ver = "unavailable (" + e.getMessage() + ")";
            }
            log("Device version reply: " + ver);
            AmlTransfer.pollCommand(session);
            log("Verification pass OK. NOTE: block-level flash read-back "
                    + "compare has no documented command in the v2.x references "
                    + "and is a known gap (see README).");
        }
        checkCancelled();
        progress("burn_complete");
        int code = options.resetAfter ? 1 : 0;
        log("burn_complete " + code + " (" + (code == 1 ? "reboot" : "power off") + ")...");
        AmlTransfer.sendCommand(session, AmlProtocol.burnCompleteCommand(code));
        log("Burn complete.");
    }

    // ------------------------------------------------------ erase only

    BurnOutcome runErase(boolean eraseFlash, int eraseType, boolean eraseBootloader) {
        UsbSession session = null;
        try {
            session = acquireSession();
            log("Device opened: " + session.device.getDeviceName());
            if (detectMode(session) != DeviceMode.BULK) {
                return BurnOutcome.failed("Erase requires bulk/update mode. Put the target in "
                        + "U-Boot update mode and retry.");
            }
            if (eraseBootloader) {
                checkCancelled();
                progress("erase_bootloader");
                log("erase_bootloader...");
                AmlTransfer.sendCommand(session, "erase_bootloader");
                AmlTransfer.pollCommand(session);
                log("erase_bootloader OK.");
            }
            if (eraseFlash) {
                checkCancelled();
                progress("erase_flash");
                int level = (eraseType == 1) ? 2 : 1;
                log("disk_initial " + level + " (erase flash)...");
                AmlTransfer.sendCommand(session, AmlProtocol.diskInitialCommand(level));
                AmlTransfer.pollCommand(session);
                log("disk_initial " + level + " OK.");
            }
            log("Erase done. Device left in burn mode (no burn_complete, "
                    + "matching aml-burn.py erase).");
            return BurnOutcome.success();
        } catch (BurnCancelledException e) {
            log("Erase cancelled by user.");
            return BurnOutcome.cancelled();
        } catch (IOException e) {
            return BurnOutcome.failed(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (session != null) session.close();
        }
    }
}
