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
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AdbConfigService extends Service {
    private static final String TAG = "ADBAutoEnable";
    private static final String CHANNEL_ID = "ADBAutoEnableChannel";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_LAST_PORT = "last_port";
    private static final String KEY_TARGET_PORT = "target_port";
    private static final int INITIAL_BOOT_DELAY_SECONDS = 30;
    private static final int MAX_RETRY_ATTEMPTS = 8;
    private static final int RETRY_DELAY_SECONDS = 15;
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

            // Dual-homed Sony BRAVIA (and similar) need Wi-Fi TLS ADB even when Ethernet is the
            // ConnectivityManager "active" network. Keep this conservative for other OEMs.
            prepareWirelessAdbStack();

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

            int port = discoverAdbPortViaMdns();
            if (port == -1) {
                Log.i(TAG, "mDNS failed, falling back to port scan...");
                updateNotification("mDNS failed, scanning ports...");
                updateStatus("mDNS failed, scanning ports...");
                port = scanForAdbPort();
            }

            if (port == -1) {
                Log.e(TAG, "Could not find ADB port");
                updateStatus("Failed - port not found");
                updateNotification("Failed - port not found");
                return false;
            }

            Log.i(TAG, "Found ADB on port " + port);
            saveLastPort(port);

            int targetPort = getTargetPort();
            Log.i(TAG, "Step 4: Switching to port " + targetPort + "...");
            updateNotification("Switching to port " + targetPort + "...");
            updateStatus("Switching to port " + targetPort + "...");

            AdbHelper adbHelper = new AdbHelper(this);

            // Try loopback first (avoids hangs on some devices), fall back to active device IP (Fixes #8)
            boolean success = adbHelper.switchToPort("127.0.0.1", port, targetPort);
            if (!success && !deviceIP.equals("127.0.0.1")) {
                Log.i(TAG, "Switch failed via loopback, falling back to " + deviceIP + "...");
                success = adbHelper.switchToPort(deviceIP, port, targetPort);
            }

            if (success) {
                String verifyHost = deviceIP;
                String wifiIp = NetworkUtils.getIpv4ForInterface("wlan0");
                if (wifiIp != null) {
                    verifyHost = wifiIp;
                }
                String ethIp = NetworkUtils.getIpv4ForInterface("eth0");
                if (ethIp == null) {
                    ethIp = NetworkUtils.getIpv4ForInterface("eth1");
                }
                boolean verified = waitForTcpPort(verifyHost, targetPort, 15000)
                        || (ethIp != null && waitForTcpPort(ethIp, targetPort, 5000))
                        || waitForTcpPort("127.0.0.1", targetPort, 3000);
                if (!verified) {
                    Log.e(TAG, "tcpip command reported success but target port is not reachable yet");
                    updateStatus("Failed - target port not reachable");
                    updateNotification("Failed - target port not reachable");
                    return false;
                }
                String successHost = ethIp != null ? ethIp : verifyHost;
                Log.i(TAG, "Successfully configured ADB on " + successHost + ":" + targetPort + "!");
                updateStatus("Success - ADB on " + successHost + ":" + targetPort);
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

    @SuppressWarnings("deprecation")
    private int discoverAdbPortViaMdns() {
        final AtomicInteger discoveredPort = new AtomicInteger(-1);
        final CountDownLatch discoveryStarted = new CountDownLatch(1);
        final String activeIP = getDeviceIP();
        final AdbHelper adbHelper = new AdbHelper(this);
        Log.i(TAG, "Looking for mDNS service; active IP=" + activeIP);

        NsdManager nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available");
            return -1;
        }

        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "mDNS discovery started for " + serviceType);
                discoveryStarted.countDown();
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service found: " + serviceInfo.getServiceName());
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        Log.e(TAG, "Resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        if (info.getHost() == null) {
                            Log.w(TAG, "Host address is null");
                            return;
                        }
                        String host = info.getHost().getHostAddress();
                        int port = info.getPort();
                        Log.i(TAG, "mDNS candidate host=" + host + " port=" + port);
                        if (host == null || port < 1 || discoveredPort.get() != -1) {
                            return;
                        }

                        // Sony BRAVIA may advertise TLS ADB on Wi-Fi while ConnectivityManager
                        // reports Ethernet as active. Validate with an ADB handshake.
                        if (adbHelper.connect("127.0.0.1", port)
                                || (!activeIP.equals("127.0.0.1") && adbHelper.connect(activeIP, port))
                                || adbHelper.connect(host, port)) {
                            if (discoveredPort.compareAndSet(-1, port)) {
                                Log.i(TAG, "Validated mDNS ADB port " + port + " via handshake");
                            }
                        } else {
                            Log.w(TAG, "Skipping unvalidated mDNS candidate " + host + ":" + port);
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service lost: " + serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: error " + errorCode);
                discoveryStarted.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: error " + errorCode);
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            discoveryStarted.await(3, TimeUnit.SECONDS);
            for (int i = 0; i < 20 && discoveredPort.get() == -1; i++) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "mDNS discovery error", e);
        } finally {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping discovery", e);
            }
        }

        return discoveredPort.get();
    }

    private int scanForAdbPort() {
        final String activeIP = getDeviceIP();
        final String wifiIp = NetworkUtils.getIpv4ForInterface("wlan0");
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        if (wifiIp != null) hosts.add(wifiIp);
        if (activeIP != null && !activeIP.equals("127.0.0.1")) hosts.add(activeIP);
        hosts.add("127.0.0.1");
        Log.i(TAG, "Starting ephemeral port scan on hosts " + hosts);

        SharedPreferences prefs = getPrefs();
        int lastPort = prefs.getInt(KEY_LAST_PORT, -1);
        AdbHelper probe = new AdbHelper(this);
        if (lastPort > 0) {
            for (String host : hosts) {
                if (probe.connect(host, lastPort)) {
                    Log.i(TAG, "Found ADB on previously used port: " + host + ":" + lastPort);
                    return lastPort;
                }
            }
        }

        final int MIN_PORT = 32768;
        final int MAX_PORT = 60999;
        final int THREAD_COUNT = 96;
        final int TIMEOUT_MS = 80;
        final AtomicInteger foundPort = new AtomicInteger(-1);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (String host : hosts) {
            for (int port = MIN_PORT; port <= MAX_PORT; port++) {
                final int currentPort = port;
                final String scanHost = host;
                executor.submit(() -> {
                    if (foundPort.get() != -1) return;
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(scanHost, currentPort), TIMEOUT_MS);
                        AdbHelper helper = new AdbHelper(AdbConfigService.this);
                        if (helper.connect(scanHost, currentPort)
                                && foundPort.compareAndSet(-1, currentPort)) {
                            Log.i(TAG, "Full scan found ADB on " + scanHost + ":" + currentPort);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(45, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        return foundPort.get();
    }

    private void prepareWirelessAdbStack() {
        try {
            Settings.Global.putInt(getContentResolver(), "wifi_always_requested", 1);
        } catch (Exception e) {
            Log.w(TAG, "Unable to set wifi_always_requested", e);
        }

        boolean sonyLike = isSonyLikeDevice()
                || (NetworkUtils.getIpv4ForInterface("wlan0") != null
                && (NetworkUtils.getIpv4ForInterface("eth0") != null
                || NetworkUtils.getIpv4ForInterface("eth1") != null));
        if (!sonyLike) {
            return;
        }

        Log.i(TAG, "Preparing dual-interface wireless ADB stack (Sony/BRAVIA-style)");
        updateNotification("Preparing Wi-Fi ADB stack...");
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                // Bounce Wi-Fi while Ethernet remains connected so TLS ADB rebinds on wlan0.
                wifiManager.setWifiEnabled(false);
                Thread.sleep(3000);
                wifiManager.setWifiEnabled(true);
                waitForWifiIpv4(60000);
            }
        } catch (Exception e) {
            Log.w(TAG, "Wi-Fi bounce skipped/failed", e);
        }

        try {
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
            Thread.sleep(2000);
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
            Settings.Global.putLong(getContentResolver(), "adb_allowed_connection_time", 0L);
            Log.i(TAG, "Toggled adb_wifi_enabled 0 -> 1");
        } catch (Exception e) {
            Log.w(TAG, "Unable to toggle adb_wifi_enabled", e);
        }
    }

    private boolean isSonyLikeDevice() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String brand = Build.BRAND == null ? "" : Build.BRAND;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String haystack = (manufacturer + " " + brand + " " + model).toLowerCase();
        return haystack.contains("sony") || haystack.contains("bravia");
    }

    private String waitForWifiIpv4(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String ip = NetworkUtils.getIpv4ForInterface("wlan0");
            if (ip != null) {
                Log.i(TAG, "Wi-Fi IPv4 ready: " + ip);
                return ip;
            }
            Thread.sleep(1000);
        }
        Log.w(TAG, "Timed out waiting for wlan0 IPv4");
        return null;
    }

    private boolean waitForTcpPort(String host, int port, long timeoutMs) {
        if (host == null || host.isEmpty()) return false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 500);
                return true;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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
