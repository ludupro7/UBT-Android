package com.cue.amlogicburn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Plain-JVM test harness for the pure engine logic (no Android, no
 * JUnit needed). Run with:
 * <pre>
 *   javac -d /tmp/enginetest-classes \
 *     app/src/main/java/com/cue/amlogicburn/{SeekableSource,AmlProtocol,AmlImage}.java \
 *     app/src/test/java/com/cue/amlogicburn/EngineTest.java
 *   java -cp /tmp/enginetest-classes com.cue.amlogicburn.EngineTest
 * </pre>
 * Every check throws on failure; a clean run ends with
 * "ALL TESTS PASSED".
 */
public class EngineTest {
    private static int passed;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
        passed++;
        System.out.println("ok - " + msg);
    }

    private static void eq(long expected, long actual, String msg) {
        check(expected == actual, msg + " (expected " + expected + ", got " + actual + ")");
    }

    private static void eq(String expected, String actual, String msg) {
        check(expected.equals(actual), msg + " (expected [" + expected + "], got [" + actual + "])");
    }

    /** In-memory SeekableSource for tests. */
    static class MemSource implements SeekableSource {
        final byte[] data;

        MemSource(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public int readAt(long offset, byte[] buf, int off, int len) {
            if (offset >= data.length) return -1;
            int want = (int) Math.min(len, data.length - offset);
            System.arraycopy(data, (int) offset, buf, off, want);
            return want;
        }

        @Override
        public void close() {
        }
    }

    static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    static void putU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) ((v >> (8 * i)) & 0xFF);
    }

    /** Builds a synthetic AMLIMG. CRC uses the Rust convention:
     * standard CRC-32 of bytes[4..EOF] XOR 0xFFFFFFFF. */
    static byte[] syntheticImage(int version, long itemOffset, long itemSize,
                                 byte[] payload, int fileType) {
        int recSize = (version == 2) ? 0x240 : 0x80;
        int textLen = (version == 2) ? 256 : 32;
        int headSize = 0x40;
        long tableEnd = headSize + recSize;
        long total = Math.max(tableEnd + itemSize, itemOffset + itemSize);
        byte[] img = new byte[(int) total];
        putU32(img, 4, version);
        putU32(img, 8, 0x27B51956L);
        putU32(img, 0x18, 1);
        int r = headSize;
        putU32(img, r + 0x00, 0);
        putU32(img, r + 0x04, fileType);
        // CORRECT offset layout: plain LE u64 at 0x10 (low @0x10, high @0x14).
        putU64(img, r + 0x10, itemOffset);
        putU64(img, r + 0x18, itemSize);
        System.arraycopy("PARTITION".getBytes(), 0, img, r + 0x20, "PARTITION".length());
        System.arraycopy("system".getBytes(), 0, img, r + 0x20 + textLen, "system".length());
        if (payload != null) {
            System.arraycopy(payload, 0, img, (int) itemOffset, (int) Math.min(payload.length, itemSize));
        }
        CRC32 crc = new CRC32();
        crc.update(img, 4, img.length - 4);
        putU32(img, 0, crc.getValue() ^ 0xFFFFFFFFL);
        return img;
    }

    public static void main(String[] args) throws Exception {
        // ---- AMLS checksum: one-shot vs incremental ----
        byte[] data = new byte[256003];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 7 + 3);
        long oneShot = AmlProtocol.amlsChecksum(data);
        AmlProtocol.AmlsChecksum inc = new AmlProtocol.AmlsChecksum();
        for (int off = 0; off < data.length; off += 1000) {
            int len = Math.min(1000, data.length - off);
            byte[] chunk = Arrays.copyOfRange(data, off, off + len);
            inc.update(chunk, len);
        }
        eq(oneShot, inc.finish(), "AMLS incremental == one-shot (256003 B)");

        // ---- AMLS tail structure ----
        byte[] tail = AmlProtocol.amlsTail(123456L, 0xDEADBEEFL);
        eq(512, tail.length, "AMLS tail length 512");
        check(tail[0] == 'A' && tail[1] == 'M' && tail[2] == 'L' && tail[3] == 'S',
                "AMLS tail magic");
        eq(123456L, AmlImage.u32(tail, 4), "AMLS tail total");
        eq(0xDEADBEEFL, AmlImage.u32(tail, 8), "AMLS tail checksum");
        eq(0L, AmlImage.u32(tail, 12), "AMLS tail zero field");

        // ---- download lines ----
        eq("download store system normal 123456",
                AmlProtocol.downloadLine("store", "system", "normal", 123456L, false),
                "download line decimal");
        eq("download store system normal 0x1e240",
                AmlProtocol.downloadLine("store", "system", "normal", 123456L, true),
                "download line hex");

        // ---- chunk preamble ----
        int[] p = AmlProtocol.chunkPreamble(0L, 0x10000);
        check(p[0] == 0 && p[1] == 0xFFFF, "chunk preamble (0, 64K)");
        p = AmlProtocol.chunkPreamble(0x20000L, 1);
        check(p[0] == 0x100 && p[1] == 0, "chunk preamble (0x20000, 1)");
        p = AmlProtocol.chunkPreamble(0x100000L, 0x8000);
        check(p[0] == 0x800 && p[1] == 0x7FFF, "chunk preamble (0x100000, 32K)");

        // ---- boot stage map ----
        eq(0xABCD, AmlProtocol.bootStage(0x00, 0), "boot stage (0x00,0)");
        eq(0xABCE, AmlProtocol.bootStage(0x80, 0), "boot stage (0x80,0)");
        eq(0xABCF, AmlProtocol.bootStage(0x00, 1), "boot stage (x,1)");
        eq(0xABD0, AmlProtocol.bootStage(0x00, 2), "boot stage (x,2)");

        // ---- OKAY status ----
        check(AmlProtocol.isOkayStatus("OKAYxxxx........".getBytes()), "OKAY status accepted");
        check(!AmlProtocol.isOkayStatus("FAILxxxx........".getBytes()), "FAIL status rejected");

        // ---- file type mapping ----
        eq("normal", AmlImage.imgTypeName(0x000L), "imgType normal");
        eq("sparse", AmlImage.imgTypeName(0x0FEL), "imgType sparse");
        eq("ubifs", AmlImage.imgTypeName(0x1FEL), "imgType ubifs");

        // ---- v1 parse + stream ----
        byte[] payload = "hello-partition".getBytes();
        byte[] v1 = syntheticImage(1, 0x40 + 0x80, payload.length, payload, 0);
        AmlImage img1 = new AmlImage(new MemSource(v1));
        eq(1, img1.itemCount, "v1 item count");
        eq(0x40 + 0x80, img1.items.get(0).offset, "v1 offset");
        eq("PARTITION", img1.items.get(0).type, "v1 type");
        eq("system", img1.items.get(0).subtype, "v1 subtype");
        check(img1.crcOk(), "v1 CRC OK (Rust convention)");
        InputStream s = img1.openItemStream(img1.items.get(0));
        byte[] got = new byte[payload.length];
        int n = 0;
        while (n < got.length) {
            int r = s.read(got, n, got.length - n);
            if (r < 0) break;
            n += r;
        }
        check(Arrays.equals(payload, got), "v1 item stream reads payload");

        // ---- v2 REGRESSION: HG680P offset 0x2440 ----
        // Real record layout: low word 0x2440 at 0x10, high word 0 at 0x14.
        // The old (swapped) code read this as 0x244000000000.
        byte[] v2 = syntheticImage(2, 0x2440L, payload.length, payload, 0);
        AmlImage img2 = new AmlImage(new MemSource(v2));
        eq(0x2440L, img2.items.get(0).offset, "v2 HG680P offset 0x2440 (not 0x244000000000)");
        check(img2.crcOk(), "v2 CRC OK (Rust convention)");
        InputStream s2 = img2.openItemStream(img2.items.get(0));
        byte[] got2 = new byte[payload.length];
        int n2 = 0;
        while (n2 < got2.length) {
            int r = s2.read(got2, n2, got2.length - n2);
            if (r < 0) break;
            n2 += r;
        }
        check(Arrays.equals(payload, got2), "v2 item stream reads payload at 0x2440");

        // ---- v2 >4 GiB offset pins word order ----
        // low = 0x12345678 @0x10, high = 0x00000001 @0x14 -> 0x112345678.
        byte[] v2big = syntheticImage(2, 0x112345678L, 0, null, 0);
        AmlImage imgBig = new AmlImage(new MemSource(v2big));
        eq(0x112345678L, imgBig.items.get(0).offset, "v2 >4GiB offset word order");

        // ---- CRC rejection + bad magic ----
        byte[] bad = Arrays.copyOf(v2, v2.length);
        bad[0x2440] ^= 0x01;
        check(!new AmlImage(new MemSource(bad)).crcOk(), "CRC rejects flipped payload byte");
        byte[] badMagic = Arrays.copyOf(v2, v2.length);
        putU32(badMagic, 8, 0x12345678L);
        boolean threw = false;
        try {
            new AmlImage(new MemSource(badMagic));
        } catch (AmlImage.AmlImageException e) {
            threw = true;
        }
        check(threw, "bad magic rejected");

        System.out.println("ALL TESTS PASSED (" + passed + " checks)");
    }
}
