package com.cue.amlogicburn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "burn_settings";
    private static final String ACTION_USB_PERMISSION = "com.cue.amlogicburn.USB_PERMISSION";
    private static final int REQUEST_IMAGE = 4101;
    private static final int AML_VID = 0x1B8E;
    private static final int AML_PID = 0xC003;

    private UsbManager usbManager;
    private String language = "en";
    private boolean darkMode;
    private boolean receiverRegistered;
    private boolean usbChangeReceiverRegistered;
    private boolean scanningForDevice;
    private LinearLayout deviceRows;
    private TextView hostStatus;
    private TextView imageStatus;
    private TextView resultLog;
    private TextView totals;
    private Button scanButton;
    private Button startButton;
    private Button stopButton;
    private CheckBox eraseFlashBox;
    private CheckBox eraseBootBox;
    private CheckBox resetAfterBox;
    private CheckBox overwriteKeyBox;
    private Spinner eraseModeSpinner;
    private android.net.Uri selectedImageUri;
    private boolean imageValid;
    private boolean burning;
    private boolean burnReceiverRegistered;
    private int totalCount;
    private int successCount;
    private int errorCount;
    private String lastBurnStep = "";
    private UsbDevice pendingPermissionDevice;
    private UsbDevice connectedDevice;
    private UsbDeviceConnection connectedConnection;
    private int tableColor;
    private int headerColor;
    private int resultsColor;
    private int borderColor;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler usbHandler = new Handler(Looper.getMainLooper());

    private final Runnable scanLoop = new Runnable() {
        @Override public void run() {
            if (!scanningForDevice) return;
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                scanningForDevice = false;
                refreshDevices();
                return;
            }
            UsbDevice device = findAmlogicDevice();
            if (device != null) {
                handleDetectedDevice(device);
                return;
            }
            usbHandler.postDelayed(this, 750);
        }
    };

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (device == null || !isAmlogicDevice(device)) {
                pendingPermissionDevice = null;
                refreshDevices();
                return;
            }
            if (granted) connectUsbDevice(device);
            else {
                pendingPermissionDevice = null;
                scanningForDevice = false;
                usbHandler.removeCallbacks(scanLoop);
                hostStatus.setText(t("USB permission denied.", "Izin USB ditolak."));
                appendResult(t("USB permission was denied.", "Izin USB ditolak."));
                updateScanButton();
                refreshDevices();
            }
        }
    };

    private final BroadcastReceiver usbChangeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (scanningForDevice) usbHandler.removeCallbacks(scanLoop);
                if (scanningForDevice) usbHandler.post(scanLoop);
                refreshDevices();
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action) && device != null) {
                if (connectedDevice != null && connectedDevice.getDeviceId() == device.getDeviceId()) {
                    closeConnectedDevice();
                    appendResult(t("Amlogic USB ROM device disconnected.", "Perangkat ROM USB Amlogic terputus."));
                }
                if (pendingPermissionDevice != null && pendingPermissionDevice.getDeviceId() == device.getDeviceId()) {
                    pendingPermissionDevice = null;
                    scanningForDevice = true;
                    hostStatus.setText(t("Waiting for compatible Amlogic USB device…", "Menunggu perangkat USB Amlogic yang kompatibel…"));
                    usbHandler.removeCallbacks(scanLoop);
                    usbHandler.post(scanLoop);
                }
                refreshDevices();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        applyStoredTheme();
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        language = getSharedPreferences(PREFS, MODE_PRIVATE).getString("language", "en");
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerLocalReceiver(permissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        receiverRegistered = true;
        registerLocalReceiver(burnReceiver, new IntentFilter(BurnBroadcasts.ACTION_LOG));
        IntentFilter burnProgressFilter = new IntentFilter(BurnBroadcasts.ACTION_PROGRESS);
        registerLocalReceiver(burnReceiver, burnProgressFilter);
        registerLocalReceiver(burnReceiver, new IntentFilter(BurnBroadcasts.ACTION_RESULT));
        burnReceiverRegistered = true;
        buildScreen();
        refreshDevices();
        updateStartButton();
    }

    private void applyStoredTheme() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = preferences.getString("theme", "system");
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        darkMode = "dark".equals(mode) || ("system".equals(mode) && night == Configuration.UI_MODE_NIGHT_YES);
        setTheme(darkMode ? com.cue.amlogicburn.R.style.AppThemeDark : com.cue.amlogicburn.R.style.AppThemeLight);
    }

    private final BroadcastReceiver burnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BurnBroadcasts.ACTION_LOG.equals(action)) {
                String line = intent.getStringExtra("line");
                if (line != null) appendResult(line);
                return;
            }
            if (BurnBroadcasts.ACTION_PROGRESS.equals(action)) {
                // Chunk progress would flood the Results panel: only
                // log step changes and the 100% mark of a partition.
                String step = intent.getStringExtra("step");
                int pct = intent.getIntExtra("overall_pct", -1);
                String part = intent.getStringExtra("partition");
                String key = (step == null ? "" : step) + "|" + (part == null ? "" : part);
                if (!key.equals(lastBurnStep) || pct == 100) {
                    lastBurnStep = key;
                    if (part != null && pct >= 0) {
                        appendResult(t("Progress: ", "Progres: ") + part + " " + pct + "%");
                    } else if (step != null) {
                        appendResult(t("Step: ", "Tahap: ") + step);
                    }
                }
                return;
            }
            if (BurnBroadcasts.ACTION_RESULT.equals(action)) {
                burning = false;
                int outcome = intent.getIntExtra("outcome", BurnOutcome.FAILED);
                totalCount++;
                if (outcome == BurnOutcome.SUCCESS) {
                    successCount++;
                    appendResult(t("Burn succeeded.", "Flash berhasil."));
                } else if (outcome == BurnOutcome.CANCELLED) {
                    appendResult(t("Burn cancelled.", "Flash dibatalkan."));
                } else {
                    errorCount++;
                    String reason = intent.getStringExtra("reason");
                    appendResult(t("Burn failed: ", "Flash gagal: ")
                            + (reason == null ? "" : reason));
                }
                updateTotals();
                updateStartButton();
                refreshDevices();
            }
        }
    };

    private void updateTotals() {
        if (totals == null) return;
        totals.setText(t("Total: " + totalCount + "    Success: " + successCount + "    Error: " + errorCount,
                "Total: " + totalCount + "    Berhasil: " + successCount + "    Gagal: " + errorCount));
    }

    private void updateStartButton() {
        if (startButton == null) return;
        boolean ready = !burning && connectedConnection != null && imageValid;
        startButton.setEnabled(ready);
        startButton.setAlpha(ready ? 1f : 0.55f);
        if (stopButton != null) stopButton.setEnabled(burning);
    }

    private void startBurn() {
        if (burning) return;
        if (connectedDevice == null || connectedConnection == null) {
            appendResult(t("Connect a device first (Scan).", "Sambungkan perangkat dulu (Pindai)."));
            return;
        }
        if (selectedImageUri == null || !imageValid) {
            appendResult(t("Import a valid image first.", "Impor image yang valid dulu."));
            return;
        }
        boolean eraseFlash = eraseFlashBox != null && eraseFlashBox.isChecked();
        boolean eraseBoot = eraseBootBox != null && eraseBootBox.isChecked();
        boolean resetAfter = resetAfterBox != null && resetAfterBox.isChecked();
        boolean overwriteKey = overwriteKeyBox != null && overwriteKeyBox.isChecked();
        int eraseType = (eraseModeSpinner != null && eraseModeSpinner.getSelectedItemPosition() == 1) ? 1 : 0;
        String summary = t("Image: ", "Image: ") + selectedImageUri.getLastPathSegment()
                + "\n" + t("Erase flash: ", "Hapus flash: ") + (eraseFlash
                        ? (eraseType == 1 ? t("force", "paksa") : t("normal", "normal")) : t("no", "tidak"))
                + "\n" + t("Erase bootloader: ", "Hapus bootloader: ") + (eraseBoot ? t("yes", "ya") : t("no", "tidak"))
                + "\n" + t("Overwrite key: ", "Timpa key: ") + (overwriteKey ? t("yes", "ya") : t("no", "tidak"))
                + "\n" + t("Reset after success: ", "Reset setelah berhasil: ") + (resetAfter ? t("yes", "ya") : t("no", "tidak"))
                + "\n\n" + t("This writes the target flash. Continue?",
                        "Ini akan menulis flash target. Lanjutkan?");
        final boolean fEraseFlash = eraseFlash;
        final boolean fEraseBoot = eraseBoot;
        final boolean fResetAfter = resetAfter;
        final boolean fOverwriteKey = overwriteKey;
        final int fEraseType = eraseType;
        new AlertDialog.Builder(this)
                .setTitle(t("Start burn", "Mulai flash"))
                .setMessage(summary)
                .setPositiveButton(t("Start", "Mulai"), (dialog, which) -> {
                    // Hand the device to the burn service: the preview
                    // connection closes here; the service opens its own
                    // connection (permission is already granted to the app
                    // for this device) and owns the whole transfer.
                    closeConnectedDevice();
                    Intent intent = new Intent(MainActivity.this, BurnService.class);
                    intent.setAction(BurnServiceActions.BURN_AMLIMG);
                    intent.putExtra("image_uri", selectedImageUri.toString());
                    intent.putExtra("erase_flash", fEraseFlash);
                    intent.putExtra("erase_type", fEraseType);
                    intent.putExtra("erase_bootloader", fEraseBoot);
                    intent.putExtra("reset_after", fResetAfter);
                    intent.putExtra("overwrite_key", fOverwriteKey);
                    intent.putExtra("strict_crc", true);
                    burning = true;
                    lastBurnStep = "";
                    startService(intent);
                    appendResult(t("Burn started.", "Flash dimulai."));
                    updateStartButton();
                    refreshDevices();
                })
                .setNegativeButton(t("Cancel", "Batal"), null)
                .show();
    }

    private void stopBurn() {
        if (!burning) return;
        Intent intent = new Intent(MainActivity.this, BurnService.class);
        intent.setAction(BurnServiceActions.CANCEL);
        startService(intent);
        appendResult(t("Cancel requested.", "Pembatalan diminta."));
    }

    @Override protected void onStart() {
        super.onStart();
        if (!usbChangeReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerLocalReceiver(usbChangeReceiver, filter);
            usbChangeReceiverRegistered = true;
        }
        if (scanningForDevice) usbHandler.post(scanLoop);
    }

    @Override protected void onStop() {
        usbHandler.removeCallbacks(scanLoop);
        if (usbChangeReceiverRegistered) {
            unregisterReceiver(usbChangeReceiver);
            usbChangeReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        String mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("theme", "system");
        if ("system".equals(mode)) recreate();
    }

    @Override protected void onDestroy() {
        usbHandler.removeCallbacks(scanLoop);
        if (usbChangeReceiverRegistered) unregisterReceiver(usbChangeReceiver);
        if (receiverRegistered) unregisterReceiver(permissionReceiver);
        if (burnReceiverRegistered) unregisterReceiver(burnReceiver);
        closeConnectedDevice();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void registerLocalReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void buildScreen() {
        final int bg = darkMode ? 0xFF11151A : 0xFFECECEC;
        final int card = darkMode ? 0xFF20262D : 0xFFF3F3F3;
        final int text = darkMode ? 0xFFE8EDF2 : 0xFF151515;
        final int muted = darkMode ? 0xFFB2BEC9 : 0xFF3E3E3E;
        tableColor = darkMode ? 0xFF252B32 : 0xFFFFFFE1;
        headerColor = darkMode ? 0xFF303943 : 0xFFE3E3E3;
        resultsColor = darkMode ? 0xFF2C3238 : 0xFFC5C5C5;
        borderColor = darkMode ? 0xFF59636D : 0xFF929292;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(5), dp(8), dp(5));
        root.setBackgroundColor(bg);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = label("UBT Android", 17, text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        toolbar.addView(title, new LinearLayout.LayoutParams(dp(132), dp(38)));
        Button importButton = button(t("Import image", "Impor image"), card, text);
        importButton.setOnClickListener(v -> chooseImage());
        toolbar.addView(importButton, new LinearLayout.LayoutParams(dp(112), dp(36)));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 2f));
        TextView languageLabel = label(t("Language", "Bahasa"), 10, muted, false);
        languageLabel.setSingleLine(true);
        toolbar.addView(languageLabel, margins(-2, dp(34), dp(5), dp(8)));

        Spinner languageSpinner = spinner(new String[]{"English", "Bahasa Indonesia"});
        languageSpinner.setSelection("id".equals(language) ? 1 : 0);
        languageSpinner.setDropDownWidth(dp(180));
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean initial = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initial) { initial = false; return; }
                String selected = position == 1 ? "id" : "en";
                if (!selected.equals(language)) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("language", selected).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        toolbar.addView(languageSpinner, new LinearLayout.LayoutParams(dp(148), dp(34)));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(dp(48), 1));
        TextView viewLabel = label(t("View", "Tampilan"), 10, muted, false);
        viewLabel.setSingleLine(true);
        toolbar.addView(viewLabel, margins(dp(48), dp(34), dp(5), 0));

        String themeMode = getSharedPreferences(PREFS, MODE_PRIVATE).getString("theme", "system");
        Spinner themeSpinner = spinner(new String[]{t("System", "Sistem"), t("Light", "Terang"), t("Dark", "Gelap")});
        themeSpinner.setSelection("light".equals(themeMode) ? 1 : ("dark".equals(themeMode) ? 2 : 0));
        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean initial = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initial) { initial = false; return; }
                String selected = position == 1 ? "light" : (position == 2 ? "dark" : "system");
                if (!selected.equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString("theme", "system"))) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("theme", selected).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        toolbar.addView(themeSpinner, new LinearLayout.LayoutParams(dp(84), dp(34)));
        Button aboutButton = button(t("About", "Tentang"), card, text);
        aboutButton.setOnClickListener(v -> showAbout());
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        toolbar.addView(aboutButton, new LinearLayout.LayoutParams(dp(72), dp(36)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(makeDevicePanel(card, text, muted), new LinearLayout.LayoutParams(-1, 0, 0.58f));
        left.addView(verticalSpace(dp(5)));
        left.addView(makeResultsPanel(card, text, muted), new LinearLayout.LayoutParams(-1, 0, 0.42f));
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 0.64f));
        body.addView(space(dp(6)));
        body.addView(makeConfigPanel(card, text, muted), new LinearLayout.LayoutParams(0, -1, 0.36f));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        imageStatus = label(t("No image loaded", "Belum ada image"), 13, muted, false);
        totals = label(t("Total: 0    Success: 0    Error: 0", "Total: 0    Berhasil: 0    Gagal: 0"), 13, muted, false);
        imageStatus.setTextSize(11);
        imageStatus.setSingleLine(true);
        imageStatus.setEllipsize(TextUtils.TruncateAt.END);
        totals.setTextSize(11);
        totals.setSingleLine(true);
        totals.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(imageStatus, new LinearLayout.LayoutParams(0, dp(24), 0.68f));
        footer.addView(totals, new LinearLayout.LayoutParams(0, dp(24), 0.32f));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(24)));
        setContentView(root);
    }

    private View makeDevicePanel(int card, int text, int muted) {
        LinearLayout panel = panel(card);
        panel.addView(label(t("Devices", "Perangkat"), 14, text, true));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackground(flatBackground(headerColor, borderColor));
        header.addView(tableCell(t("Device ID", "ID perangkat"), 11, text, true, headerColor), new LinearLayout.LayoutParams(0, dp(28), 0.19f));
        header.addView(tableCell(t("Status", "Status"), 11, text, true, headerColor), new LinearLayout.LayoutParams(0, dp(28), 0.48f));
        header.addView(tableCell(t("Time", "Waktu"), 11, text, true, headerColor), new LinearLayout.LayoutParams(0, dp(28), 0.13f));
        header.addView(tableCell(t("Statistic", "Statistik"), 11, text, true, headerColor), new LinearLayout.LayoutParams(0, dp(28), 0.20f));
        panel.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(tableColor);
        deviceRows = new LinearLayout(this);
        deviceRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(deviceRows);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        hostStatus = label("", 11, muted, false);
        hostStatus.setSingleLine(true);
        hostStatus.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(hostStatus);
        return panel;
    }

    private View makeResultsPanel(int card, int text, int muted) {
        LinearLayout panel = panel(card);
        panel.addView(label(t("Results", "Hasil"), 14, text, true));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackground(flatBackground(headerColor, borderColor));
        header.addView(tableCell(t("Device ID", "ID perangkat"), 11, text, true, headerColor), new LinearLayout.LayoutParams(0, dp(27), 0.22f));
        header.addView(tableCell(t("Time", "Waktu"), 11, text, true, headerColor), new LinearLayout.LayoutParams(0, dp(27), 0.20f));
        header.addView(tableCell(t("Result", "Hasil"), 11, text, true, headerColor), new LinearLayout.LayoutParams(0, dp(27), 0.58f));
        panel.addView(header);
        resultLog = label(t("No operations performed.", "Belum ada operasi."), 11, muted, false);
        resultLog.setGravity(Gravity.TOP | Gravity.LEFT);
        resultLog.setPadding(dp(5), dp(5), dp(5), dp(5));
        resultLog.setBackground(flatBackground(resultsColor, borderColor));
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(true);
        resultScroll.addView(resultLog);
        panel.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return panel;
    }

    private View makeConfigPanel(int card, int text, int muted) {
        LinearLayout panel = panel(card);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button(t("Start", "Mulai"), card, text);
        start.setEnabled(false);
        start.setAlpha(0.55f);
        start.setOnClickListener(v -> startBurn());
        startButton = start;
        Button stop = button(t("Stop", "Hentikan"), card, text);
        stop.setEnabled(false);
        stop.setOnClickListener(v -> stopBurn());
        stopButton = stop;
        Button refresh = button(t("Refresh", "Segarkan"), card, text);
        refresh.setOnClickListener(v -> refreshDevices());
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(44), 1f));
        buttons.addView(space(dp(6)));
        buttons.addView(stop, new LinearLayout.LayoutParams(0, dp(44), 1f));
        buttons.addView(space(dp(6)));
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1f));
        panel.addView(buttons);

        scanButton = button(t("Scan device", "Pindai perangkat"), card, text);
        scanButton.setOnClickListener(v -> {
            if (scanningForDevice) stopDeviceScan();
            else startDeviceScan();
        });
        LinearLayout.LayoutParams accessParams = new LinearLayout.LayoutParams(-1, dp(34));
        accessParams.topMargin = dp(4);
        accessParams.bottomMargin = dp(4);
        panel.addView(scanButton, accessParams);

        panel.addView(label(t("Configuration", "Konfigurasi"), 14, text, true));
        CheckBox eraseFlash = checkBox(t("Erase flash", "Hapus flash"), false, text);
        CheckBox eraseBoot = checkBox(t("Erase bootloader", "Hapus bootloader"), false, text);
        CheckBox resetAfter = checkBox(t("Reset after success", "Reset setelah berhasil"), false, text);
        CheckBox overwriteKey = checkBox(t("Whether overwrite key", "Timpa key"), false, text);
        eraseFlashBox = eraseFlash;
        eraseBootBox = eraseBoot;
        resetAfterBox = resetAfter;
        overwriteKeyBox = overwriteKey;
        panel.addView(eraseFlash);
        Spinner eraseMode = spinner(new String[]{t("Normal erase", "Hapus normal"), t("Force erase all", "Paksa hapus semua")});
        eraseModeSpinner = eraseMode;
        LinearLayout.LayoutParams eraseModeParams = new LinearLayout.LayoutParams(-1, dp(32));
        eraseModeParams.bottomMargin = dp(3);
        panel.addView(eraseMode, eraseModeParams);
        panel.addView(eraseBoot);
        panel.addView(resetAfter);
        panel.addView(overwriteKey);

        panel.addView(label(t("Key (Overwrite)       Available", "Key (Timpa)       Tersedia"), 12, text, true));
        panel.addView(label(t("No key package loaded", "Belum ada paket key"), 11, muted, false));
        TextView noticeHeading = label(t("Notice", "Catatan"), 13, text, true);
        LinearLayout.LayoutParams noticeHeadingParams = new LinearLayout.LayoutParams(-1, dp(22));
        noticeHeadingParams.topMargin = dp(4);
        panel.addView(noticeHeading, noticeHeadingParams);
        panel.addView(label(t("1. Use Android 9+ with USB Host/OTG support.\n2. Connect a compatible Amlogic USB ROM device.\n3. Import an image and review erase/key options.\n4. Flashing writes the target flash - back up first; target support is not universal.",
                "1. Gunakan Android 9+ dengan USB Host/OTG.\n2. Sambungkan perangkat ROM USB Amlogic yang kompatibel.\n3. Impor image dan cek opsi hapus/key.\n4. Flashing menulis flash target - cadangkan dulu; dukungan target belum universal."), 11, muted, false));
        ScrollView configScroll = new ScrollView(this);
        configScroll.setFillViewport(false);
        configScroll.setVerticalScrollBarEnabled(true);
        configScroll.addView(panel);
        return configScroll;
    }

    private void refreshDevices() {
        if (deviceRows == null || usbManager == null) return;
        deviceRows.removeAllViews();
        boolean host = getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        if (!host) {
            hostStatus.setText(t("USB Host/OTG is not supported on this Android device. USB scanning is unavailable.",
                    "Perangkat Android ini tidak mendukung USB Host/OTG. Pemindaian USB tidak tersedia."));
            addDeviceRow(t("USB Host/OTG unavailable", "USB Host/OTG tidak tersedia"), t("Scanning disabled", "Pemindaian dinonaktifkan"));
            scanButton.setEnabled(false);
            return;
        }
        Map<String, UsbDevice> connected = usbManager.getDeviceList();
        if (connectedDevice != null && !isDevicePresent(connected, connectedDevice)) {
            closeConnectedDevice();
            appendResult(t("Amlogic USB ROM device disconnected.", "Perangkat ROM USB Amlogic terputus."));
        }
        if (pendingPermissionDevice != null && !isDevicePresent(connected, pendingPermissionDevice)) {
            pendingPermissionDevice = null;
        }
        updateScanButton();
        if (connectedConnection != null) {
            hostStatus.setText(t("Amlogic ROM connected; no flash commands sent.",
                    "ROM Amlogic terhubung; belum ada perintah flash."));
        } else if (pendingPermissionDevice != null) {
            String deviceName = displayUsbProductName(pendingPermissionDevice);
            hostStatus.setText(t(deviceName + " detected; waiting for USB permission…", deviceName + " terdeteksi; menunggu izin USB…"));
        } else if (scanningForDevice) {
            hostStatus.setText(t("Scanning for compatible Amlogic ROM (USB ID 1B8E:C003).",
                    "Memindai ROM Amlogic yang kompatibel (USB ID 1B8E:C003)."));
        } else {
            hostStatus.setText(t("USB Host/OTG available; Amlogic ROM filter: 1B8E:C003.",
                    "USB Host/OTG tersedia; filter ROM Amlogic: 1B8E:C003."));
        }
        if (connected.isEmpty()) {
            addDeviceRow(scanningForDevice ? t("Waiting for compatible device", "Menunggu perangkat kompatibel") : t("Waiting for USB device", "Menunggu perangkat USB"),
                    scanningForDevice ? t("Scanning…", "Memindai…") : t("No device detected", "Belum terdeteksi"));
            return;
        }
        for (UsbDevice device : connected.values()) {
            String id = String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId());
            boolean amlogic = isAmlogicDevice(device);
            boolean isConnected = connectedDevice != null && connectedDevice.getDeviceId() == device.getDeviceId()
                    && connectedConnection != null;
            boolean permissionPending = pendingPermissionDevice != null
                    && pendingPermissionDevice.getDeviceId() == device.getDeviceId();
            String status = isConnected ? t("Connected", "Terhubung")
                    : permissionPending ? t("Waiting for USB permission", "Menunggu izin USB")
                    : amlogic ? (usbManager.hasPermission(device) ? t("Permission granted; press Scan", "Izin diberikan; tekan Pindai")
                    : t("Amlogic ROM detected; press Scan", "ROM Amlogic terdeteksi; tekan Pindai"))
                    : t("Other USB device", "Perangkat USB lain");
            addDeviceRow(id + "  " + displayUsbProductName(device), status);
        }
        updateStartButton();
    }

    private void addDeviceRow(String id, String status) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(tableCell(id, 11, darkMode ? 0xFFE8EDF2 : 0xFF151515, false, tableColor), new LinearLayout.LayoutParams(0, dp(30), 0.19f));
        row.addView(tableCell(status, 11, darkMode ? 0xFFE8EDF2 : 0xFF151515, false, tableColor), new LinearLayout.LayoutParams(0, dp(30), 0.48f));
        row.addView(tableCell("", 11, darkMode ? 0xFFE8EDF2 : 0xFF151515, false, tableColor), new LinearLayout.LayoutParams(0, dp(30), 0.13f));
        row.addView(tableCell("", 11, darkMode ? 0xFFE8EDF2 : 0xFF151515, false, tableColor), new LinearLayout.LayoutParams(0, dp(30), 0.20f));
        deviceRows.addView(row);
    }

    private boolean isAmlogicDevice(UsbDevice device) {
        return device != null && device.getVendorId() == AML_VID && device.getProductId() == AML_PID;
    }

    private String displayUsbProductName(UsbDevice device) {
        String productName = device == null ? null : device.getProductName();
        if (productName == null || productName.trim().isEmpty() || "null".equalsIgnoreCase(productName.trim())) {
            return t("Device", "Perangkat");
        }
        return productName.trim();
    }

    private UsbDevice findAmlogicDevice() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isAmlogicDevice(device)) return device;
        }
        return null;
    }

    private boolean isDevicePresent(Map<String, UsbDevice> devices, UsbDevice target) {
        if (target == null) return false;
        for (UsbDevice device : devices.values()) {
            if (device.getDeviceId() == target.getDeviceId()) return true;
        }
        return false;
    }

    private void updateScanButton() {
        if (scanButton == null) return;
        boolean host = getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        if (pendingPermissionDevice != null) {
            scanButton.setText(t("Waiting for permission…", "Menunggu izin…"));
            scanButton.setEnabled(false);
        } else if (scanningForDevice) {
            scanButton.setText(t("Cancel scan", "Batalkan pemindaian"));
            scanButton.setEnabled(host);
        } else {
            scanButton.setText(t("Scan device", "Pindai perangkat"));
            scanButton.setEnabled(host);
        }
    }

    private void startDeviceScan() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            appendResult(t("USB host is not supported by this device.", "Perangkat ini tidak mendukung USB host."));
            return;
        }
        if (connectedConnection != null && connectedDevice != null) {
            appendResult(t("Amlogic ROM device is already connected.", "Perangkat ROM Amlogic sudah terhubung."));
            refreshDevices();
            return;
        }
        if (pendingPermissionDevice != null) return;
        scanningForDevice = true;
        updateScanButton();
        hostStatus.setText(t("Scanning for compatible Amlogic ROM (USB ID 1B8E:C003).",
                "Memindai ROM Amlogic yang kompatibel (USB ID 1B8E:C003)."));
        appendResult(t("Scanning for Amlogic ROM (USB ID 1B8E:C003).", "Memindai ROM Amlogic (USB ID 1B8E:C003)."));
        refreshDevices();
        usbHandler.removeCallbacks(scanLoop);
        usbHandler.post(scanLoop);
    }

    private void stopDeviceScan() {
        scanningForDevice = false;
        usbHandler.removeCallbacks(scanLoop);
        updateScanButton();
        hostStatus.setText(t("Device scan stopped.", "Pemindaian perangkat dihentikan."));
        refreshDevices();
    }

    private void handleDetectedDevice(UsbDevice device) {
        if (!isAmlogicDevice(device)) return;
        scanningForDevice = false;
        usbHandler.removeCallbacks(scanLoop);
        if (connectedDevice != null && connectedConnection != null
                && connectedDevice.getDeviceId() == device.getDeviceId()) {
            refreshDevices();
            return;
        }
        pendingPermissionDevice = device;
        updateScanButton();
        String deviceName = displayUsbProductName(device);
        hostStatus.setText(t(deviceName + " detected; requesting USB permission…", deviceName + " terdeteksi; meminta izin USB…"));
        refreshDevices();
        if (usbManager.hasPermission(device)) {
            connectUsbDevice(device);
            return;
        }
        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
        permissionIntent.setPackage(getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(this, device.getDeviceId(), permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            usbManager.requestPermission(device, pending);
        } catch (RuntimeException error) {
            pendingPermissionDevice = null;
            hostStatus.setText(t("Could not request USB permission.", "Tidak dapat meminta izin USB."));
            appendResult(t("USB permission request failed.", "Permintaan izin USB gagal."));
            updateScanButton();
            refreshDevices();
        }
    }

    private void connectUsbDevice(UsbDevice device) {
        if (!isAmlogicDevice(device)) return;
        UsbDeviceConnection connection = usbManager.openDevice(device);
        pendingPermissionDevice = null;
        scanningForDevice = false;
        usbHandler.removeCallbacks(scanLoop);
        if (connection == null) {
            hostStatus.setText(t("USB permission granted, but connection failed.", "Izin USB diberikan, tetapi koneksi gagal."));
            appendResult(t("Could not open the Amlogic USB connection.", "Koneksi USB Amlogic tidak dapat dibuka."));
            updateScanButton();
            refreshDevices();
            return;
        }
        closeConnectedDevice();
        connectedDevice = device;
        connectedConnection = connection;
        hostStatus.setText(t("Amlogic ROM connected; no flash commands sent.",
                "ROM Amlogic terhubung; belum ada perintah flash."));
        appendResult(t("Amlogic ROM connected. No flash data was written.", "ROM Amlogic terhubung. Tidak ada data flash yang ditulis."));
        updateScanButton();
        refreshDevices();
    }

    private void closeConnectedDevice() {
        if (connectedConnection != null) {
            try { connectedConnection.close(); } catch (RuntimeException ignored) { }
        }
        connectedConnection = null;
        connectedDevice = null;
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        imageStatus.setText(t("Checking image header and CRC…", "Memeriksa header dan CRC image…"));
        worker.execute(() -> {
            String result;
            int fd = -1;
            try {
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new IOException("Could not open selected file");
                long size = pfd.getStatSize();
                fd = pfd.detachFd();
                result = NativeCore.validateImageFd(fd, size);
                fd = -1; // Rust takes ownership and closes the descriptor.
            } catch (Throwable error) {
                if (fd >= 0) {
                    try { Os.close(ParcelFileDescriptor.adoptFd(fd).getFileDescriptor()); } catch (Throwable ignored) { }
                }
                result = t("Image check failed: ", "Pemeriksaan image gagal: ") + error.getClass().getSimpleName();
            }
            final String message = result;
            final String display = uri.getLastPathSegment() == null ? uri.toString() : uri.getLastPathSegment();
            final boolean valid = message.startsWith("VALID_IMAGE:");
            runOnUiThread(() -> {
                selectedImageUri = uri;
                imageValid = valid;
                imageStatus.setText(display + " — " + message);
                appendResult(message);
                updateStartButton();
            });
        });
    }

    private void appendResult(String message) {
        if (resultLog == null) return;
        String current = resultLog.getText().toString();
        if (current.equals(t("No operations performed.", "Belum ada operasi."))) current = "";
        String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date()) + "  " + message;
        resultLog.setText(current.isEmpty() ? line : current + "\n" + line);
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(t("About", "Tentang"))
                .setMessage(t("UBT Android is an Android 9+ USB-host tool. It can run on Android devices that support USB Host/OTG; devices without that capability are reported as unsupported. The app can enumerate compatible Amlogic ROM devices, validate image structure/CRC, and flash: ROM/DDR/U-Boot/eMMC flashing, erase operations, key/DRM burning with user-supplied key material only, and flash verification (per-download device checksums plus version/get_status). Flash protocol and target support are not universal.",
                        "UBT Android adalah tool USB host untuk Android 9+. Aplikasi dapat berjalan pada perangkat Android yang mendukung USB Host/OTG; perangkat tanpa kemampuan itu akan ditandai tidak didukung. Aplikasi dapat membaca perangkat ROM Amlogic yang kompatibel, memvalidasi struktur/CRC image, dan melakukan flash: flash ROM/DDR/U-Boot/eMMC, operasi hapus, penulisan key/DRM hanya dengan material key dari pengguna, serta verifikasi flash (checksum perangkat per download plus version/get_status). Protokol flash dan dukungan target tidak berlaku universal."))
                .setPositiveButton("OK", null)
                .show();
    }

    private String t(String english, String indonesian) { return "id".equals(language) ? indonesian : english; }

    private LinearLayout panel(int color) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), dp(3), dp(4), dp(3));
        panel.setBackground(flatBackground(color, borderColor));
        return panel;
    }

    private TextView tableCell(String text, int size, int color, boolean bold, int fillColor) {
        TextView cell = label(text, size, color, bold);
        cell.setSingleLine(true);
        cell.setEllipsize(TextUtils.TruncateAt.END);
        cell.setPadding(dp(4), 0, dp(3), 0);
        cell.setBackground(flatBackground(fillColor, borderColor));
        return cell;
    }

    private GradientDrawable flatBackground(int fillColor, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fillColor);
        shape.setCornerRadius(0f);
        shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String text, int color, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(textColor);
        button.setFocusable(true);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(0);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setBackground(flatBackground(darkMode ? 0xFF343D47 : 0xFFE7E7E7, borderColor));
        button.setOnFocusChangeListener((view, focused) -> view.setBackground(flatBackground(
                focused ? (darkMode ? 0xFF385B78 : 0xFFD7E9FA) : (darkMode ? 0xFF343D47 : 0xFFE7E7E7),
                focused ? (darkMode ? 0xFF90CAF9 : 0xFF3182C4) : borderColor)));
        return button;
    }

    private CheckBox checkBox(String text, boolean checked, int textColor) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(textColor);
        box.setTextSize(12);
        box.setSingleLine(true);
        box.setEllipsize(TextUtils.TruncateAt.END);
        box.setChecked(checked);
        box.setFocusable(true);
        return box;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            private View compactText(View view) {
                if (view instanceof TextView) {
                    TextView text = (TextView) view;
                    text.setTextSize(11);
                    text.setSingleLine(true);
                    text.setEllipsize(TextUtils.TruncateAt.END);
                }
                return view;
            }
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return compactText(super.getView(position, convertView, parent));
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                return compactText(super.getDropDownView(position, convertView, parent));
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setFocusable(true);
        spinner.setMinimumHeight(dp(34));
        spinner.setPadding(dp(2), 0, dp(2), 0);
        return spinner;
    }

    private View space(int width) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return view;
    }

    private View verticalSpace(int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, height));
        return view;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.rightMargin = right;
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
