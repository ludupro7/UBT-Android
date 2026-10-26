package com.cue.amlogicburn;

import java.io.Closeable;
import java.io.IOException;

/**
 * Minimal seekable byte source. Implemented by {@link SafSource} (SAF
 * documents on Android) and by in-memory sources in tests.
 *
 * <p>Extends {@link Closeable} deliberately: callers use
 * try-with-resources on it. (An earlier draft port declared its own
 * {@code close()} without extending {@code Closeable}, which broke
 * every {@code .use} call site at compile time. That mistake is not
 * repeated here.)</p>
 */
public interface SeekableSource extends Closeable {
    long size();

    /**
     * Reads up to {@code len} bytes at {@code offset} into {@code buf}
     * at {@code off}. Returns bytes read, or -1 at end of source.
     */
    int readAt(long offset, byte[] buf, int off, int len) throws IOException;

    @Override
    void close() throws IOException;
}
