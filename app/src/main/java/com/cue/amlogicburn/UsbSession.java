package com.cue.amlogicburn;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.IOException;

/** Thrown for USB transport failures (timeouts, short transfers, ...). */
class UsbError extends IOException {
    UsbError(String message) { super(message); }
}

/**
 * Thin transport over one {@link UsbDeviceConnection}: interface claim,
 * bulk endpoint discovery, looped bulk transfers with per-call
 * byte-count checks, and exact-length control transfers.
 *
 * <p>No root is needed: access comes from the runtime USB permission
 * dialog, exactly like the prototype's detection flow.</p>
 */
class UsbSession implements Closeable {
    final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private final UsbEndpoint epOut;
    private final UsbEndpoint epIn;

    private UsbSession(UsbDevice device, UsbDeviceConnection connection,
                       UsbInterface iface, UsbEndpoint epOut, UsbEndpoint epIn) {
        this.device = device;
        this.connection = connection;
        this.iface = iface;
        this.epOut = epOut;
        this.epIn = epIn;
    }

    /** Finds Amlogic burn-mode devices currently on the bus. */
    static java.util.List<UsbDevice> findDevices(UsbManager manager) {
        java.util.List<UsbDevice> out = new java.util.ArrayList<>();
        for (UsbDevice dev : manager.getDeviceList().values()) {
            if (dev.getVendorId() == AmlProtocol.AML_VID
                    && dev.getProductId() == AmlProtocol.AML_PID) {
                out.add(dev);
            }
        }
        return out;
    }

    /** Opens the device, claims the first interface that carries bulk
     * endpoints, and returns the session. Throws on any failure. */
    static UsbSession open(UsbManager manager, UsbDevice device) throws UsbError {
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) {
            throw new UsbError("Could not open USB device (permission missing?).");
        }
        try {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface itf = device.getInterface(i);
                UsbEndpoint out = null;
                UsbEndpoint in = null;
                for (int e = 0; e < itf.getEndpointCount(); e++) {
                    UsbEndpoint ep = itf.getEndpoint(e);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                }
                if (out == null || in == null) continue;
                if (!conn.claimInterface(itf, true)) continue;
                return new UsbSession(device, conn, itf, out, in);
            }
            throw new UsbError("No interface with bulk IN+OUT endpoints found.");
        } catch (UsbError e) {
            try { conn.close(); } catch (RuntimeException ignored) { }
            throw e;
        }
    }

    /** Exact-length control read returning the bytes. */
    byte[] controlReadBytes(int requestType, int request, int value, int index,
                            int length, int timeoutMs) throws UsbError {
        byte[] buf = new byte[length];
        int n = connection.controlTransfer(requestType, request, value, index, buf, length, timeoutMs);
        if (n != length) {
            throw new UsbError("Control read short: got " + n + " of " + length
                    + " bytes (req 0x" + Integer.toHexString(request) + ").");
        }
        return buf;
    }

    void controlWrite(int requestType, int request, int value, int index,
                      byte[] data, int timeoutMs) throws UsbError {
        int n = connection.controlTransfer(requestType, request, value, index, data, data.length, timeoutMs);
        if (n != data.length) {
            throw new UsbError("Control write short: sent " + n + " of " + data.length
                    + " bytes (req 0x" + Integer.toHexString(request) + ").");
        }
    }

    void controlWriteNoData(int requestType, int request, int value, int index,
                            int timeoutMs) throws UsbError {
        int n = connection.controlTransfer(requestType, request, value, index, null, 0, timeoutMs);
        if (n != 0) {
            throw new UsbError("Control no-data transfer returned " + n
                    + " (req 0x" + Integer.toHexString(request) + ").");
        }
    }

    /** Bulk write, looped until every byte is accepted. */
    void bulkWrite(byte[] data, int off, int len, int timeoutMs) throws UsbError {
        int done = 0;
        while (done < len) {
            int n = connection.bulkTransfer(epOut, data, off + done, len - done, timeoutMs);
            if (n <= 0) {
                throw new UsbError("Bulk write failed at " + done + "/" + len + " bytes.");
            }
            done += n;
        }
    }

    /** Bulk read of exactly {@code len} bytes (looped). */
    byte[] bulkRead(int len, int timeoutMs) throws UsbError {
        byte[] buf = new byte[len];
        int done = 0;
        while (done < len) {
            int n = connection.bulkTransfer(epIn, buf, done, len - done, timeoutMs);
            if (n <= 0) {
                throw new UsbError("Bulk read failed at " + done + "/" + len + " bytes.");
            }
            done += n;
        }
        return buf;
    }

    @Override
    public void close() {
        try { connection.releaseInterface(iface); } catch (RuntimeException ignored) { }
        try { connection.close(); } catch (RuntimeException ignored) { }
    }
}
