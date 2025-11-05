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
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebServer extends NanoHTTPD {

    private static final String TAG = "ADBAutoEnable";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";

    private final Context context;
    private final AdbHelper adbHelper;

    public WebServer(Context context, int port) {
        super(port);
        this.context = context;
        this.adbHelper = new AdbHelper(context);
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
        } else if (uri.equals("/api/logs")) {
            return handleLogs();
        } else if (uri.equals("/api/reset") && method == Method.POST) {
            return handleReset();
        } else {
            return newFixedLengthResponse(getHTML());
        }
    }

    private Response handlePairing(IHTTPSession session) {
        try {
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
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean("is_paired", true).apply();
                Log.i(TAG, "Web API: Pairing successful");

                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        Log.i(TAG, "Attempting to self-grant WRITE_SECURE_SETTINGS permission");

                        int adbPort = discoverAdbPort();
                        if (adbPort == -1) {
                            Log.w(TAG, "Could not discover ADB port for self-grant, skipping");
                            return;
                        }

                        Log.i(TAG, "Found ADB on port " + adbPort + ", attempting self-grant");
                        boolean granted = adbHelper.selfGrantPermission("127.0.0.1", adbPort,
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
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String lastStatus = prefs.getString("last_status", "Not run yet");
        int lastPort = prefs.getInt("last_port", -1);
        boolean isPaired = prefs.getBoolean("is_paired", false);
        boolean hasPermission;
        boolean adb5555Available = checkPort5555();

        try {
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            hasPermission = true;
        } catch (SecurityException e) {
            hasPermission = false;
        }

        String json = String.format(Locale.US,
                "{\"lastStatus\":\"%s\",\"lastPort\":%d,\"isPaired\":%b,\"hasPermission\":%b,\"adb5555Available\":%b}",
                lastStatus, lastPort, isPaired, hasPermission, adb5555Available
        );

        return newFixedLengthResponse(Response.Status.OK, "application/json", json);
    }

    private Response handleLogs() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-s", "ADBAutoEnable:*"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder logs = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                logs.append(line).append("\n");
            }

            reader.close();
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
        }
    }

    private Response handleReset() {
        try {
            Log.i(TAG, "Web API: Resetting pairing status");
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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

    private boolean checkPort5555() {
        try {
            Socket socket = new Socket("127.0.0.1", 5555);
            socket.close();
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
                String deviceIP = getDeviceIP();
                int port = discoverAdbPort();

                if (port == -1) {
                    Log.e(TAG, "Web API: Could not find ADB port");
                    return;
                }

                Log.i(TAG, "Web API: Found ADB on port " + port + ", switching to 5555...");
                boolean success = adbHelper.switchToPort5555(deviceIP, port);

                if (success) {
                    Log.i(TAG, "Web API: Successfully switched to port 5555");
                } else {
                    Log.e(TAG, "Web API: Failed to switch to port 5555");
                }

            } catch (Exception e) {
                Log.e(TAG, "Web API: Switch error", e);
            }

        }).start();

        return newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"success\":true,\"message\":\"Port switch started. Check logs below for status.\"}");
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

        final NsdManager.DiscoveryListener[] discoveryListenerHolder = new NsdManager.DiscoveryListener[1];

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
                        if (serviceInfo.getHost() != null) {
                            InetAddress hostAddress = serviceInfo.getHost();
                            String host = hostAddress.getHostAddress();

                            if (host == null) {
                                Log.w(TAG, "Host address is null");
                                return;
                            }

                            int port = serviceInfo.getPort();
                            Log.i(TAG, "Host: " + host + ", Port: " + port);

                            if (host.equals(deviceIP)) {
                                Log.i(TAG, "Found matching device with IP: " + deviceIP);
                                discoveredPort[0] = port;
                                latch.countDown();

                                if (discoveryListenerHolder[0] != null) {
                                    try {
                                        nsdManager.stopServiceDiscovery(discoveryListenerHolder[0]);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error stopping discovery", e);
                                    }
                                }
                            } else {
                                Log.w(TAG, "Skipping device with IP " + host + " (looking for " + deviceIP + ")");
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

        discoveryListenerHolder[0] = discoveryListener;

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
        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

            if (wifiManager == null) {
                return "127.0.0.1";
            }

            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            byte[] ipBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ipAddress)
                    .array();

            InetAddress inetAddress = InetAddress.getByAddress(ipBytes);
            String result = inetAddress.getHostAddress();

            return (result != null) ? result : "127.0.0.1";

        } catch (Exception e) {
            Log.e(TAG, "Failed to get device IP", e);
            return "127.0.0.1";
        }
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
                "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                "        body {\n" +
                "            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;\n" +
                "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "            min-height: 100vh;\n" +
                "            padding: 20px;\n" +
                "        }\n" +
                "        .container {\n" +
                "            max-width: 900px;\n" +
                "            margin: 0 auto;\n" +
                "            background: white;\n" +
                "            border-radius: 12px;\n" +
                "            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);\n" +
                "            overflow: hidden;\n" +
                "        }\n" +
                "        .header {\n" +
                "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "            color: white;\n" +
                "            padding: 30px;\n" +
                "            text-align: center;\n" +
                "        }\n" +
                "        .header h1 {\n" +
                "            font-size: 28px;\n" +
                "            margin-bottom: 10px;\n" +
                "        }\n" +
                "        .content {\n" +
                "            padding: 30px;\n" +
                "        }\n" +
                "        .section {\n" +
                "            margin-bottom: 30px;\n" +
                "            padding-bottom: 30px;\n" +
                "            border-bottom: 1px solid #e0e0e0;\n" +
                "        }\n" +
                "        .section:last-child {\n" +
                "            border-bottom: none;\n" +
                "            margin-bottom: 0;\n" +
                "            padding-bottom: 0;\n" +
                "        }\n" +
                "        .section h2 {\n" +
                "            font-size: 20px;\n" +
                "            color: #333;\n" +
                "            margin-bottom: 15px;\n" +
                "        }\n" +
                "        .status-grid {\n" +
                "            display: grid;\n" +
                "            grid-template-columns: 1fr 1fr;\n" +
                "            gap: 15px;\n" +
                "            margin-bottom: 20px;\n" +
                "        }\n" +
                "        .status-item {\n" +
                "            background: #f5f5f5;\n" +
                "            padding: 15px;\n" +
                "            border-radius: 8px;\n" +
                "            border-left: 4px solid #667eea;\n" +
                "        }\n" +
                "        .status-label {\n" +
                "            font-size: 12px;\n" +
                "            color: #666;\n" +
                "            text-transform: uppercase;\n" +
                "            margin-bottom: 5px;\n" +
                "        }\n" +
                "        .status-value {\n" +
                "            font-size: 16px;\n" +
                "            color: #333;\n" +
                "            font-weight: 600;\n" +
                "        }\n" +
                "        .status-value.pending {\n" +
                "            color: #ff9800;\n" +
                "        }\n" +
                "        .status-value.success {\n" +
                "            color: #4caf50;\n" +
                "        }\n" +
                "        .status-value.error {\n" +
                "            color: #f44336;\n" +
                "        }\n" +
                "        .input-group {\n" +
                "            display: grid;\n" +
                "            grid-template-columns: 1fr 1fr;\n" +
                "            gap: 15px;\n" +
                "            margin-bottom: 15px;\n" +
                "        }\n" +
                "        input[type=\"text\"], input[type=\"number\"] {\n" +
                "            padding: 12px 15px;\n" +
                "            border: 1px solid #ddd;\n" +
                "            border-radius: 6px;\n" +
                "            font-size: 14px;\n" +
                "            font-family: monospace;\n" +
                "        }\n" +
                "        input[type=\"text\"]:focus, input[type=\"number\"]:focus {\n" +
                "            outline: none;\n" +
                "            border-color: #667eea;\n" +
                "            box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);\n" +
                "        }\n" +
                "        button {\n" +
                "            padding: 12px 24px;\n" +
                "            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "            color: white;\n" +
                "            border: none;\n" +
                "            border-radius: 6px;\n" +
                "            font-size: 14px;\n" +
                "            font-weight: 600;\n" +
                "            cursor: pointer;\n" +
                "            transition: all 0.3s ease;\n" +
                "        }\n" +
                "        button:hover {\n" +
                "            transform: translateY(-2px);\n" +
                "            box-shadow: 0 8px 20px rgba(102, 126, 234, 0.3);\n" +
                "        }\n" +
                "        button:active {\n" +
                "            transform: translateY(0);\n" +
                "        }\n" +
                "        .button-group {\n" +
                "            display: flex;\n" +
                "            gap: 10px;\n" +
                "            flex-wrap: wrap;\n" +
                "        }\n" +
                "        .info-text {\n" +
                "            background: #e3f2fd;\n" +
                "            color: #1976d2;\n" +
                "            padding: 12px 15px;\n" +
                "            border-radius: 6px;\n" +
                "            font-size: 13px;\n" +
                "            line-height: 1.6;\n" +
                "            margin: 15px 0;\n" +
                "        }\n" +
                "        .logs-container {\n" +
                "            background: #1e1e1e;\n" +
                "            color: #00ff00;\n" +
                "            padding: 15px;\n" +
                "            border-radius: 6px;\n" +
                "            font-family: 'Courier New', monospace;\n" +
                "            font-size: 12px;\n" +
                "            max-height: 400px;\n" +
                "            overflow-y: auto;\n" +
                "            line-height: 1.4;\n" +
                "            white-space: pre-wrap;\n" +
                "            word-wrap: break-word;\n" +
                "        }\n" +
                "        .paired-success {\n" +
                "            background: #c8e6c9;\n" +
                "            color: #2e7d32;\n" +
                "            padding: 15px;\n" +
                "            border-radius: 6px;\n" +
                "            margin: 15px 0;\n" +
                "            text-align: center;\n" +
                "            font-weight: 600;\n" +
                "        }\n" +
                "        .hidden { display: none; }\n" +
                "        @media (max-width: 600px) {\n" +
                "            .status-grid, .input-group { grid-template-columns: 1fr; }\n" +
                "            .header h1 { font-size: 22px; }\n" +
                "        }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <div class=\"header\">\n" +
                "            <h1>🔧 ADB Auto-Enable Configuration</h1>\n" +
                "        </div>\n" +
                "        <div class=\"content\">\n" +
                "\n" +
                "            <div class=\"section\">\n" +
                "                <h2>📊 System Status</h2>\n" +
                "                <div class=\"status-grid\">\n" +
                "                    <div class=\"status-item\">\n" +
                "                        <div class=\"status-label\">Permission</div>\n" +
                "                        <div id=\"permission-status\" class=\"status-value pending\">Loading...</div>\n" +
                "                    </div>\n" +
                "                    <div class=\"status-item\">\n" +
                "                        <div class=\"status-label\">Pairing Status</div>\n" +
                "                        <div id=\"pairing-status\" class=\"status-value pending\">Loading...</div>\n" +
                "                    </div>\n" +
                "                    <div class=\"status-item\">\n" +
                "                        <div class=\"status-label\">ADB Port 5555</div>\n" +
                "                        <div id=\"adb5555-status\" class=\"status-value pending\">Loading...</div>\n" +
                "                    </div>\n" +
                "                    <div class=\"status-item\">\n" +
                "                        <div class=\"status-label\">Device IP</div>\n" +
                "                        <div id=\"device-ip\" class=\"status-value\">" + deviceIP + "</div>\n" +
                "                    </div>\n" +
                "                    <div class=\"status-item\">\n" +
                "                        <div class=\"status-label\">Last Boot Status</div>\n" +
                "                        <div id=\"last-status\" class=\"status-value\">Loading...</div>\n" +
                "                    </div>\n" +
                "                    <div class=\"status-item\">\n" +
                "                        <div class=\"status-label\">Last Port</div>\n" +
                "                        <div id=\"last-port\" class=\"status-value\">Loading...</div>\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "                <button onclick=\"refreshStatus()\">🔄 Refresh Status</button>\n" +
                "            </div>\n" +
                "\n" +
                "            <div class=\"section\">\n" +
                "                <h2>🔐 Initial Pairing (One-Time Setup)</h2>\n" +
                "                <div class=\"info-text\">\n" +
                "                    <strong>Step 1:</strong> On your Android device, go to:<br/>\n" +
                "                    Settings → Developer Options → Wireless Debugging<br/>\n" +
                "                    Tap \"Pair device with pairing code\"<br/>\n" +
                "                    <br/>\n" +
                "                    <strong>Step 2:</strong> Copy the pairing code and port shown and enter them below:\n" +
                "                </div>\n" +
                "                <div class=\"input-group\">\n" +
                "                    <input type=\"number\" id=\"pairing-port\" placeholder=\"Pairing Port (e.g., 32894)\" min=\"1\" max=\"65535\">\n" +
                "                    <input type=\"text\" id=\"pairing-code\" placeholder=\"Pairing Code (6 digits)\" maxlength=\"6\">\n" +
                "                </div>\n" +
                "                <button onclick=\"pairDevice()\">🔗 Pair Device</button>\n" +
                "                <p class=\"info-text\" style=\"margin-top: 10px; font-size: 12px;\">\n" +
                "                    After pairing, the app will attempt to automatically grant itself permissions. Check the status above to verify.\n" +
                "                </p>\n" +
                "                <div id=\"paired-section\" class=\"hidden\">\n" +
                "                    <div class=\"paired-success\">✅ Device Paired</div>\n" +
                "                    <div class=\"info-text\">Your device is successfully paired and ready to use!</div>\n" +
                "                    <button onclick=\"resetPairing()\">🔄 Reset Pairing</button>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "\n" +
                "            <div class=\"section\">\n" +
                "                <h2>🔄 Switch to Port 5555</h2>\n" +
                "                <p class=\"info-text\">\n" +
                "                    After pairing and enabling wireless debugging, switch ADB to port 5555:\n" +
                "                </p>\n" +
                "                <button onclick=\"switchPort()\">🔀 Switch to Port 5555 Now</button>\n" +
                "            </div>\n" +
                "\n" +
                "            <div class=\"section\">\n" +
                "                <h2>🧪 Testing</h2>\n" +
                "                <p class=\"info-text\">\n" +
                "                    Test the full boot configuration sequence:\n" +
                "                </p>\n" +
                "                <button onclick=\"runTest()\">▶️ Run Test Now</button>\n" +
                "            </div>\n" +
                "\n" +
                "            <div class=\"section\">\n" +
                "                <div style=\"display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px;\">\n" +
                "                    <h2 style=\"margin: 0;\">📋 Live Logs</h2>\n" +
                "                    <button onclick=\"copyLogs()\" style=\"padding: 8px 16px; font-size: 12px;\">📋 Copy to Clipboard</button>\n" +
                "                </div>\n" +
                "                <p style=\"color: #666; font-size: 12px; margin-bottom: 10px;\">Auto-refresh paused</p>\n" +
                "                <div id=\"logs-content\" class=\"logs-container\">\n" +
                "                    Loading logs...\n" +
                "                </div>\n" +
                "            </div>\n" +
                "\n" +
                "        </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        async function refreshStatus() {\n" +
                "            try {\n" +
                "                const response = await fetch('/api/status');\n" +
                "                const data = await response.json();\n" +
                "\n" +
                "                document.getElementById('permission-status').textContent = data.hasPermission ? '✓ Granted' : '✗ Not Granted';\n" +
                "                document.getElementById('permission-status').className = 'status-value ' + (data.hasPermission ? 'success' : 'error');\n" +
                "\n" +
                "                document.getElementById('pairing-status').textContent = data.isPaired ? '✓ Paired' : '✗ Not Paired';\n" +
                "                document.getElementById('pairing-status').className = 'status-value ' + (data.isPaired ? 'success' : 'error');\n" +
                "\n" +
                "                document.getElementById('adb5555-status').textContent = data.adb5555Available ? '✓ Available' : '✗ Not Available';\n" +
                "                document.getElementById('adb5555-status').className = 'status-value ' + (data.adb5555Available ? 'success' : 'error');\n" +
                "\n" +
                "                document.getElementById('last-status').textContent = data.lastStatus || 'Never run';\n" +
                "                document.getElementById('last-port').textContent = data.lastPort > 0 ? data.lastPort : 'N/A';\n" +
                "\n" +
                "                if (data.isPaired) {\n" +
                "                    document.getElementById('paired-section').classList.remove('hidden');\n" +
                "                } else {\n" +
                "                    document.getElementById('paired-section').classList.add('hidden');\n" +
                "                }\n" +
                "\n" +
                "                refreshLogs();\n" +
                "            } catch (error) {\n" +
                "                console.error('Error fetching status:', error);\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        async function refreshLogs() {\n" +
                "            try {\n" +
                "                const response = await fetch('/api/logs');\n" +
                "                const data = await response.json();\n" +
                "                document.getElementById('logs-content').textContent = data.logs || 'No logs available';\n" +
                "            } catch (error) {\n" +
                "                console.error('Error fetching logs:', error);\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        async function copyLogs() {\n" +
                "            const logsText = document.getElementById('logs-content').innerText;\n" +
                "            \n" +
                "            // Try modern Clipboard API first (secure contexts only)\n" +
                "            if (navigator.clipboard && navigator.clipboard.writeText) {\n" +
                "                try {\n" +
                "                    await navigator.clipboard.writeText(logsText);\n" +
                "                    alert('Logs copied to clipboard!');\n" +
                "                    return;\n" +
                "                } catch (err) {\n" +
                "                    console.log('Clipboard API failed, using fallback method');\n" +
                "                }\n" +
                "            }\n" +
                "            \n" +
                "            // Fallback: use textarea selection method (works on all HTTP contexts)\n" +
                "            const textarea = document.createElement('textarea');\n" +
                "            textarea.value = logsText;\n" +
                "            document.body.appendChild(textarea);\n" +
                "            textarea.select();\n" +
                "            document.execCommand('copy');\n" +
                "            document.body.removeChild(textarea);\n" +
                "            alert('Logs copied to clipboard!');\n" +
                "        }\n" +
                "\n" +
                "        async function pairDevice() {\n" +
                "            const port = document.getElementById('pairing-port').value;\n" +
                "            const code = document.getElementById('pairing-code').value;\n" +
                "\n" +
                "            if (!port || !code) {\n" +
                "                alert('Please enter both port and pairing code');\n" +
                "                return;\n" +
                "            }\n" +
                "\n" +
                "            try {\n" +
                "                const response = await fetch('/api/pair', {\n" +
                "                    method: 'POST',\n" +
                "                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },\n" +
                "                    body: 'port=' + port + '&code=' + code\n" +
                "                });\n" +
                "\n" +
                "                const data = await response.json();\n" +
                "\n" +
                "                if (response.ok) {\n" +
                "                    alert('Pairing successful! Attempting to self-grant permissions...');\n" +
                "                    setTimeout(refreshStatus, 2000);\n" +
                "                } else {\n" +
                "                    alert('Pairing failed: ' + data.error);\n" +
                "                }\n" +
                "            } catch (error) {\n" +
                "                alert('Error: ' + error.message);\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        async function resetPairing() {\n" +
                "            if (!confirm('Are you sure you want to reset the pairing? You will need to pair again.')) {\n" +
                "                return;\n" +
                "            }\n" +
                "\n" +
                "            try {\n" +
                "                const response = await fetch('/api/reset', { method: 'POST' });\n" +
                "                const data = await response.json();\n" +
                "                alert(data.message || 'Pairing reset successful');\n" +
                "                refreshStatus();\n" +
                "            } catch (error) {\n" +
                "                alert('Error: ' + error.message);\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        async function switchPort() {\n" +
                "            try {\n" +
                "                const response = await fetch('/api/switch');\n" +
                "                const data = await response.json();\n" +
                "                alert(data.message);\n" +
                "                setTimeout(refreshLogs, 1000);\n" +
                "            } catch (error) {\n" +
                "                alert('Error: ' + error.message);\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        async function runTest() {\n" +
                "            try {\n" +
                "                const response = await fetch('/api/test');\n" +
                "                const data = await response.json();\n" +
                "                alert(data.message);\n" +
                "                setTimeout(refreshLogs, 1000);\n" +
                "            } catch (error) {\n" +
                "                alert('Error: ' + error.message);\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        // Initial load\n" +
                "        window.addEventListener('load', () => {\n" +
                "            refreshStatus();\n" +
                "        });\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}
