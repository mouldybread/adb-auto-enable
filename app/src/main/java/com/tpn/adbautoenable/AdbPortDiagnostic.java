package com.tpn.adbautoenable;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Diagnostics only: never used as a discovery result. Call from a worker thread. */
final class AdbPortDiagnostic {
    private static final String TAG = "ADBAutoEnable";
    private AdbPortDiagnostic() {}

    static void logTlsPort() {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", "service.adb.tls.port")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                Log.i(TAG, "Diagnostic service.adb.tls.port: read timed out (not used for discovery)");
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String value = reader.readLine();
                String outcome = "empty or inaccessible";
                if (process.exitValue() == 0 && value != null && value.matches("[0-9]{1,5}")) {
                    int port = Integer.parseInt(value);
                    if (port > 0 && port <= 65535) outcome = Integer.toString(port);
                }
                Log.i(TAG, "Diagnostic service.adb.tls.port: " + outcome + " (not used for discovery)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.i(TAG, "Diagnostic service.adb.tls.port: unavailable (not used for discovery)", e);
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }
}
