package org.belos.belmarket.receiver;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;

import org.belos.belmarket.Utils;
import org.belos.belmarket.net.WifiStateChangeService;

public class WifiStateChangeReceiver extends BroadcastReceiver {
    private static final String TAG = "WifiStateChangeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(intent.getAction())) {
            intent.setComponent(new ComponentName(context, WifiStateChangeService.class));
            context.startService(intent);
        } else {
            Utils.debugLog(TAG, "received unsupported Intent: " + intent);
        }
    }
}
