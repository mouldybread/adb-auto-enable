package com.tpn.adbautoenable;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Second start path, for devices whose OEM never starts this app's process for
 * BOOT_COMPLETED. A persisted job is restored and dispatched by JobScheduler
 * rather than by a manifest broadcast, so it does not depend on that path.
 *
 * The settings write happens in the job itself. Starting a foreground service
 * from the background can be refused, but writing adb_wifi_enabled needs only
 * WRITE_SECURE_SETTINGS, so wireless debugging comes up even when the service
 * start is denied.
 */
public class BootJobService extends JobService {
    private static final String TAG = "ADBAutoEnable";
    private static final int JOB_ID = 8451;
    private static final long RETRY_DELAY_MS = 5 * 60 * 1000L;

    static void schedule(Context context, long delayMs) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            Log.w(TAG, "job: no JobScheduler available");
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, BootJobService.class))
                .setPersisted(true)
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + RETRY_DELAY_MS)
                .build();
        int result = scheduler.schedule(job);
        Log.i(TAG, "job: scheduled in " + delayMs + "ms, result=" + result);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "job: fired");

        if (isPortOpen(5555)) {
            Log.i(TAG, "job: 5555 already listening, nothing to do");
            schedule(this, RETRY_DELAY_MS);
            return false;
        }

        try {
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
            Settings.Global.putLong(getContentResolver(), "adb_allowed_connection_time", 0L);
            Log.i(TAG, "job: wireless debugging enabled");
        } catch (Exception e) {
            Log.e(TAG, "job: could not write settings", e);
        }

        try {
            Intent serviceIntent = new Intent(this, AdbConfigService.class);
            serviceIntent.putExtra("boot_config", true);
            startForegroundService(serviceIntent);
            Log.i(TAG, "job: started AdbConfigService");
        } catch (Exception e) {
            Log.w(TAG, "job: could not start service, settings write stands on its own: " + e);
        }

        schedule(this, RETRY_DELAY_MS);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
