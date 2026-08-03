package com.amazon.device.kmlsynclib.keymap.sync;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/* loaded from: classes.dex */
public class KeyMapBroadcaster {
    private static final String BROADCAST_ACTION_REQUEST = "com.amazon.device.bluetoothkeymap.action.KEYMAP_SYNC_REQUEST";
    private static final String BROADCAST_ACTION_STATUS = "com.amazon.device.bluetoothkeymap.action.KEYMAP_SYNC_STATUS";
    public static final String NOTIFY_UPDATE_FAILED = "update_failed";
    public static final String NOTIFY_UPDATE_STARTED = "update_started";
    public static final String NOTIFY_UPDATE_SUCCESS = "update_success";
    public static final String REQUEST_FULL_SYNC = "full_sync";
    public static final String REQUEST_STATE_SWITCH = "state_switch";
    private static final String TAG = "KeyMapBroadcaster";
    private final Context mContext;
    private boolean enableRequest = true;
    private boolean enableNotify = true;

    public KeyMapBroadcaster(Context context) {
        this.mContext = context;
    }

    public void notifyStatus(String address, String status) {
        int progress;
        progress = -1;
        switch (status) {
            case "update_started":
                progress = 0;
                break;
            case "update_success":
                progress = 100;
                break;
        }
        notifyStatus(address, status, "", progress);
    }

    public void notifyStatus(String address, String status, String reason, int progress) {
        Log.i(TAG, String.format("Status:%s Reason:%s AlogBtKeymap", status, reason));
        Intent intent = new Intent(BROADCAST_ACTION_STATUS);
        intent.putExtra("address", address);
        intent.putExtra("status", status);
        intent.putExtra("reason", reason);
        intent.putExtra("progress", progress);
        if (this.enableNotify) {
            this.mContext.sendBroadcast(intent, "com.amazon.tv.devicecontrol.READ");
        } else {
            Log.i(TAG, intent.toString());
        }
    }

    public void requestAction(String address, String request) {
        Log.i(TAG, String.format("Request:%s", request));
        Intent intent = new Intent(BROADCAST_ACTION_REQUEST);
        intent.putExtra("address", address);
        intent.putExtra("request", request);
        if (this.enableRequest) {
            this.mContext.sendBroadcast(intent, "com.amazon.tv.devicecontrol.READ");
        } else {
            Log.i(TAG, intent.toString());
        }
    }
}
