package com.cue.amlogicburn;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * AMLIMG firmware image parser — pure Java, zero Android imports, so
 * this file can be unit-tested on a plain JVM.
 *
 * <p>Format (cross-checked against the prototype's own Rust validator
 * in native/src/lib.rs, which reads real images correctly):
 * <pre>
 *   Header, 64 bytes:
 *     @0    u32  CRC32 of the whole file from byte 4 to EOF (see below)
 *     @4    u32  version (0, 1 or 2)
 *     @8    u32  magic 0x27B51956
 *     @0x18 u32  item count
 *   Item records: v0/v1 = 0x80 bytes, v2 = 0x240 bytes:
 *     @0x00 u32  id
 *     @0x04 u32  file_type (0=normal, 0xFE=sparse, 0x1FE=ubi, 0x2FE=ubifs)
 *     @0x08 u64  cursor (ignored when reading)
 *     @0x10 u64  data offset  &lt;-- plain little-endian u64: LOW word at
 *              0x10, HIGH word at 0x14
 *     @0x18 u64  data size
 *     type string:    v0/v1 @0x20..0x40, v2 @0x20..0x120
 *     subtype string: v0/v1 @0x40..0x60, v2 @0x120..0x220
 *     flags u16: v0/v1 verify @0x60, backup @0x64; v2 verify @0x220, backup @0x224
 * </pre></p>
 *
 * <p><b>Offset correction (2026-10-26):</b> an earlier draft port read
 * the offset as high-word@0x10 / low-word@0x14 (the same swapped
 * convention aml-burn.py lines 118-119 carry). A real HG680P v2 image
 * proves that is backwards: its true offset 0x2440 is stored with the
 * low word at 0x10, and the old code misread it as 0x244000000000.
 * This parser reads a plain LE u64 at 0x10, exactly like the Rust
 * validator's {@code read_u64(descriptor, 16)}. The old test fixtures
 * hid the bug by writing the value at 0x14; the regression tests now
 * mirror a real record.</p>
 *
 * <p><b>CRC convention:</b> the stored header CRC equals the standard
 * CRC-32 of bytes [4..EOF] XORed with 0xFFFFFFFF — this is the
 * convention the Rust validator implements
 * ({@code hasher.finalize() ^ 0xffffffff}) and the one the real
 * HG680P image passes. aml-burn.py's plain comparison disagrees with
 * it; for this app the Rust validator is authoritative because it is
 * the app's own validation gate.</p>
 *
 * <p>Unlike aml-burn.py (which reads the whole file into RAM), this
 * parser NEVER loads image data: it reads only the header + the
 * record table, and streams item data on demand through
 * {@link SeekableSource}. A 4.5 GiB item therefore costs no RAM.</p>
 */
public class AmlImage {
    public static final long IMG_MAGIC = 0x27B51956L;
    public static final int HEAD_SIZE = 0x40;

    public static class AmlImageException extends Exception {
        public AmlImageException(String message) { super(message); }
    }

    /** One item (file/partition) inside an AMLIMG container. */
    public static class AmlItem {
        public final int idx;
        public final long itemId;
        public final long fileType;
        public final long offset;
        public final long size;
        public final String type;
        public final String subtype;
        public final boolean isVerify;
        public final boolean isBackup;

        public AmlItem(int idx, long itemId, long fileType, long offset, long size,
                       String type, String subtype, boolean isVerify, boolean isBackup) {
            this.idx = idx;
            this.itemId = itemId;
            this.fileType = fileType;
            this.offset = offset;
            this.size = size;
            this.type = type;
            this.subtype = subtype;
            this.isVerify = isVerify;
            this.isBackup = isBackup;
        }

        public String name() {
            return subtype.isEmpty() ? type : type + "." + subtype;
        }
    }

    /** image_type parameter for the phase-2 {@code download} command,
     * mapped from the item file_type (verified against pyamlboot:
     * 0x00 -> normal, 0xFE -> sparse, 0x1FE -> ubifs). */
    public static String imgTypeName(long fileType) {
        if (fileType == 0x000L) return "normal";
        if (fileType == 0x0FEL) return "sparse";
        if (fileType == 0x1FEL) return "ubifs";
        return "normal";
    }

    public static String fileTypeName(long fileType) {
        if (fileType == 0x000L) return "normal";
        if (fileType == 0x0FEL) return "sparse";
        if (fileType == 0x1FEL) return "ubi";
        if (fileType == 0x2FEL) return "ubifs";
        return "unknown";
    }

    private final SeekableSource source;
    public final long crcStored;
    public final int version;
    public final long magic;
    public final int itemCount;
    public final List<AmlItem> items;

    public AmlImage(SeekableSource source) throws IOException, AmlImageException {
        this.source = source;
        if (source.size() < HEAD_SIZE) {
            throw new AmlImageException("File too small for an AMLIMG header.");
        }
        byte[] head = new byte[HEAD_SIZE];
        readFully(0, head);
        crcStored = u32(head, 0);
        version = (int) u32(head, 4);
        magic = u32(head, 8);
        if (magic != IMG_MAGIC) {
            throw new AmlImageException(
                    "Bad magic: 0x" + Long.toHexString(magic)
                            + " (expected 0x27b51956). This is not an Amlogic (AMLIMG) image.");
        }
        final int recSize;
        final int textLen;
        if (version == 2) {
            recSize = 0x240;
            textLen = 256;
        } else if (version == 0 || version == 1) {
            recSize = 0x80;
            textLen = 32;
        } else {
            throw new AmlImageException("Unknown image version " + version + " (known: 0, 1 and 2).");
        }
        itemCount = (int) u32(head, 0x18);
        long tableEnd = (long) HEAD_SIZE + (long) itemCount * recSize;
        if (tableEnd > source.size()) {
            throw new AmlImageException("Item table is truncated (file damaged/incomplete).");
        }
        byte[] table = new byte[itemCount * recSize];
        readFully(HEAD_SIZE, table);
        List<AmlItem> parsed = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            byte[] raw = new byte[recSize];
            System.arraycopy(table, i * recSize, raw, 0, recSize);
            long itemId = u32(raw, 0x00);
            long fileType = u32(raw, 0x04);
            // CORRECTED: plain LE u64 at 0x10 (low word @0x10, high @0x14).
            long offset = u64(raw, 0x10);
            long size = u64(raw, 0x18);
            String type = cstr(raw, 0x20, 0x20 + textLen);
            String subtype = cstr(raw, 0x20 + textLen, 0x20 + 2 * textLen);
            int verifyOff = 0x20 + 2 * textLen;
            boolean verify = u16(raw, verifyOff) != 0;
            boolean backup = u16(raw, verifyOff + 4) != 0;
            parsed.add(new AmlItem(i, itemId, fileType, offset, size, type, subtype, verify, backup));
        }
        items = parsed;
    }

    private void readFully(long offset, byte[] buf) throws IOException, AmlImageException {
        int pos = 0;
        while (pos < buf.length) {
            int n = source.readAt(offset + pos, buf, pos, buf.length - pos);
            if (n <= 0) {
                throw new AmlImageException("Unexpected end of file at offset " + (offset + pos) + ".");
            }
            pos += n;
        }
    }

    /** CRC32 over bytes [4..EOF], streamed (never loads the file).
     * Returns true when (standard CRC-32 XOR 0xFFFFFFFF) equals the
     * stored header CRC — the Rust validator's convention. */
    public boolean crcOk() throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[0x10000];
        long pos = 4;
        while (pos < source.size()) {
            int want = (int) Math.min(buf.length, source.size() - pos);
            int n = source.readAt(pos, buf, 0, want);
            if (n <= 0) break;
            crc.update(buf, 0, n);
            pos += n;
        }
        return (crc.getValue() ^ 0xFFFFFFFFL) == crcStored;
    }

    /** Stream reading one item's payload: [offset, offset+size). */
    public InputStream openItemStream(final AmlItem item) {
        return new InputStream() {
            private long pos = item.offset;
            private final long end = item.offset + item.size;

            @Override
            public int read() throws IOException {
                if (pos >= end) return -1;
                byte[] b = new byte[1];
                int n = source.readAt(pos, b, 0, 1);
                if (n <= 0) return -1;
                pos += n;
                return b[0] & 0xFF;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (pos >= end) return -1;
                int want = (int) Math.min(len, end - pos);
                int n = source.readAt(pos, buf, off, want);
                if (n <= 0) return -1;
                pos += n;
                return n;
            }
        };
    }

    public AmlItem find(String type, String subtype) {
        for (AmlItem it : items) {
            if (it.type.equals(type) && (subtype == null || it.subtype.equals(subtype))) {
                return it;
            }
        }
        return null;
    }

    /** DDR + U-Boot items used by the cold-ROM boot path. */
    public AmlItem[] findBootItems() {
        AmlItem ddr = find("DDR", "USB");
        if (ddr == null) {
            for (AmlItem it : items) {
                if (it.name().toUpperCase().startsWith("DDR")) { ddr = it; break; }
            }
        }
        AmlItem uboot = null;
        for (AmlItem it : items) {
            String n = it.name().toUpperCase();
            if (n.startsWith("UBOOT") && n.endsWith("USB")) { uboot = it; break; }
        }
        if (uboot == null) {
            for (AmlItem it : items) {
                if (it.name().toUpperCase().startsWith("UBOOT")) { uboot = it; break; }
            }
        }
        return new AmlItem[]{ddr, uboot};
    }

    public AmlItem findDtb() {
        for (AmlItem it : items) {
            if (it.type.toUpperCase().equals("DTB")) return it;
        }
        return null;
    }

    /** Flashable partitions: PARTITION items except DTB and boot blobs. */
    public List<AmlItem> partitionItems() {
        AmlItem[] boot = findBootItems();
        AmlItem ddr = boot[0];
        AmlItem uboot = boot[1];
        List<AmlItem> out = new ArrayList<>();
        for (AmlItem it : items) {
            if (!it.subtype.toUpperCase().equals("PARTITION")) continue;
            if (it.type.toUpperCase().equals("DTB")) continue;
            if (it == ddr || it == uboot) continue;
            if (it.name().toUpperCase().startsWith("DDR")) continue;
            out.add(it);
        }
        return out;
    }

    public List<String> sanityWarnings() {
        List<String> out = new ArrayList<>();
        int recSize = (version == 2) ? 0x240 : 0x80;
        long tableEnd = (long) HEAD_SIZE + (long) itemCount * recSize;
        for (AmlItem it : items) {
            if (it.offset + it.size > source.size()) {
                out.add("item " + it.name() + ": offset+size (" + it.offset + "+" + it.size
                        + ") exceeds file size (" + source.size() + ")");
            }
            if (it.size > 0 && it.offset < tableEnd) {
                out.add("item " + it.name() + ": data offset " + it.offset
                        + " overlaps header/item table");
            }
        }
        return out;
    }

    public static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    public static long u64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    public static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static String cstr(byte[] b, int from, int to) {
        int end = from;
        while (end < to && b[end] != 0) end++;
        return new String(b, from, end - from, java.nio.charset.StandardCharsets.UTF_8);
    }
}
