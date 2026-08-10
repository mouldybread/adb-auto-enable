package com.tpn.adbautoenable;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int WEB_PORT = 9093;
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_TARGET_PORT = "target_port";

    private EditText portEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Start the foreground service to keep web server alive
        Intent serviceIntent = new Intent(this, AdbConfigService.class);
        serviceIntent.putExtra("boot_config", false); // Not boot config, just start service
        startForegroundService(serviceIntent);

        // Create UI
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView titleText = new TextView(this);
        titleText.setText("ADB Auto-Enable");
        titleText.setTextSize(24);

        // Target ADB Port Controls
        TextView portLabel = new TextView(this);
        portLabel.setText("\nTarget ADB Port:");
        portLabel.setTextSize(16);

        portEditText = new EditText(this);
        portEditText.setInputType(InputType.TYPE_CLASS_NUMBER);
        portEditText.setText(String.valueOf(getTargetPort()));

        Button savePortButton = new Button(this);
        savePortButton.setText("Save Port");
        savePortButton.setOnClickListener(v -> saveTargetPort());

        TextView statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setText("\nWeb interface running on:\n");

        TextView urlText = new TextView(this);
        urlText.setTextSize(18);
        urlText.setTextColor(0xFF2196F3);
        urlText.setText("http://" + getLocalIpAddress() + ":" + WEB_PORT);

        TextView instructionText = new TextView(this);
        instructionText.setText("\n\nOpen this URL in your browser to configure the app.\n\nThe web server runs in the background even when you close this app.");

        layout.addView(titleText);
        layout.addView(portLabel);
        layout.addView(portEditText);
        layout.addView(savePortButton);
        layout.addView(statusText);
        layout.addView(urlText);
        layout.addView(instructionText);

        setContentView(layout);
    }

    private int getTargetPort() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_TARGET_PORT, 5555);
    }

    private void saveTargetPort() {
        String input = portEditText.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "Please enter a valid port number", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int port = Integer.parseInt(input);
            if (port < 1 || port > 65535) {
                Toast.makeText(this, "Port must be between 1 and 65535", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putInt(KEY_TARGET_PORT, port).apply();
            Toast.makeText(this, "Target port saved: " + port, Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
        }
    }

    private String getLocalIpAddress() {
        return NetworkUtils.getLiveDeviceIP(this);
    }
}