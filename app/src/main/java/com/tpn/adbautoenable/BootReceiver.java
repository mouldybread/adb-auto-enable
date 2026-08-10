package com.tpn.adbautoenable;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ADBAutoEnable";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {

            Log.i(TAG, "Boot event detected, scheduling ADB configuration service...");

            if (Build.VERSION.SDK_INT >= 34) {
                scheduleServiceStart(context);
            } else {
                startServiceNow(context);
            }
        } else {
            Log.i(TAG, "Ignoring broadcast: " + action);
        }
    }

    private void startServiceNow(Context context) {
        Intent serviceIntent = new Intent(context, AdbConfigService.class);
        serviceIntent.putExtra("boot_config", true);
        context.startForegroundService(serviceIntent);
    } // <--- Added missing closing brace here

    private void scheduleServiceStart(Context context) {
        Intent serviceIntent = new Intent(context, AdbConfigService.class);
        serviceIntent.putExtra("boot_config", true);

        PendingIntent pendingIntent = PendingIntent.getForegroundService(
                context,
                0,
                serviceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            long triggerTime = SystemClock.elapsedRealtime() + 30000;
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent);
            Log.i(TAG, "Scheduled service start in 30 seconds");
        }
    }
}