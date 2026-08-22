package com.tpn.adbautoenable;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.Log;
import fi.iki.elonen.NanoHTTPD;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebServer extends NanoHTTPD {
    private static final String TAG = "ADBAutoEnable";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_TARGET_PORT = "target_port";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";

    private final Context context;
    private final AdbHelper adbHelper;
    private Boolean permissionCached = null;

    public WebServer(Context context, int port) {
        super(port);
        this.context = context;
        this.adbHelper = new AdbHelper(context);
    }

    private SharedPreferences getPrefs() {
        return NetworkUtils.getDeviceProtectedPrefs(context, PREFS_NAME);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (uri.equals("/api/pair") && method == Method.POST) {
            return handlePairing(session);
        } else if (uri.equals("/api/status")) {
            return handleStatus();
        } else if (uri.equals("/api/test")) {
            return handleTest();
        } else if (uri.equals("/api/switch")) {
            return handleSwitch();
        } else if (uri.equals("/api/port") && method == Method.POST) {
            return handleSetPort(session);
        } else if (uri.equals("/api/logs")) {
            return handleLogs();
        } else if (uri.equals("/api/reset") && method == Method.POST) {
            return handleReset();
        } else if (uri.equals("/api/webserver") && method == Method.POST) {
            return handleToggleWebServer(session);
        } else {
            return newFixedLengthResponse(getHTML());
        }
    }

    private Response handleToggleWebServer(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, List<String>> params = session.getParameters();
            List<String> enabledList = params.get("enabled");
            String enabledStr = (enabledList != null && !enabledList.isEmpty()) ? enabledList.get(0) : "true";
            boolean enable = Boolean.parseBoolean(enabledStr);

            SharedPreferences prefs = getPrefs();
            prefs.edit().putBoolean("web_server_enabled", enable).apply();
            Log.i(TAG, "Web API: Web server enabled set to " + enable);

            if (!enable) {
                // Stop server on a background thread after a brief delay so response finishes sending
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        if (context instanceof AdbConfigService) {
                            // Or stop self/server directly if reference is held
                        }
                        stop();
                        Log.i(TAG, "WebServer stopped via API request");
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping web server", e);
                    }
                }).start();
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"success\":true,\"message\":\"Web server " + (enable ? "enabled" : "disabled (will stop shortly)") + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Web API: Toggle web server error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handlePairing(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, List<String>> params = session.getParameters();

            List<String> portList = params.get("port");
            List<String> codeList = params.get("code");

            String portStr = (portList != null && !portList.isEmpty()) ? portList.get(0) : null;
            String code = (codeList != null && !codeList.isEmpty()) ? codeList.get(0) : null;

            Log.i(TAG, "Web API: Received pairing request - port: " + portStr + ", code: " + code);

            if (portStr == null || code == null || portStr.isEmpty() || code.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        "{\"error\":\"Port and code required\"}");
            }

            int port = Integer.parseInt(portStr);
            Log.i(TAG, "Web API: Pairing on port " + port + " with code " + code);

            boolean success = adbHelper.pair("127.0.0.1", port, code);

            if (success) {
                SharedPreferences prefs = getPrefs();
                prefs.edit().putBoolean("is_paired", true).apply();
                Log.i(TAG, "Web API: Pairing successful");

                //Clear permission cache so it re-checks after self-grant
                permissionCached = null;

                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        Log.i(TAG, "Attempting to self-grant WRITE_SECURE_SETTINGS permission");

                        int adbPort = discoverAdbPort();
                        if (adbPort == -1) {
                            Log.w(TAG, "Could not discover ADB port for self-grant, skipping");
                            return;
                        }

                        String deviceIP = getDeviceIP();
                        Log.i(TAG, "Found ADB on port " + adbPort + ", attempting self-grant via " + deviceIP);
                        boolean granted = adbHelper.selfGrantPermission(deviceIP, adbPort,
                                "com.tpn.adbautoenable", "android.permission.WRITE_SECURE_SETTINGS");

                        if (granted) {
                            Log.i(TAG, "Successfully self-granted WRITE_SECURE_SETTINGS permission!");
                        } else {
                            Log.w(TAG, "Failed to self-grant permission, user will need to grant manually");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during self-grant attempt", e);
                    }
                }).start();

                return newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"success\":true,\"message\":\"Pairing successful! Attempting to self-grant permissions...\"}");
            } else {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\":\"Pairing failed. Make sure wireless debugging is enabled and code is correct.\"}");
            }

        } catch (NumberFormatException e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    "{\"error\":\"Invalid port number\"}");
        } catch (Exception e) {
            Log.e(TAG, "Web API: Pairing error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleStatus() {
        Log.d(TAG, "handleStatus() called - checking status");
        SharedPreferences prefs = getPrefs();
        String lastStatus = prefs.getString("last_status", "Not run yet");
        boolean isPaired = prefs.getBoolean("is_paired", false);
        int targetPort = getTargetPort();
        int currentPort = getCurrentPort();
        boolean webServerEnabled = prefs.getBoolean("web_server_enabled", true);

        boolean adbTargetAvailable = checkTargetPortAvailable();

        // Cache permission check - only do it once
        if (permissionCached == null) {
            Log.d(TAG, "handleStatus() - checking WRITE_SECURE_SETTINGS permission (cached)");
            try {
                Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
                permissionCached = true;
                Log.d(TAG, "handleStatus() - permission check SUCCESS");
            } catch (SecurityException e) {
                permissionCached = false;
                Log.d(TAG, "handleStatus() - permission check FAILED");
            }
        }

        String json = String.format(Locale.US,
                "{\"lastStatus\":\"%s\",\"currentPort\":%d,\"isPaired\":%b,\"hasPermission\":%b,\"adb5555Available\":%b,\"targetPort\":%d,\"webServerEnabled\":%b}",
                lastStatus, currentPort, isPaired, permissionCached, adbTargetAvailable, targetPort, webServerEnabled
        );
        Log.d(TAG, "handleStatus() completed");
        return newFixedLengthResponse(Response.Status.OK, "application/json", json);
    }

    private Response handleSetPort(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, List<String>> params = session.getParameters();
            List<String> portList = params.get("port");
            String portStr = (portList != null && !portList.isEmpty()) ? portList.get(0) : null;

            if (portStr == null || portStr.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        "{\"error\":\"Port required\"}");
            }

            int port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                        "{\"error\":\"Invalid port range (1-65535)\"}");
            }

            SharedPreferences prefs = getPrefs();
            prefs.edit().putInt(KEY_TARGET_PORT, port).apply();
            Log.i(TAG, "Web API: Target port updated to " + port);

            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"success\":true,\"message\":\"Target port updated successfully to " + port + "\"}");
        } catch (NumberFormatException e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    "{\"error\":\"Invalid port number\"}");
        } catch (Exception e) {
            Log.e(TAG, "Web API: Set port error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleLogs() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-s", "ADBAutoEnable:*"});

            StringBuilder logs = new StringBuilder();
            try (InputStreamReader isr = new InputStreamReader(process.getInputStream());
                 BufferedReader reader = new BufferedReader(isr)) {

                String line;
                while ((line = reader.readLine()) != null) {
                    logs.append(line).append("\n");
                }
            }

            process.waitFor();

            String logsText = logs.toString();
            if (logsText.isEmpty()) {
                logsText = "No logs found for ADBAutoEnable";
            }

            logsText = logsText.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");

            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"logs\":\"" + logsText + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Failed to read logs", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"Failed to read logs: " + e.getMessage() + "\"}");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private Response handleReset() {
        try {
            Log.i(TAG, "Web API: Resetting pairing status");

            //Clear permission cache on reset too
            permissionCached = null;

            SharedPreferences prefs = getPrefs();
            prefs.edit()
                    .putBoolean("is_paired", false)
                    .apply();

            File keyDir = new File(context.getFilesDir(), "adb_key");
            File pubKeyFile = new File(context.getFilesDir(), "adb_key.pub");
            File certFile = new File(context.getFilesDir(), "adb_cert");

            boolean deleted1 = keyDir.delete();
            boolean deleted2 = pubKeyFile.delete();
            boolean deleted3 = certFile.delete();

            Log.i(TAG, "Deleted adb_key: " + deleted1);
            Log.i(TAG, "Deleted adb_key.pub: " + deleted2);
            Log.i(TAG, "Deleted adb_cert: " + deleted3);

            Log.i(TAG, "Pairing reset successful");
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"success\":true,\"message\":\"Pairing reset successful. Please pair again.\"}");

        } catch (Exception e) {
            Log.e(TAG, "Web API: Reset error", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private boolean checkTargetPortAvailable() {
        String deviceIP = getDeviceIP();
        int targetPort = getTargetPort();

        // Check device IP first (fixes Chromecast/TV devices that don't expose loopback)
        if (!deviceIP.equals("127.0.0.1") && checkSocket(deviceIP, targetPort)) {
            return true;
        }

        // Fallback to loopback
        return checkSocket("127.0.0.1", targetPort);
    }

    private int getCurrentPort() {
        int targetPort = getTargetPort();
        String deviceIP = getDeviceIP();

        // Check target port first
        if (!deviceIP.equals("127.0.0.1") && checkSocket(deviceIP, targetPort)) {
            return targetPort;
        }
        if (checkSocket("127.0.0.1", targetPort)) {
            return targetPort;
        }

        // Check last known successful port if different from target
        SharedPreferences prefs = getPrefs();
        int lastPort = prefs.getInt("last_port", -1);
        if (lastPort > 0 && lastPort != targetPort) {
            if (!deviceIP.equals("127.0.0.1") && checkSocket(deviceIP, lastPort)) {
                return lastPort;
            }
            if (checkSocket("127.0.0.1", lastPort)) {
                return lastPort;
            }
        }

        return -1;
    }

    private boolean checkSocket(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Response handleTest() {
        new Thread(() -> {
            BootReceiver receiver = new BootReceiver();
            receiver.onReceive(context, new android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED));
        }).start();

        return newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"success\":true,\"message\":\"Boot test started. Check logs below for progress.\"}");
    }

    private Response handleSwitch() {
        new Thread(() -> {
            try {
                Log.i(TAG, "Web API: Discovering ADB port...");
                int port = discoverAdbPort();

                if (port == -1) {
                    Log.e(TAG, "Web API: Could not find ADB port");
                    return;
                }

                String deviceIP = getDeviceIP();
                int targetPort = getTargetPort();

                Log.i(TAG, "Web API: Found ADB on port " + port + ", switching to target port " + targetPort + "...");

                // Try loopback first, fall back to live device IP (Fixes #8)
                boolean success = adbHelper.switchToPort("127.0.0.1", port, targetPort);
                if (!success && !deviceIP.equals("127.0.0.1")) {
                    Log.i(TAG, "Web API: Switch failed via loopback, retrying via " + deviceIP + "...");
                    success = adbHelper.switchToPort(deviceIP, port, targetPort);
                }

                if (success) {
                    Log.i(TAG, "Web API: Successfully switched to port " + targetPort);
                } else {
                    Log.e(TAG, "Web API: Failed to switch to port " + targetPort);
                }

            } catch (Exception e) {
                Log.e(TAG, "Web API: Switch error", e);
            }

        }).start();

        return newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"success\":true,\"message\":\"Port switch started. Check logs below for status.\"}");
    }

    private int getTargetPort() {
        return getPrefs().getInt(KEY_TARGET_PORT, 5555);
    }

    private int discoverAdbPort() {
        final int[] discoveredPort = {-1};
        final CountDownLatch latch = new CountDownLatch(1);
        String deviceIP = getDeviceIP();

        Log.i(TAG, "Looking for mDNS service on device IP: " + deviceIP);

        NsdManager nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
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
            @SuppressWarnings("deprecation")
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "Service found: " + serviceInfo.getServiceName());
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        Log.e(TAG, "Resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
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
                Log.e(TAG, "mDNS discovery timed out after 10 seconds");
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener);
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping discovery after timeout", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "mDNS discovery error", e);
        }

        return discoveredPort[0];
    }

    private String getDeviceIP() {
        return NetworkUtils.getLiveDeviceIP(context);
    }

    private String getHTML() {
        String deviceIP = getDeviceIP();
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>ADB Auto-Enable Configuration</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; max-width: 800px; margin: 20px auto; padding: 20px; background: #f5f5f5; }\n" +
                "        .status-bar { position: sticky; top: 0; z-index: 1000; background: #2196F3; color: white; padding: 12px 20px; border-radius: 6px; margin-bottom: 20px; display: flex; align-items: center; box-shadow: 0 4px 6px rgba(0,0,0,0.15); transition: background 0.3s; }\n" +
                "        .status-bar.success { background: #4CAF50; }\n" +
                "        .status-bar.error { background: #f44336; }\n" +
                "        .spinner { width: 18px; height: 18px; border: 3px solid rgba(255,255,255,0.3); border-top: 3px solid white; border-radius: 50%; animation: spin 0.8s linear infinite; margin-right: 12px; flex-shrink: 0; display: none; }\n" +
                "        @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }\n" +
                "        .card { background: white; border-radius: 8px; padding: 20px; margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
                "        h1 { color: #333; margin-top: 0; }\n" +
                "        h2 { color: #666; font-size: 18px; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }\n" +
                "        button { background: #4CAF50; color: white; border: none; padding: 12px 24px; font-size: 16px; border-radius: 4px; cursor: pointer; margin: 5px; }\n" +
                "        button:hover { opacity: 0.9; }\n" +
                "        button.secondary { background: #2196F3; }\n" +
                "        button.warning { background: #ff9800; }\n" +
                "        button.danger { background: #f44336; }\n" +
                "        input { padding: 10px; font-size: 14px; border: 1px solid #ddd; border-radius: 4px; width: 200px; margin: 5px; }\n" +
                "        .status { padding: 8px; border-radius: 4px; margin: 5px 0; }\n" +
                "        .status.good { background: #d4edda; color: #155724; }\n" +
                "        .status.bad { background: #f8d7da; color: #721c24; }\n" +
                "        code { background: #f4f4f4; padding: 2px 6px; border-radius: 3px; font-family: monospace; display: block; margin: 10px 0; white-space: pre-wrap; word-break: break-all; }\n" +
                "        .instruction { background: #e3f2fd; padding: 15px; border-radius: 4px; margin: 10px 0; }\n" +
                "        .success { background: #d4edda; color: #155724; padding: 10px; border-radius: 4px; margin: 10px 0; display: none; }\n" +
                "        .error { background: #f8d7da; color: #721c24; padding: 10px; border-radius: 4px; margin: 10px 0; display: none; }\n" +
                "        .info { background: #d1ecf1; color: #0c5460; padding: 10px; border-radius: 4px; margin: 10px 0; display: none; }\n" +
                "        .status-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid #eee; }\n" +
                "        .status-row:last-child { border-bottom: none; }\n" +
                "        .status-label { font-weight: bold; color: #666; min-width: 150px; }\n" +
                "        .status-value { flex: 1; text-align: right; }\n" +
                "        #logs-container { background: #1e1e1e; color: #d4d4d4; font-family: 'Courier New', monospace; font-size: 12px; padding: 15px; border-radius: 4px; max-height: 400px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; user-select: text; }\n" +
                "        .logs-controls { margin-bottom: 10px; }\n" +
                "        .paused { background: #ff9800; color: white; padding: 5px 10px; border-radius: 3px; font-size: 12px; margin-left: 10px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div id=\"top-status-bar\" class=\"status-bar success\">\n" +
                "        <div class=\"spinner\" id=\"status-spinner\"></div>\n" +
                "        <span id=\"status-text\">System Ready</span>\n" +
                "    </div>\n" +
                "\n" +
                "    <h1>🔧 ADB Auto-Enable Configuration</h1>\n" +
                "    \n" +
                "    <div class=\"card\">\n" +
                "        <h2>📊 System Status</h2>\n" +
                "        <div id=\"status-display\">\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Permission:</div>\n" +
                "                <div class=\"status-value\" id=\"permission-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Pairing Status:</div>\n" +
                "                <div class=\"status-value\" id=\"pairing-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">ADB Target Port:</div>\n" +
                "                <div class=\"status-value\" id=\"port-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Device IP:</div>\n" +
                "                <div class=\"status-value\">" + deviceIP + "</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Last Boot Status:</div>\n" +
                "                <div class=\"status-value\" id=\"last-status\">Loading...</div>\n" +
                "            </div>\n" +
                "            <div class=\"status-row\">\n" +
                "                <div class=\"status-label\">Current Port:</div>\n" +
                "                <div class=\"status-value\" id=\"current-port\">Loading...</div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "        <button onclick=\"refreshStatus()\">🔄 Refresh Status</button>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\" id=\"pairing-card\">\n" +
                "        <h2>🔐 Initial Pairing (One-Time Setup)</h2>\n" +
                "        <div class=\"instruction\">\n" +
                "            <strong>Step 1:</strong> On your Android device, go to:<br>\n" +
                "            <strong>Settings → Developer Options → Wireless Debugging</strong><br>\n" +
                "            Tap <strong>\"Pair device with pairing code\"</strong><br><br>\n" +
                "            <strong>Step 2:</strong> Copy the pairing code and port shown and enter them below:<br>\n" +
                "        </div>\n" +
                "        <div>\n" +
                "            <input type=\"text\" id=\"pair-code\" placeholder=\"Pairing Code\" />\n" +
                "            <input type=\"number\" id=\"pair-port\" placeholder=\"Pairing Port\" />\n" +
                "            <button onclick=\"pairDevice()\">🔗 Pair Device</button>\n" +
                "        </div>\n" +
                "        <div id=\"pair-success\" class=\"success\"></div>\n" +
                "        <div id=\"pair-error\" class=\"error\"></div>\n" +
                "        <p><em>After pairing, the app will attempt to automatically grant itself permissions. Check the status above to verify.</em></p>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\" id=\"paired-card\" style=\"display:none\">\n" +
                "        <h2>✅ Device Paired</h2>\n" +
                "        <p>Your device is successfully paired and ready to use!</p>\n" +
                "        <button onclick=\"resetPairing()\" class=\"danger\">🔄 Reset Pairing</button>\n" +
                "        <div id=\"reset-success\" class=\"success\"></div>\n" +
                "        <div id=\"reset-error\" class=\"error\"></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\" id=\"port-config-card\">\n" +
                "        <h2>⚙️ Target Port Configuration</h2>\n" +
                "        <div class=\"instruction\">\n" +
                "            Configure the target TCP port and switch immediately:\n" +
                "        </div>\n" +
                "        <div>\n" +
                "            <input type=\"number\" id=\"target-port-input\" placeholder=\"Target Port\" />\n" +
                "            <button onclick=\"saveTargetPort()\" class=\"secondary\">💾 Save Port</button>\n" +
                "            <button onclick=\"saveAndSwitchPort()\" class=\"warning\">💾 Save & Switch</button>\n" +
                "        </div>\n" +
                "        <div id=\"port-success\" class=\"success\"></div>\n" +
                "        <div id=\"port-error\" class=\"error\"></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\" id=\"switch-card\">\n" +
                "        <h2>🔄 Switch Target Port</h2>\n" +
                "        <div class=\"instruction\">\n" +
                "            After pairing and enabling wireless debugging, switch ADB to your target port:\n" +
                "        </div>\n" +
                "        <button onclick=\"switchPort()\">🔀 Switch Target Port Now</button>\n" +
                "        <div id=\"switch-info\" class=\"info\"></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\">\n" +
                "        <h2>🌐 Web Interface Control</h2>\n" +
                "        <div class=\"instruction\">\n" +
                "            Disable the web server to run completely silent on future boots and reduce resource usage:\n" +
                "        </div>\n" +
                "        <button onclick=\"toggleWebServer(false)\" class=\"danger\">🛑 Disable Web Server</button>\n" +
                "        <button onclick=\"toggleWebServer(true)\" class=\"secondary\">🟢 Enable Web Server</button>\n" +
                "        <div id=\"web-server-success\" class=\"success\"></div>\n" +
                "        <div id=\"web-server-error\" class=\"error\"></div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class=\"card\">\n" +
                "        <h2>🧪 Testing</h2>\n" +
                "        <div class=\"instruction\">\n" +
                "            Test the full boot configuration sequence:\n" +
                "        </div>\n" +
                "        <button onclick=\"runTest()\">▶️ Run Test Now</button>\n" +
                "        <div id=\"test-info\" class=\"info\"></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class=\"card\">\n" +
                "        <h2>📋 Live Logs</h2>\n" +
                "        <div class=\"logs-controls\">\n" +
                "            <button onclick=\"copyLogs()\" class=\"secondary\">📋 Copy to Clipboard</button>\n" +
                "            <span id=\"paused-indicator\" class=\"paused\" style=\"display:none\">Auto-refresh paused</span>\n" +
                "        </div>\n" +
                "        <div id=\"logs-container\">Loading logs...</div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <script>\n" +
                "        let autoRefreshPaused = false;\n" +
                "        let logsRefreshInterval;\n" +
                "        \n" +
                "        function showActivity(text) {\n" +
                "            const bar = document.getElementById('top-status-bar');\n" +
                "            const spinner = document.getElementById('status-spinner');\n" +
                "            const textEl = document.getElementById('status-text');\n" +
                "            bar.className = 'status-bar';\n" +
                "            spinner.style.display = 'block';\n" +
                "            textEl.textContent = text;\n" +
                "        }\n" +
                "        \n" +
                "        function showResult(text, isSuccess) {\n" +
                "            const bar = document.getElementById('top-status-bar');\n" +
                "            const spinner = document.getElementById('status-spinner');\n" +
                "            const textEl = document.getElementById('status-text');\n" +
                "            bar.className = 'status-bar ' + (isSuccess ? 'success' : 'error');\n" +
                "            spinner.style.display = 'none';\n" +
                "            textEl.textContent = text;\n" +
                "        }\n" +
                "        \n" +
                "        function refreshStatus() {\n" +
                "            fetch('/api/status')\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    document.getElementById('permission-status').innerHTML = data.hasPermission ? \n" +
                "                        '<span class=\"status good\">✓ Granted</span>' : \n" +
                "                        '<span class=\"status bad\">✗ Not granted</span>';\n" +
                "                    document.getElementById('pairing-status').innerHTML = data.isPaired ? \n" +
                "                        '<span class=\"status good\">✓ Paired</span>' : \n" +
                "                        '<span class=\"status bad\">✗ Not paired</span>';\n" +
                "                    document.getElementById('port-status').innerHTML = data.adb5555Available ? \n" +
                "                        '<span class=\"status good\">✓ Available</span>' : \n" +
                "                        '<span class=\"status bad\">✗ Not available</span>';\n" +
                "                    document.getElementById('last-status').textContent = data.lastStatus;\n" +
                "                    document.getElementById('current-port').textContent = (data.currentPort === -1) ? 'NONE' : data.currentPort;\n" +
                "                    \n" +
                "                    const portInput = document.getElementById('target-port-input');\n" +
                "                    if (document.activeElement !== portInput) {\n" +
                "                        portInput.value = data.targetPort;\n" +
                "                    }\n" +
                "                    \n" +
                "                    if (data.isPaired) {\n" +
                "                        document.getElementById('pairing-card').style.display = 'none';\n" +
                "                        document.getElementById('paired-card').style.display = 'block';\n" +
                "                    } else {\n" +
                "                        document.getElementById('pairing-card').style.display = 'block';\n" +
                "                        document.getElementById('paired-card').style.display = 'none';\n" +
                "                    }\n" +
                "                    \n" +
                "                    if (data.adb5555Available) {\n" +
                "                        document.getElementById('switch-card').style.display = 'none';\n" +
                "                    } else {\n" +
                "                        document.getElementById('switch-card').style.display = 'block';\n" +
                "                    }\n" +
                "                });\n" +
                "        }\n" +
                "        \n" +
                "        function saveTargetPort() {\n" +
                "            const port = document.getElementById('target-port-input').value;\n" +
                "            const successDiv = document.getElementById('port-success');\n" +
                "            const errorDiv = document.getElementById('port-error');\n" +
                "            \n" +
                "            successDiv.style.display = 'none';\n" +
                "            errorDiv.style.display = 'none';\n" +
                "            showActivity('Saving target port...');\n" +
                "            \n" +
                "            fetch('/api/port', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'port=' + port\n" +
                "            })\n" +
                "            .then(r => r.json())\n" +
                "            .then(data => {\n" +
                "                if (data.success) {\n" +
                "                    successDiv.textContent = data.message;\n" +
                "                    successDiv.style.display = 'block';\n" +
                "                    showResult(data.message, true);\n" +
                "                    setTimeout(() => { successDiv.style.display = 'none'; }, 3000);\n" +
                "                    refreshStatus();\n" +
                "                } else {\n" +
                "                    errorDiv.textContent = data.error || 'Failed to update port';\n" +
                "                    errorDiv.style.display = 'block';\n" +
                "                    showResult(data.error || 'Failed to update port', false);\n" +
                "                }\n" +
                "            })\n" +
                "            .catch(e => {\n" +
                "                errorDiv.textContent = 'Error: ' + e.message;\n" +
                "                errorDiv.style.display = 'block';\n" +
                "                showResult('Error: ' + e.message, false);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function saveAndSwitchPort() {\n" +
                "            const port = document.getElementById('target-port-input').value;\n" +
                "            const successDiv = document.getElementById('port-success');\n" +
                "            const errorDiv = document.getElementById('port-error');\n" +
                "            \n" +
                "            successDiv.style.display = 'none';\n" +
                "            errorDiv.style.display = 'none';\n" +
                "            showActivity('Saving port and switching...');\n" +
                "            \n" +
                "            fetch('/api/port', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'port=' + port\n" +
                "            })\n" +
                "            .then(r => r.json())\n" +
                "            .then(data => {\n" +
                "                if (data.success) {\n" +
                "                    return fetch('/api/switch');\n" +
                "                } else {\n" +
                "                    throw new Error(data.error || 'Failed to update port');\n" +
                "                }\n" +
                "            })\n" +
                "            .then(r => r.json())\n" +
                "            .then(data => {\n" +
                "                successDiv.textContent = 'Port saved and switch initiated! Check logs below.';\n" +
                "                successDiv.style.display = 'block';\n" +
                "                showResult('Port saved and switch initiated successfully!', true);\n" +
                "                setTimeout(() => { successDiv.style.display = 'none'; }, 4000);\n" +
                "                refreshStatus();\n" +
                "                refreshLogs();\n" +
                "            })\n" +
                "            .catch(e => {\n" +
                "                errorDiv.textContent = 'Error: ' + e.message;\n" +
                "                errorDiv.style.display = 'block';\n" +
                "                showResult('Error: ' + e.message, false);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function toggleWebServer(enable) {\n" +
                "            if (!enable && !confirm('Are you sure you want to disable the web server? Once disabled, you will need to restart the app or use another method to access configuration settings.')) {\n" +
                "                return;\n" +
                "            }\n" +
                "            \n" +
                "            const successDiv = document.getElementById('web-server-success');\n" +
                "            const errorDiv = document.getElementById('web-server-error');\n" +
                "            \n" +
                "            successDiv.style.display = 'none';\n" +
                "            errorDiv.style.display = 'none';\n" +
                "            showActivity('Updating web server setting...');\n" +
                "            \n" +
                "            fetch('/api/webserver', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'enabled=' + enable\n" +
                "            })\n" +
                "            .then(r => r.json())\n" +
                "            .then(data => {\n" +
                "                if (data.success) {\n" +
                "                    successDiv.textContent = data.message;\n" +
                "                    successDiv.style.display = 'block';\n" +
                "                    showResult(data.message, true);\n" +
                "                } else {\n" +
                "                    errorDiv.textContent = data.error || 'Failed to update setting';\n" +
                "                    errorDiv.style.display = 'block';\n" +
                "                    showResult(data.error || 'Failed', false);\n" +
                "                }\n" +
                "            })\n" +
                "            .catch(e => {\n" +
                "                successDiv.textContent = 'Web server setting updated. If disabled, connection will close shortly.';\n" +
                "                successDiv.style.display = 'block';\n" +
                "                showResult('Web server setting updated', true);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function refreshLogs() {\n" +
                "            fetch('/api/logs')\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    const container = document.getElementById('logs-container');\n" +
                "                    const wasScrolledToBottom = container.scrollHeight - container.clientHeight <= container.scrollTop + 1;\n" +
                "                    container.textContent = data.logs || 'No logs available';\n" +
                "                    if (wasScrolledToBottom) {\n" +
                "                        container.scrollTop = container.scrollHeight;\n" +
                "                    }\n" +
                "                })\n" +
                "                .catch(e => {\n" +
                "                    document.getElementById('logs-container').textContent = 'Error loading logs: ' + e.message;\n" +
                "                });\n" +
                "        }\n" +
                "        \n" +
                "        function copyLogs() {\n" +
                "            const logs = document.getElementById('logs-container').textContent;\n" +
                "            \n" +
                "            if (navigator.clipboard && navigator.clipboard.writeText) {\n" +
                "                navigator.clipboard.writeText(logs).then(() => {\n" +
                "                    const btn = event.target;\n" +
                "                    const originalText = btn.textContent;\n" +
                "                    btn.textContent = '✓ Copied!';\n" +
                "                    setTimeout(() => { btn.textContent = originalText; }, 2000);\n" +
                "                }).catch(e => {\n" +
                "                    copyLogsViaTextarea(logs, event.target);\n" +
                "                });\n" +
                "            } else {\n" +
                "                copyLogsViaTextarea(logs, event.target);\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function copyLogsViaTextarea(text, btn) {\n" +
                "            const textarea = document.createElement('textarea');\n" +
                "            textarea.value = text;\n" +
                "            document.body.appendChild(textarea);\n" +
                "            textarea.select();\n" +
                "            document.execCommand('copy');\n" +
                "            document.body.removeChild(textarea);\n" +
                "            \n" +
                "            const originalText = btn.textContent;\n" +
                "            btn.textContent = '✓ Copied!';\n" +
                "            setTimeout(() => { btn.textContent = originalText; }, 2000);\n" +
                "        }\n" +
                "        \n" +
                "        function resetPairing() {\n" +
                "            const successDiv = document.getElementById('reset-success');\n" +
                "            const errorDiv = document.getElementById('reset-error');\n" +
                "            \n" +
                "            if (confirm('Are you sure you want to reset pairing? You will need to pair again.')) {\n" +
                "                showActivity('Resetting pairing credentials...');\n" +
                "                fetch('/api/reset', {\n" +
                "                    method: 'POST'\n" +
                "                })\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    if (data.success) {\n" +
                "                        successDiv.textContent = data.message;\n" +
                "                        successDiv.style.display = 'block';\n" +
                "                        errorDiv.style.display = 'none';\n" +
                "                        showResult(data.message, true);\n" +
                "                        setTimeout(() => {\n" +
                "                            successDiv.style.display = 'none';\n" +
                "                            refreshStatus();\n" +
                "                        }, 3000);\n" +
                "                    } else {\n" +
                "                        errorDiv.textContent = 'Reset failed: ' + (data.error || 'Unknown error');\n" +
                "                        errorDiv.style.display = 'block';\n" +
                "                        successDiv.style.display = 'none';\n" +
                "                        showResult('Reset failed', false);\n" +
                "                    }\n" +
                "                })\n" +
                "                .catch(e => {\n" +
                "                    errorDiv.textContent = 'Error: ' + e.message;\n" +
                "                    errorDiv.style.display = 'block';\n" +
                "                    successDiv.style.display = 'none';\n" +
                "                    showResult('Error: ' + e.message, false);\n" +
                "                });\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function pairDevice() {\n" +
                "            const port = document.getElementById('pair-port').value;\n" +
                "            const code = document.getElementById('pair-code').value;\n" +
                "            const successDiv = document.getElementById('pair-success');\n" +
                "            const errorDiv = document.getElementById('pair-error');\n" +
                "            \n" +
                "            successDiv.style.display = 'none';\n" +
                "            errorDiv.style.display = 'none';\n" +
                "            showActivity('Pairing device and requesting self-grant permissions...');\n" +
                "            \n" +
                "            fetch('/api/pair', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'port=' + port + '&code=' + code\n" +
                "            })\n" +
                "            .then(r => r.json())\n" +
                "            .then(data => {\n" +
                "                if (data.success) {\n" +
                "                    successDiv.textContent = data.message;\n" +
                "                    successDiv.style.display = 'block';\n" +
                "                    showResult(data.message, true);\n" +
                "                    setTimeout(refreshStatus, 2000);\n" +
                "                } else {\n" +
                "                    errorDiv.textContent = data.error || 'Pairing failed';\n" +
                "                    errorDiv.style.display = 'block';\n" +
                "                    showResult(data.error || 'Pairing failed', false);\n" +
                "                }\n" +
                "            })\n" +
                "            .catch(e => {\n" +
                "                errorDiv.textContent = 'Error: ' + e.message;\n" +
                "                errorDiv.style.display = 'block';\n" +
                "                showResult('Error: ' + e.message, false);\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function switchPort() {\n" +
                "            const infoDiv = document.getElementById('switch-info');\n" +
                "            showActivity('Switching ADB target port...');\n" +
                "            \n" +
                "            fetch('/api/switch')\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    infoDiv.textContent = data.message;\n" +
                "                    infoDiv.style.display = 'block';\n" +
                "                    showResult('Port switch initiated successfully!', true);\n" +
                "                    setTimeout(() => {\n" +
                "                        infoDiv.style.display = 'none';\n" +
                "                        refreshStatus();\n" +
                "                        refreshLogs();\n" +
                "                    }, 5000);\n" +
                "                })\n" +
                "                .catch(e => {\n" +
                "                    showResult('Switch failed: ' + e.message, false);\n" +
                "                });\n" +
                "        }\n" +
                "        \n" +
                "        function runTest() {\n" +
                "            const infoDiv = document.getElementById('test-info');\n" +
                "            showActivity('Running boot configuration test...');\n" +
                "            \n" +
                "            fetch('/api/test')\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    infoDiv.textContent = data.message;\n" +
                "                    infoDiv.style.display = 'block';\n" +
                "                    showResult('Test sequence started successfully!', true);\n" +
                "                    setTimeout(() => {\n" +
                "                        infoDiv.style.display = 'none';\n" +
                "                        refreshLogs();\n" +
                "                    }, 3000);\n" +
                "                })\n" +
                "                .catch(e => {\n" +
                "                    showResult('Test failed: ' + e.message, false);\n" +
                "                });\n" +
                "        }\n" +
                "        \n" +
                "        document.addEventListener('DOMContentLoaded', function() {\n" +
                "            const logsContainer = document.getElementById('logs-container');\n" +
                "            const pausedIndicator = document.getElementById('paused-indicator');\n" +
                "            \n" +
                "            logsContainer.addEventListener('mousedown', function() {\n" +
                "                autoRefreshPaused = true;\n" +
                "                pausedIndicator.style.display = 'inline';\n" +
                "                clearInterval(logsRefreshInterval);\n" +
                "            });\n" +
                "            \n" +
                "            document.addEventListener('mouseup', function() {\n" +
                "                setTimeout(() => {\n" +
                "                    if (window.getSelection().toString().length === 0) {\n" +
                "                        autoRefreshPaused = false;\n" +
                "                        pausedIndicator.style.display = 'none';\n" +
                "                        startLogsAutoRefresh();\n" +
                "                    }\n" +
                "                }, 100);\n" +
                "            });\n" +
                "        });\n" +
                "        \n" +
                "        function startLogsAutoRefresh() {\n" +
                "            if (!autoRefreshPaused) {\n" +
                "                logsRefreshInterval = setInterval(() => {\n" +
                "                    if (!autoRefreshPaused) {\n" +
                "                        refreshLogs();\n" +
                "                    }\n" +
                "                }, 3000);\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        refreshStatus();\n" +
                "        refreshLogs();\n" +
                "        \n" +
                "        setInterval(refreshStatus, 5000);\n" +
                "        startLogsAutoRefresh();\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}
