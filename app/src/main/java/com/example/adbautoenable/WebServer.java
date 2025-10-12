package com.example.adbautoenable;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.Log;
import fi.iki.elonen.NanoHTTPD;
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
        } else {
            return newFixedLengthResponse(getHTML());
        }
    }

    private Response handlePairing(IHTTPSession session) {
        try {
            // Parse POST body
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

            // Use localhost for pairing
            boolean success = adbHelper.pair("127.0.0.1", port, code);

            if (success) {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean("is_paired", true).apply();
                Log.i(TAG, "Web API: Pairing successful");
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"success\":true,\"message\":\"Pairing successful!\"}");
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
                "{\"success\":true,\"message\":\"Test started\"}");
    }

    private Response handleSwitch() {
        new Thread(() -> {
            try {
                Log.i(TAG, "Web API: Discovering ADB port...");
                String deviceIP = getDeviceIP();

                // Try mDNS discovery
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
                "{\"success\":true,\"message\":\"Switch to port 5555 started. Check logs for status.\"}");
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

                            if (host.startsWith("127.") || host.equals("::1") ||
                                    host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
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

            // Convert int to byte array in little-endian order
            byte[] ipBytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(ipAddress)
                    .array();

            // Convert byte array to IP address string
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
                "    <meta charset='UTF-8'>\n" +
                "    <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
                "    <title>ADB Auto-Enable Configuration</title>\n" +
                "    <style>\n" +
                "        body { font-family: -apple-system, system-ui, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; background: #f5f5f5; }\n" +
                "        .card { background: white; border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
                "        h1 { color: #333; margin: 0 0 10px 0; }\n" +
                "        h2 { color: #666; font-size: 18px; margin-top: 0; }\n" +
                "        .status-item { margin: 10px 0; padding: 10px; background: #f9f9f9; border-radius: 6px; }\n" +
                "        input { width: 100%; padding: 10px; margin: 5px 0; border: 1px solid #ddd; border-radius: 6px; box-sizing: border-box; }\n" +
                "        button { background: #007AFF; color: white; border: none; padding: 12px 24px; border-radius: 8px; cursor: pointer; font-size: 16px; margin: 5px 0; width: 100%; }\n" +
                "        button:hover { background: #0051D5; }\n" +
                "        .success { color: #34C759; font-weight: bold; }\n" +
                "        .error { color: #FF3B30; font-weight: bold; }\n" +
                "        .info { background: #E3F2FD; padding: 15px; border-radius: 8px; margin: 10px 0; border-left: 4px solid #2196F3; }\n" +
                "        code { background: #f5f5f5; padding: 2px 6px; border-radius: 4px; font-family: monospace; }\n" +
                "        ul { margin: 10px 0; padding-left: 20px; }\n" +
                "        li { margin: 8px 0; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class='card'>\n" +
                "        <h1>🔧 ADB Auto-Enable Configuration</h1>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class='card'>\n" +
                "        <h2>📊 System Status</h2>\n" +
                "        <div id='status-display'>\n" +
                "            <div class='status-item'>Permission: <span id='permission-status'>Loading...</span></div>\n" +
                "            <div class='status-item'>Pairing Status: <span id='pairing-status'>Loading...</span></div>\n" +
                "            <div class='status-item'>ADB Port 5555: <span id='adb5555-status'>Loading...</span></div>\n" +
                "            <div class='status-item'>Device IP: " + deviceIP + "</div>\n" +
                "            <div class='status-item'>Last Boot Status: <span id='boot-status'>Loading...</span></div>\n" +
                "            <div class='status-item'>Last Port: <span id='last-port'>Loading...</span></div>\n" +
                "        </div>\n" +
                "        <button onclick='refreshStatus()'>🔄 Refresh Status</button>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class='card'>\n" +
                "        <h2>🔐 Initial Pairing (One-Time Setup)</h2>\n" +
                "        <div class='info'>\n" +
                "            <strong>Step 1:</strong> On your Android device, go to:<br>\n" +
                "            Settings → Developer Options → Wireless Debugging<br>\n" +
                "            Tap \"Pair device with pairing code\"<br><br>\n" +
                "            <strong>Step 2:</strong> Copy the pairing code and port shown and enter them below:\n" +
                "        </div>\n" +
                "        <input type='text' id='pair-code' placeholder='Pairing Code (e.g., 123456)'>\n" +
                "        <input type='text' id='pair-port' placeholder='Pairing Port (e.g., 37831)'>\n" +
                "        <button onclick='pair()'>🔗 Pair Device</button>\n" +
                "        <div id='pair-result'></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class='card'>\n" +
                "        <h2>🔄 Switch to Port 5555</h2>\n" +
                "        <div class='info'>\n" +
                "            After pairing and enabling wireless debugging, switch ADB to port 5555:\n" +
                "        </div>\n" +
                "        <button onclick='switchPort()'>🔀 Switch to Port 5555 Now</button>\n" +
                "        <div id='switch-result'></div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class='card'>\n" +
                "        <h2>🧪 Testing</h2>\n" +
                "        <div class='info'>\n" +
                "            Test the full boot configuration sequence:\n" +
                "        </div>\n" +
                "        <button onclick='test()'>▶️ Run Test Now</button>\n" +
                "        <div class='info'>\n" +
                "            View logs: <code>adb logcat -s ADBAutoEnable</code>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class='card'>\n" +
                "        <h2>💻 Grant Permission (Required)</h2>\n" +
                "        <div class='info'>\n" +
                "            Connect from your computer and run:<br>\n" +
                "            <code>adb shell pm grant com.example.adbautoenable android.permission.WRITE_SECURE_SETTINGS</code>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class='card'>\n" +
                "        <h2>🎉 Connect from PC</h2>\n" +
                "        <div class='info'>\n" +
                "            Once port 5555 shows as available, connect from your computer:<br>\n" +
                "            <code>adb connect " + deviceIP + ":5555</code>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    \n" +
                "    <div class='card'>\n" +
                "        <h2>ℹ️ How It Works</h2>\n" +
                "        <ul>\n" +
                "            <li><strong>One-time pairing:</strong> App pairs with itself, storing authentication keys</li>\n" +
                "            <li><strong>On every boot:</strong> App enables wireless debugging (random port)</li>\n" +
                "            <li><strong>Auto-discovery:</strong> App finds the ADB port via mDNS or scanning</li>\n" +
                "            <li><strong>Local connection:</strong> App connects to local ADB daemon using stored keys</li>\n" +
                "            <li><strong>Port switch:</strong> App sends tcpip:5555 command to ADB daemon</li>\n" +
                "            <li><strong>Result:</strong> Fully autonomous - no external device needed!</li>\n" +
                "        </ul>\n" +
                "    </div>\n" +
                "    \n" +
                "    <script>\n" +
                "        function refreshStatus() {\n" +
                "            fetch('/api/status')\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    document.getElementById('permission-status').innerHTML = data.hasPermission ? '<span class=\"success\">✓ Granted</span>' : '<span class=\"error\">✗ Not Granted</span>';\n" +
                "                    document.getElementById('pairing-status').innerHTML = data.isPaired ? '<span class=\"success\">✓ Paired</span>' : '<span class=\"error\">✗ Not Paired</span>';\n" +
                "                    document.getElementById('adb5555-status').innerHTML = data.adb5555Available ? '<span class=\"success\">✓ Available</span>' : '<span class=\"error\">✗ Not Available</span>';\n" +
                "                    document.getElementById('boot-status').textContent = data.lastStatus;\n" +
                "                    document.getElementById('last-port').textContent = data.lastPort === -1 ? 'Never run' : data.lastPort;\n" +
                "                })\n" +
                "                .catch(e => console.error('Status error:', e));\n" +
                "        }\n" +
                "        \n" +
                "        function pair() {\n" +
                "            const port = document.getElementById('pair-port').value;\n" +
                "            const code = document.getElementById('pair-code').value;\n" +
                "            \n" +
                "            fetch('/api/pair', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'port=' + encodeURIComponent(port) + '&code=' + encodeURIComponent(code)\n" +
                "            })\n" +
                "            .then(r => r.json())\n" +
                "            .then(data => {\n" +
                "                const result = document.getElementById('pair-result');\n" +
                "                if (data.success) {\n" +
                "                    result.innerHTML = '<div class=\"info success\">✓ ' + data.message + '</div>';\n" +
                "                    refreshStatus();\n" +
                "                } else {\n" +
                "                    result.innerHTML = '<div class=\"info error\">✗ ' + (data.error || 'Failed') + '</div>';\n" +
                "                }\n" +
                "            })\n" +
                "            .catch(e => {\n" +
                "                document.getElementById('pair-result').innerHTML = '<div class=\"info error\">✗ Error: ' + e + '</div>';\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "        function switchPort() {\n" +
                "            fetch('/api/switch')\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    const result = document.getElementById('switch-result');\n" +
                "                    result.innerHTML = '<div class=\"info success\">✓ ' + data.message + '</div>';\n" +
                "                    setTimeout(refreshStatus, 5000);\n" +
                "                })\n" +
                "                .catch(e => {\n" +
                "                    document.getElementById('switch-result').innerHTML = '<div class=\"info error\">✗ Error: ' + e + '</div>';\n" +
                "                });\n" +
                "        }\n" +
                "        \n" +
                "        function test() {\n" +
                "            fetch('/api/test')\n" +
                "                .then(r => r.json())\n" +
                "                .then(data => {\n" +
                "                    alert(data.message + '\\n\\nCheck logs with: adb logcat -s ADBAutoEnable');\n" +
                "                    setTimeout(refreshStatus, 10000);\n" +
                "                })\n" +
                "                .catch(e => alert('Error: ' + e));\n" +
                "        }\n" +
                "        \n" +
                "        refreshStatus();\n" +
                "        setInterval(refreshStatus, 5000); // Auto-refresh every 5 seconds\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}
