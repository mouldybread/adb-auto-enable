package com.tpn.adbautoenable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ADBAutoEnable";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        // Any of the filtered actions is a chance to run. Arm the persisted job
        // first: only a BOOT_COMPLETED receiver is exempt from the background
        // foreground-service start restriction, so the service start below can
        // be refused, while the job's settings write cannot.
        BootJobService.schedule(context, 0L);

        try {
            startServiceNow(context);
            Log.i(TAG, "Started ADB configuration service for " + action);
        } catch (Exception e) {
            Log.w(TAG, "Could not start service for " + action + ", leaving it to the job: " + e);
        }
    }

    private void startServiceNow(Context context) {
        Intent serviceIntent = new Intent(context, AdbConfigService.class);
        serviceIntent.putExtra("boot_config", true);
        context.startForegroundService(serviceIntent);
    }
}
