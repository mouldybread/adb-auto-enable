package com.tpn.adbautoenable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
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
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_SECONDS = 10;
    private static final int WEB_SERVER_PORT = 9093;

    private WebServer webServer;
    private volatile boolean isConfiguring = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AdbConfigService onCreate() called");
        try {
            createNotificationChannel();
            Log.i(TAG, "Notification channel created");

            // Start web server in the service
            startWebServer();
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

    @SuppressWarnings("deprecation")
    private int discoverAdbPortViaMdns() {
        final int[] discoveredPort = {-1};
        final CountDownLatch latch = new CountDownLatch(1);
        String deviceIP = getDeviceIP();
        Log.i(TAG, "Looking for mDNS service on device IP: " + deviceIP);

        NsdManager nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager not available");
            return -1;
        }

        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "mDNS discovery started for " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service found: " + serviceInfo.getServiceName());
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        Log.e(TAG, "Resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        Log.i(TAG, "Service resolved: " + serviceInfo.getServiceName());
                        if (serviceInfo.getHost() != null) {
                            InetAddress hostAddress = serviceInfo.getHost();
                            String host = hostAddress.getHostAddress();
                            if (host == null) {
                                Log.w(TAG, "Host address is null");
                                return;
                            }

                            int port = serviceInfo.getPort();
                            Log.i(TAG, "Host: " + host + ", Port: " + port);
                            if (host.startsWith("127.") || host.equals("::1") ||
                                    host.startsWith("192.168.") || host.startsWith("10.") ||
                                    host.startsWith("172.") || host.startsWith("100.")) {
                                if (host.equals(deviceIP)) {
                                    Log.i(TAG, "Found matching device with IP: " + deviceIP + ", Port: " + port);
                                    discoveredPort[0] = port;
                                } else {
                                    Log.w(TAG, "Skipping device with IP " + host + " (looking for " + deviceIP + ")");
                                }
                            }
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
                latch.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: error " + errorCode);
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);

            boolean found = latch.await(10, TimeUnit.SECONDS);
            if (!found) {
                Log.i(TAG, "mDNS discovery window finished, final port: " + discoveredPort[0]);
            }

            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
                Log.i(TAG, "Discovery stopped, using port: " + discoveredPort[0]);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping discovery", e);
            }

        } catch (Exception e) {
            Log.e(TAG, "mDNS discovery error", e);
        }

        return discoveredPort[0];
    }

    private int scanForAdbPort() {
        Log.i(TAG, "Starting full ephemeral port scan (32768-60999)...");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int lastPort = prefs.getInt(KEY_LAST_PORT, -1);

        AdbHelper adbHelper = new AdbHelper(this);

        if (lastPort > 0 && adbHelper.connect("127.0.0.1", lastPort)) {
            Log.i(TAG, "Found ADB on previously used port: " + lastPort);
            return lastPort;
        }

        final int MIN_PORT = 32768;
        final int MAX_PORT = 60999;
        final int THREAD_COUNT = 64;
        final int TIMEOUT_MS = 50;

        final AtomicInteger foundPort = new AtomicInteger(-1);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int port = MIN_PORT; port <= MAX_PORT; port++) {
            final int currentPort = port;
            executor.submit(() -> {
                if (foundPort.get() != -1) return;

                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", currentPort), TIMEOUT_MS);

                    if (adbHelper.connect("127.0.0.1", currentPort)) {
                        if (foundPort.compareAndSet(-1, currentPort)) {
                            Log.i(TAG, "Full scan found ADB on port: " + currentPort);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Port scan interrupted", e);
        }

        return foundPort.get();
    }

    private String getDeviceIP() {
        return NetworkUtils.getLiveDeviceIP(this);
    }

    private int getTargetPort() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_TARGET_PORT, 5555);
    }

    private void updateStatus(String status) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_STATUS, status).apply();
    }

    private void saveLastPort(int port) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_LAST_PORT, port).apply();
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