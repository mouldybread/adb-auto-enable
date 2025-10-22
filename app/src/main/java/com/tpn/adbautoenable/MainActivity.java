package com.tpn.adbautoenable;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.net.wifi.WifiManager;
import android.content.Context;
import android.text.format.Formatter;
import android.util.Log;

import java.io.IOException;

public class MainActivity extends Activity {
    private static final String TAG = "ADBAutoEnable";
    private static final int WEB_PORT = 8080;
    private WebServer webServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView titleText = new TextView(this);
        titleText.setText("ADB Auto-Enable");
        titleText.setTextSize(24);

        TextView statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setText("\nWeb interface running on:\n\n");

        TextView urlText = new TextView(this);
        urlText.setTextSize(18);
        urlText.setTextColor(0xFF2196F3);
        urlText.setText("http://" + getLocalIpAddress() + ":" + WEB_PORT);

        TextView instructionText = new TextView(this);
        instructionText.setText("\n\nOpen this URL in your browser to configure the app.");

        layout.addView(titleText);
        layout.addView(statusText);
        layout.addView(urlText);
        layout.addView(instructionText);

        setContentView(layout);

        // Start web server
        startWebServer();
    }

    private void startWebServer() {
        try {
            webServer = new WebServer(this, WEB_PORT);
            webServer.start();
            Log.i(TAG, "Web server started on port " + WEB_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start web server", e);
        }
    }

    private String getLocalIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        return Formatter.formatIpAddress(ipAddress);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webServer != null) {
            webServer.stop();
        }
    }
}
