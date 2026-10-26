package com.cue.amlogicburn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Broadcast actions for burn progress/log/result (package-scoped). */
class BurnBroadcasts {
    static final String ACTION_PROGRESS = "com.cue.amlogicburn.BURN_PROGRESS";
    static final String ACTION_LOG = "com.cue.amlogicburn.BURN_LOG";
    static final String ACTION_RESULT = "com.cue.amlogicburn.BURN_RESULT";
}

/** Service actions (startService intents). */
class BurnServiceActions {
    static final String BURN_AMLIMG = "com.cue.amlogicburn.BURN_AMLIMG";
    static final String CANCEL = "com.cue.amlogicburn.CANCEL";
}

/**
 * Foreground service that owns the burn. The Activity must NEVER own
 * a transfer: a multi-GB burn outlives Activity recreation, and Doze
 * would suspend it without the foreground service + partial wakelock.
 *
 * <p>Progress, log lines and the final result are broadcast (package
 * scoped) so the Activity can show them in its existing Results panel
 * without binding.</p>
 *
 * <p>The service also implements {@link DeviceProvider}: it owns the
 * permission + attach/detach handling for the burn, including
 * re-permission after a mid-burn reset re-enumerates the device
 * (the permission dialog may reappear — the user must allow it
 * again, exactly like the prototype's scan flow).</p>
 */
public class BurnService extends Service {
    private static final String ACTION_USB_PERMISSION = "com.cue.amlogicburn.USB_PERMISSION";
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "burn";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile BurnEngine engine;
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private PowerManager.WakeLock wakeLock;
    private String lastStep = "";
    private int lastPct = -2;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Burn", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && BurnServiceActions.CANCEL.equals(intent.getAction())) {
            BurnEngine eng = engine;
            if (eng == null) {
                stopSelf();
            } else {
                cancelRequested = true;
                eng.cancel();
                broadcastLog("Cancel requested.");
            }
            return START_NOT_STICKY;
        }
        if (running) {
            broadcastLog("A burn is already running.");
            return START_NOT_STICKY;
        }
        if (intent == null || !BurnServiceActions.BURN_AMLIMG.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }
        running = true;
        cancelRequested = false;
        startForegroundCompat(buildNotification("Starting burn..."));
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ubt-android:burn");
            wakeLock.acquire(10 * 60 * 60 * 1000L);
        }
        final Intent work = intent;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                BurnOutcome outcome;
                try {
                    outcome = runBurn(work);
                } catch (Exception e) {
                    outcome = BurnOutcome.failed(e.getMessage() != null ? e.getMessage() : e.toString());
                }
                broadcastResult(outcome);
                finishBurn();
            }
        });
        return START_NOT_STICKY;
    }

    private BurnOutcome runBurn(Intent intent) throws IOException {
        String uriString = intent.getStringExtra("image_uri");
        if (uriString == null) return BurnOutcome.failed("No image URI given.");
        Uri uri = Uri.parse(uriString);
        if (!"content".equals(uri.getScheme())) {
            return BurnOutcome.failed("Image must come from the document picker (content URI).");
        }
        BurnOptions options = new BurnOptions();
        options.eraseFlash = intent.getBooleanExtra("erase_flash", false);
        options.eraseType = intent.getIntExtra("erase_type", 0);
        options.eraseBootloader = intent.getBooleanExtra("erase_bootloader", false);
        options.resetAfter = intent.getBooleanExtra("reset_after", false);
        options.overwriteKey = intent.getBooleanExtra("overwrite_key", false);
        options.strictCrc = intent.getBooleanExtra("strict_crc", true);
        options.hexDownloadSize = false;
        options.keyMaterial = null; // never fabricated; no key picker exists yet

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) return BurnOutcome.failed("USB service unavailable.");

        final BurnEngine[] engineBox = new BurnEngine[1];
        DeviceProvider provider = new ServiceDeviceProvider(manager);
        BurnEngine eng = new BurnEngine(manager, provider,
                new BurnEngine.LogListener() {
                    @Override
                    public void onLog(String line) {
                        broadcastLog(line);
                    }
                },
                new BurnEngine.ProgressListener() {
                    @Override
                    public void onProgress(BurnProgress p) {
                        broadcastProgress(p);
                    }
                });
        engine = eng;
        engineBox[0] = eng;
        try (SeekableSource source = new SafSource(getContentResolver(), uri)) {
            AmlImage image;
            try {
                image = new AmlImage(source);
            } catch (AmlImage.AmlImageException e) {
                return BurnOutcome.failed("Image parse failed: " + e.getMessage());
            }
            return eng.runAmlImage(image, options, null);
        }
    }

    // ------------------------------------------------ DeviceProvider impl

    private class ServiceDeviceProvider implements DeviceProvider {
        private final UsbManager manager;

        ServiceDeviceProvider(UsbManager manager) {
            this.manager = manager;
        }

        @Override
        public UsbDevice waitForDevice(long timeoutMs) throws IOException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (cancelRequested) throw new BurnCancelledException();
                for (UsbDevice dev : manager.getDeviceList().values()) {
                    if (dev.getVendorId() == AmlProtocol.AML_VID
                            && dev.getProductId() == AmlProtocol.AML_PID) {
                        return dev;
                    }
                }
                sleepQuietly(500);
            }
            return null;
        }

        @Override
        public void waitForDetach(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (cancelRequested) return;
                boolean present = false;
                for (UsbDevice dev : manager.getDeviceList().values()) {
                    if (dev.getVendorId() == AmlProtocol.AML_VID
                            && dev.getProductId() == AmlProtocol.AML_PID) {
                        present = true;
                        break;
                    }
                }
                if (!present) return;
                sleepQuietly(500);
            }
        }

        @Override
        public boolean ensurePermission(final UsbDevice device) throws IOException {
            if (manager.hasPermission(device)) return true;
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean granted = new AtomicBoolean(false);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (dev != null && dev.getDeviceId() == device.getDeviceId()) {
                        granted.set(intent.getBooleanExtra(
                                UsbManager.EXTRA_PERMISSION_GRANTED, false));
                        latch.countDown();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            try {
                Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
                permissionIntent.setPackage(getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(
                        BurnService.this, device.getDeviceId(), permissionIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                manager.requestPermission(device, pending);
                // The user may take a while on the dialog; the burn
                // waits, and Cancel breaks the wait via the flag.
                while (!latch.await(1, TimeUnit.SECONDS)) {
                    if (cancelRequested) throw new BurnCancelledException();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for USB permission.", e);
            } finally {
                try {
                    unregisterReceiver(receiver);
                } catch (RuntimeException ignored) {
                }
            }
            return granted.get();
        }

        @Override
        public UsbSession openSession(UsbDevice device) throws IOException {
            return UsbSession.open(manager, device);
        }

        private void sleepQuietly(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private void finishBurn() {
        running = false;
        engine = null;
        try {
            if (wakeLock != null) wakeLock.release();
        } catch (RuntimeException ignored) {
        }
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("UBT Android")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, buildNotification(text));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void broadcastProgress(BurnProgress p) {
        Intent intent = new Intent(BurnBroadcasts.ACTION_PROGRESS);
        intent.setPackage(getPackageName());
        intent.putExtra("step", p.step);
        intent.putExtra("partition", p.partition);
        intent.putExtra("part_done", p.partDone);
        intent.putExtra("part_total", p.partTotal);
        intent.putExtra("overall_pct", p.overallPercent);
        sendBroadcast(intent);
        String text = (p.partition != null && p.overallPercent >= 0)
                ? p.partition + ": " + p.overallPercent + "%" : p.step;
        if (!p.step.equals(lastStep) || p.overallPercent != lastPct) {
            lastStep = p.step;
            lastPct = p.overallPercent;
            updateNotification(text);
        }
    }

    private void broadcastLog(String line) {
        Intent intent = new Intent(BurnBroadcasts.ACTION_LOG);
        intent.setPackage(getPackageName());
        intent.putExtra("line", line);
        sendBroadcast(intent);
    }

    private void broadcastResult(BurnOutcome outcome) {
        Intent intent = new Intent(BurnBroadcasts.ACTION_RESULT);
        intent.setPackage(getPackageName());
        intent.putExtra("outcome", outcome.kind);
        if (outcome.reason != null) intent.putExtra("reason", outcome.reason);
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        try {
            if (wakeLock != null) wakeLock.release();
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }
}
