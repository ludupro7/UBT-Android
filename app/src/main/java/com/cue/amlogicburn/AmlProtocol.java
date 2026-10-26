package com.cue.amlogicburn;

/**
 * Amlogic USB protocol constants and pure encodings — zero Android
 * imports, unit-testable on a plain JVM.
 *
 * <p>Values are ported from aml-burn.py (clean-room, from the USB
 * Burning Tool v2.x decompile) and cross-checked against the real
 * tool binary extracted from the v2.2.0 installer (tool version
 * 2.1.9.0), whose strings confirm: {@code "download "},
 * {@code "download get_status"}, {@code "download key "},
 * {@code "disk_initial"}, {@code "burn_complete"},
 * {@code "save_setting"}, {@code "key init"}, {@code "key can_write "},
 * {@code "key get_id "}, {@code "key is_burned "}, {@code "key uninit"},
 * {@code "get keyid"}, {@code "erase_bootloader"} and
 * {@code "store erase partition data"}.</p>
 */
public final class AmlProtocol {
    private AmlProtocol() { }

    // --- USB identity -------------------------------------------------
    public static final int AML_VID = 0x1B8E;
    public static final int AML_PID = 0xC003;
    /** Bulk OUT endpoint address on WorldCup devices. */
    public static final int EP_OUT = 2;
    /** Bulk IN endpoint address on WorldCup devices. */
    public static final int EP_IN = 0x81;

    // --- Transfer geometry --------------------------------------------
    /** Bulk chunk size: one 0x60 preamble covers at most 64 KiB,
     * because the preamble's wIndex field is (length - 1) in 16 bits. */
    public static final int BULK_CHUNK = 0x10000;
    /** ROM memory read/write granularity (control transfers). */
    public static final int MEM_CHUNK = 0x40;
    /** Phase-2 status block size. */
    public static final int STATUS_LEN = 16;
    /** AMLS verification tail size. */
    public static final int TAIL_LEN = 0x200;

    // --- ROM control requests (bmRequestType, bRequest) ---------------
    /** Chip identify: read, type 0xC0, 64 bytes. */
    public static final int REQ_CHIP_ID = 0x40;
    /** Write memory: write, type 0x40, 64-byte chunks. */
    public static final int REQ_WRITE_MEM = 1;
    /** Read memory: read, type 0xC0, 64-byte chunks. */
    public static final int REQ_READ_MEM = 2;
    /** Boot info: write, type 0x40, wValue 0x200, then 512-byte bulk read. */
    public static final int REQ_BOOT_INFO = 0x50;
    /** Bulk preamble: write, type 0x40, per bulk chunk. */
    public static final int REQ_BULK_PREAMBLE = 0x60;

    /** True when a status block starts with "OKAY". */
    public static boolean isOkayStatus(byte[] block) {
        return block != null && block.length >= 4
                && block[0] == 'O' && block[1] == 'K'
                && block[2] == 'A' && block[3] == 'Y';
    }

    /** Human-readable part of a status block (text before the first NUL). */
    public static String statusText(byte[] block) {
        if (block == null) return "";
        int end = 0;
        while (end < block.length && block[end] != 0) end++;
        return new String(block, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * AMLS checksum with aml-burn.py semantics: sum of little-endian
     * u32 words over the data, plus each trailing byte (&lt; 4) added
     * one by one. All arithmetic mod 2^32.
     */
    public static long amlsChecksum(byte[] data) {
        long sum = 0;
        int words = data.length / 4;
        for (int i = 0; i < words; i++) {
            int off = i * 4;
            long w = (data[off] & 0xFFL)
                    | ((data[off + 1] & 0xFFL) << 8)
                    | ((data[off + 2] & 0xFFL) << 16)
                    | ((data[off + 3] & 0xFFL) << 24);
            sum = (sum + w) & 0xFFFFFFFFL;
        }
        for (int i = words * 4; i < data.length; i++) {
            sum = (sum + (data[i] & 0xFFL)) & 0xFFFFFFFFL;
        }
        return sum;
    }

    /** Incremental version of {@link #amlsChecksum(byte[])} for streams. */
    public static final class AmlsChecksum {
        private long sum;
        private final byte[] carry = new byte[3];
        private int carryLen;

        public void update(byte[] chunk, int length) {
            int pos = 0;
            // Drain the carry first so word alignment is preserved.
            if (carryLen > 0) {
                int need = 4 - carryLen;
                int take = Math.min(need, length);
                System.arraycopy(chunk, 0, carry, carryLen, take);
                carryLen += take;
                pos += take;
                if (carryLen == 4) {
                    sum = (sum + u32(carry, 0)) & 0xFFFFFFFFL;
                    carryLen = 0;
                }
            }
            while (pos + 4 <= length) {
                sum = (sum + u32(chunk, pos)) & 0xFFFFFFFFL;
                pos += 4;
            }
            int rest = length - pos;
            if (rest > 0) {
                System.arraycopy(chunk, pos, carry, 0, rest);
                carryLen = rest;
            }
        }

        public long finish() {
            long s = sum;
            for (int i = 0; i < carryLen; i++) {
                s = (s + (carry[i] & 0xFFL)) & 0xFFFFFFFFL;
            }
            return s;
        }

        private static long u32(byte[] b, int off) {
            return (b[off] & 0xFFL)
                    | ((b[off + 1] & 0xFFL) << 8)
                    | ((b[off + 2] & 0xFFL) << 16)
                    | ((b[off + 3] & 0xFFL) << 24);
        }
    }

    /** 512-byte AMLS verification tail: 'AMLS' + total + checksum + 0,
     * zero-padded to {@link #TAIL_LEN}. */
    public static byte[] amlsTail(long total, long checksum) {
        byte[] tail = new byte[TAIL_LEN];
        tail[0] = 'A';
        tail[1] = 'M';
        tail[2] = 'L';
        tail[3] = 'S';
        putU32(tail, 4, total);
        putU32(tail, 8, checksum);
        putU32(tail, 12, 0);
        return tail;
    }

    static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    /**
     * Phase-2 download command line.
     *
     * <p>USB Burning Tool v2.x lineage uses a DECIMAL size:
     * {@code download store system normal 123456}. The 2013 update.exe
     * used HEX ({@code download %s %s %s 0x%llx}). This port defaults
     * to DECIMAL (the v2.x behavior); HEX is offered for
     * update.exe-style packages.</p>
     */
    public static String downloadLine(String media, String part, String imgType,
                                      long size, boolean hexSize) {
        String sizeText = hexSize ? "0x" + Long.toHexString(size) : Long.toString(size);
        return "download " + media + " " + part + " " + imgType + " " + sizeText;
    }

    /**
     * 0x60 preamble fields for one bulk chunk: wValue = offset&gt;&gt;9,
     * wIndex = length-1 (both 16-bit). Returns {wValue, wIndex}.
     */
    public static int[] chunkPreamble(long offset, int length) {
        return new int[]{(int) ((offset >> 9) & 0xFFFF), (length - 1) & 0xFFFF};
    }

    /** BL2 boot-stage code from the boot-info block bytes @0x10/@0x11. */
    public static int bootStage(int b10, int b11) {
        if (b11 == 0) return 0xABCD + (b10 >= 0x80 ? 1 : 0);
        if (b11 == 1) return 0xABCF;
        return 0xABD0;
    }

    /**
     * disk_initial levels (aml-burn.py docs comment): 0 = no erase,
     * 1 = everything except key area (the original tool's "erase flash"
     * checkbox), 2 = force except key, 3 = including key,
     * 4 = force clear including key.
     */
    public static String diskInitialCommand(int level) {
        return "disk_initial " + level;
    }

    /** burn_complete reset codes: 0 = power off, 1 = reboot,
     * 2 = off after power key, 3 = off after USB unplug. */
    public static String burnCompleteCommand(int code) {
        return "burn_complete " + code;
    }

    /** Key-phase command names. The real tool's strings carry trailing
     * spaces on the argument-taking forms ({@code "key can_write "},
     * {@code "key get_id "}, {@code "key is_burned "},
     * {@code "download key "}); the engine sends the trimmed base
     * command when no argument is known, which u-boot's whitespace
     * splitting treats identically. Key material itself is
     * device-specific and is NEVER fabricated by this port. */
    public static final class KeyCommands {
        private KeyCommands() { }
        public static final String INIT = "key init";
        public static final String CAN_WRITE = "key can_write";
        public static final String IS_BURNED = "key is_burned";
        public static final String GET_ID = "key get_id";
        public static final String UNINIT = "key uninit";
        public static final String DOWNLOAD_KEY = "download key";
        public static final String GET_KEYID = "get keyid";
        public static final String BURN_KEY = "burn key";
    }
}
