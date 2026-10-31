package com.tpn.adbautoenable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;

public class AdbConfigService extends Service {
    private static final String TAG = "ADBAutoEnable";
    private static final String CHANNEL_ID = "ADBAutoEnableChannel";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_LAST_PORT = "last_port";
    private static final String KEY_TARGET_PORT = "target_port";
    private static final int INITIAL_BOOT_DELAY_SECONDS = 30;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_SECONDS = 10;
    private static final int WEB_SERVER_PORT = 9093;

    private WebServer webServer;
    private volatile boolean isConfiguring = false;

    private SharedPreferences getPrefs() {
        return NetworkUtils.getDeviceProtectedPrefs(this, PREFS_NAME);
    }

    // Some devices disable BootReceiver behind the app's back, which takes it out
    // of the BOOT_COMPLETED resolution set and stops the app ever starting at
    // boot. A shell cannot undo that for an app that is not test-only, and
    // reinstalling loses the app's adb key, but the app may set its own
    // components, so it is repaired here on every service start.
    private void ensureBootReceiverEnabled() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName receiver = new ComponentName(this, BootReceiver.class);
            // Anything that is not enabled, rather than the one disabled state:
            // DISABLED_USER and DISABLED_UNTIL_USED keep it out of the resolution
            // set just as surely, and DEFAULT means the manifest value, enabled.
            int state = pm.getComponentEnabledSetting(receiver);
            if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                pm.setComponentEnabledSetting(receiver,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "BootReceiver was disabled, re-enabled it");
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not check or re-enable BootReceiver", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AdbConfigService onCreate() called");

        // Log app version and device model for easier debugging
        String appVersion = "Unknown";
        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersion = pInfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to get package info", e);
        }

        String deviceInfo = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        Log.i(TAG, "Starting ADBAutoEnable v" + appVersion + " on device: " + deviceInfo);

        try {
            ensureBootReceiverEnabled();
            createNotificationChannel();
            Log.i(TAG, "Notification channel created");

            // Only start web server if enabled in preferences
            SharedPreferences prefs = getPrefs();
            if (prefs.getBoolean("web_server_enabled", true)) {
                startWebServer();
            } else {
                Log.i(TAG, "Web server is disabled by user preference");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "AdbConfigService onStartCommand() called");
        Log.i(TAG, "Intent: " + (intent != null ? intent.toString() : "NULL"));
        Log.i(TAG, "Flags: " + flags);

        if (intent != null) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Log.i(TAG, "Extra: " + key + " = " + extras.getString(key));
                }
            } else {
                Log.i(TAG, "Intent has no extras");
            }
        }

        boolean isBootConfigMode = intent != null && intent.getBooleanExtra("boot_config", false);
        Log.i(TAG, "isBootConfigMode: " + isBootConfigMode);

        try {
            // Start as foreground service IMMEDIATELY
            Notification notification = createNotification(
                    isBootConfigMode ? "Starting ADB configuration..." : "Web server running on port " + WEB_SERVER_PORT
            );
            startForeground(1, notification);
            Log.i(TAG, "Started foreground service with notification");

            // Ensure the web server state matches the user's preference
            SharedPreferences prefs = getPrefs();
            if (prefs.getBoolean("web_server_enabled", true)) {
                // NanoHTTPD's isAlive() checks if the server thread is actively running
                if (webServer == null || !webServer.isAlive()) {
                    Log.i(TAG, "Web server enabled but not running, starting it now...");
                    startWebServer();
                }
            } else {
                if (webServer != null) {
                    webServer.stop();
                    webServer = null;
                    Log.i(TAG, "Web server disabled by preference, ensuring it is stopped");
                }
            }

            ensureTclAutostartProtectionAsync();
            // Fix: Ensure wireless debugging is enabled on EVERY service start, not just boot events.
            enableWirelessDebuggingImmediately();

            // Only run boot configuration if this is a boot event
            if (isBootConfigMode) {
                if (isConfiguring) {
                    Log.w(TAG, "Configuration already in progress, ignoring duplicate request");
                    return START_STICKY;
                }

                isConfiguring = true;

                // Run configuration in background thread
                new Thread(() -> {
                    try {
                        // Step 1: Wait for WiFi to be connected
                        waitForWifiConnection();
                        // Step 2: Wait for system to stabilize
                        waitForBootStabilization();
                        // Step 3: Attempt configuration with retries
                        configureAdbWithRetries();
                    } catch (Exception e) {
                        Log.e(TAG, "Error in configuration thread", e);
                        updateStatus("Failed - " + e.getMessage());
                        updateNotification("Web server running - Boot config failed");
                    } finally {
                        isConfiguring = false;
                    }
                    // Keep web server running
                    updateNotification("Web server running on port " + WEB_SERVER_PORT);
                }).start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand", e);
        }

        return START_STICKY;
    }

    private void ensureTclAutostartProtectionAsync() {
        new Thread(() -> {
            TclAutostartProtection.Result policy = TclAutostartProtection.apply(
                    getContentResolver(),
                    getPackageName()
            );
            if (!policy.available) {
                return;
            }
            if (!policy.success) {
                Log.w(TAG, "TCL AppBootPolicy protection could not be applied");
                return;
            }

            SharedPreferences prefs = getPrefs();
            if (!prefs.getBoolean("is_paired", false)) {
                Log.i(TAG, "TCL AppBootPolicy applied; ADB pairing is needed for AUTO_START app-op");
                return;
            }

            int targetPort = getTargetPort();
            AdbHelper adbHelper = new AdbHelper(this);
            boolean allowed = adbHelper.ensureTclAutoStart(
                    getDeviceIP(),
                    targetPort,
                    getPackageName()
            );
            if (!allowed) {
                Log.w(TAG, "TCL AUTO_START app-op could not be verified");
            }
        }, "tcl-autostart-protection").start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "AdbConfigService onDestroy() called");

        if (webServer != null) {
            webServer.stop();
            Log.i(TAG, "Web server stopped");
        }
    }

    private void enableWirelessDebuggingImmediately() {
        try {
            Log.i(TAG, "Step 0: Immediately enabling wireless debugging & disabling key revocation...");

            // Enable Wireless Debugging
            Settings.Global.putInt(
                    getContentResolver(),
                    "adb_wifi_enabled",
                    1
            );

            // Disable automatic ADB authorization revocation (0 = never expire)
            Settings.Global.putLong(
                    getContentResolver(),
                    "adb_allowed_connection_time",
                    0L
            );

            Log.i(TAG, "Wireless debugging and key revocation settings successfully updated early");
        } catch (SecurityException e) {
            Log.e(TAG, "Early settings write failed - permission WRITE_SECURE_SETTINGS missing", e);
        } catch (Exception e) {
            Log.e(TAG, "Early settings write unexpected error", e);
        }
    }

    private void startWebServer() {
        try {
            webServer = new WebServer(this, WEB_SERVER_PORT);
            webServer.start();
            Log.i(TAG, "Web server started on port " + WEB_SERVER_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start web server", e);
        }
    }

    private void waitForWifiConnection() throws InterruptedException {
        Log.i(TAG, "Waiting for WiFi connection...");
        updateNotification("Waiting for WiFi...");
        int maxWaitSeconds = 60;
        int waitedSeconds = 0;

        while (waitedSeconds < maxWaitSeconds) {
            if (isWifiConnected()) {
                String deviceIP = getDeviceIP();
                if (!deviceIP.equals("127.0.0.1") && !deviceIP.equals("0.0.0.0")) {
                    Log.i(TAG, "WiFi connected! Device IP: " + deviceIP);
                    return;
                }
            }
            Thread.sleep(1000);
            waitedSeconds++;
            if (waitedSeconds % 10 == 0) {
                Log.i(TAG, "Still waiting for WiFi... (" + waitedSeconds + "s)");
                updateNotification("Waiting for WiFi... (" + waitedSeconds + "s)");
            }
        }
        Log.w(TAG, "WiFi wait timeout - proceeding anyway");
    }

    private boolean isWifiConnected() {
        return NetworkUtils.isNetworkConnected(this);
    }

    private void waitForBootStabilization() throws InterruptedException {
        Log.i(TAG, "Waiting " + INITIAL_BOOT_DELAY_SECONDS + " seconds for system to stabilize...");
        for (int i = INITIAL_BOOT_DELAY_SECONDS; i > 0; i--) {
            updateNotification("System stabilizing... " + i + "s");
            Thread.sleep(1000);
            if (i % 10 == 0) {
                Log.i(TAG, "Boot stabilization: " + i + " seconds remaining");
            }
        }
        Log.i(TAG, "Boot stabilization complete");
    }

    private void configureAdbWithRetries() {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            Log.i(TAG, "Configuration attempt " + attempt + " of " + MAX_RETRY_ATTEMPTS);
            updateNotification("Attempt " + attempt + " of " + MAX_RETRY_ATTEMPTS);

            boolean success = configureAdb();
            if (success) {
                Log.i(TAG, "Configuration successful on attempt " + attempt);
                return;
            }

            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.i(TAG, "Attempt " + attempt + " failed, waiting " + RETRY_DELAY_SECONDS + "s before retry...");
                updateNotification("Failed, retrying in " + RETRY_DELAY_SECONDS + "s...");
                try {
                    Thread.sleep(RETRY_DELAY_SECONDS * 1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Retry delay interrupted", e);
                    return;
                }
            }
        }

        Log.e(TAG, "All " + MAX_RETRY_ATTEMPTS + " attempts failed");
        updateStatus("Failed after " + MAX_RETRY_ATTEMPTS + " attempts");
        updateNotification("Failed after " + MAX_RETRY_ATTEMPTS + " attempts");
    }

    private boolean configureAdb() {
        try {
            // Re-assert wireless debugging & auto-revocation settings
            Settings.Global.putInt(
                    getContentResolver(),
                    "adb_wifi_enabled",
                    1
            );
            Settings.Global.putLong(
                    getContentResolver(),
                    "adb_allowed_connection_time",
                    0L
            );

            Log.i(TAG, "Step 2: Waiting for ADB service to start...");
            updateNotification("Waiting for ADB service...");
            Thread.sleep(15000);

            String deviceIP = getDeviceIP();
            Log.i(TAG, "Device IP: " + deviceIP);

            if (deviceIP.equals("127.0.0.1") || deviceIP.equals("0.0.0.0")) {
                Log.e(TAG, "Invalid device IP: " + deviceIP);
                updateStatus("Failed - no valid IP address");
                return false;
            }

            Log.i(TAG, "Step 3: Discovering ADB port...");
            updateNotification("Discovering ADB port...");
            updateStatus("Discovering ADB port...");

            AdbHelper adbHelper = new AdbHelper(this);

            AdbEndpoint endpoint = AdbDiscovery.discover(this);
            if (endpoint == null) {
                Log.i(TAG, "mDNS failed, falling back to port scan...");
                updateNotification("mDNS failed, scanning ports...");
                updateStatus("mDNS failed, scanning ports...");
                int cachedPort = getPrefs().getInt(KEY_LAST_PORT, -1);
                endpoint = AdbPortScanner.find(this, cachedPort,
                        candidate -> adbHelper.connect(candidate.host, candidate.port));
            }

            if (endpoint == null) {
                Log.e(TAG, "Could not find ADB port");
                updateStatus("Failed - port not found");
                updateNotification("Failed - port not found");
                return false;
            }

            Log.i(TAG, "Found ADB at " + endpoint);
            saveLastPort(endpoint.port);

            int targetPort = getTargetPort();
            Log.i(TAG, "Step 4: Switching to port " + targetPort + "...");
            updateNotification("Switching to port " + targetPort + "...");
            updateStatus("Switching to port " + targetPort + "...");

            // Loopback first (avoids hangs on some devices), then the resolved LAN
            // host and every local Wi-Fi/Ethernet address (Fixes #8, Fixes #21).
            boolean success = false;
            for (String host : NetworkUtils.getAdbCandidateHosts(this, endpoint.host)) {
                Log.i(TAG, "Switching via " + host + "...");
                success = adbHelper.switchToPort(host, endpoint.port, targetPort);
                if (success) break;
            }

            if (success) {
                Log.i(TAG, "Successfully configured ADB on port " + targetPort + "!");
                updateStatus("Success - ADB on port " + targetPort);
                updateNotification("Success - ADB on port " + targetPort);
                return true;
            } else {
                Log.e(TAG, "Failed to switch to port " + targetPort);
                updateStatus("Failed - could not switch port");
                updateNotification("Failed - could not switch port");
                return false;
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied - grant WRITE_SECURE_SETTINGS via ADB", e);
            updateStatus("Failed - permission denied");
            updateNotification("Failed - permission denied");
            return false;
        } catch (InterruptedException e) {
            Log.e(TAG, "Thread interrupted", e);
            updateStatus("Failed - interrupted");
            updateNotification("Failed - interrupted");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error", e);
            updateStatus("Failed - " + e.getMessage());
            updateNotification("Failed - error");
            return false;
        }
    }

    private String getDeviceIP() {
        return NetworkUtils.getLiveDeviceIP(this);
    }

    private int getTargetPort() {
        return getPrefs().getInt(KEY_TARGET_PORT, 5555);
    }

    private void updateStatus(String status) {
        getPrefs().edit().putString(KEY_LAST_STATUS, status).apply();
    }

    private void saveLastPort(int port) {
        getPrefs().edit().putInt(KEY_LAST_PORT, port).apply();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ADB Configuration",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("ADB auto-configuration service");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ADB Auto-Enable")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_preferences)
                .build();
    }

    private void updateNotification(String text) {
        try {
            Notification notification = createNotification(text);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(1, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification", e);
        }
    }
}
