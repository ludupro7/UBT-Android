package com.cue.amlogicburn;

final class NativeCore {
    static {
        System.loadLibrary("amlburn");
    }

    private NativeCore() { }

    /** Takes ownership of a detached ParcelFileDescriptor fd and closes it in Rust. */
    static native String validateImageFd(int fd, long reportedSize);
}
