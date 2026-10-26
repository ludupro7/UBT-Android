package com.cue.amlogicburn;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.Os;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * {@link SeekableSource} backed by a Storage Access Framework document.
 *
 * <p>SAF gives us a file descriptor (no storage permission needed), and
 * {@link Os#pread} lets us read at arbitrary offsets — exactly what the
 * streaming AMLIMG parser needs for multi-GB images.</p>
 */
class SafSource implements SeekableSource {
    private final ParcelFileDescriptor pfd;
    private final long size;

    SafSource(ContentResolver resolver, Uri uri) throws IOException {
        ParcelFileDescriptor opened = resolver.openFileDescriptor(uri, "r");
        if (opened == null) {
            throw new FileNotFoundException("Cannot open document: " + uri);
        }
        pfd = opened;
        long sz = pfd.getStatSize();
        if (sz < 0) {
            try (android.database.Cursor c = resolver.query(
                    uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.SIZE);
                    if (idx >= 0 && !c.isNull(idx)) sz = c.getLong(idx);
                }
            }
        }
        if (sz < 0) {
            try { pfd.close(); } catch (IOException ignored) { }
            throw new IOException("Cannot determine size of document: " + uri);
        }
        size = sz;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int readAt(long offset, byte[] buf, int off, int len) throws IOException {
        if (offset >= size) return -1;
        int want = (int) Math.min(len, size - offset);
        try {
            int n = Os.pread(pfd.getFileDescriptor(), buf, off, want, offset);
            if (n < 0) throw new IOException("pread failed at offset " + offset);
            return n;
        } catch (android.system.ErrnoException e) {
            throw new IOException("pread failed at offset " + offset + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        pfd.close();
    }
}
