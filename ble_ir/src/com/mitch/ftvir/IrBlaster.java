package com.mitch.ftvir;

import android.content.Context;
import android.util.Log;
import com.amazon.device.kmlsynclib.keymap.sync.BleKeyMapDeviceProxyV2;
import com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceError;
import com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy;
import com.amazon.device.kmlsynclib.keymap.table.KeyMapTable;
import com.amazon.device.kmlsynclib.keymap.table.KeyMapTableFactory;
import java.util.HashMap;
import org.json.JSONObject;

final class IrBlaster {
    private static final String TAG = "FtvIrBlast";

    private IrBlaster() {}

    static void blast(Context context, String address, String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.has("InstantFire")) {
            for (int i = 0; i < root.getJSONArray("InstantFire").length(); i++) {
                JSONObject cmd = root.getJSONArray("InstantFire").getJSONObject(i);
                if ("IR".equals(cmd.optString("CommandType")) && !cmd.has("PostDelay")) {
                    cmd.put("PostDelay", 120);
                }
            }
        }
        HashMap<String, Integer> scan = new HashMap<String, Integer>();
        KeyMapTable[] tables = KeyMapTableFactory.fromJson(scan, root);
        Log.i(TAG, "Blasting " + tables.length + " InstantFire table(s) to " + address);
        KeyMapDeviceProxy proxy = new BleKeyMapDeviceProxyV2(context, address);
        proxy.open();
        try {
            for (KeyMapTable table : tables) {
                KeyMapDeviceError err = proxy.blastCommand(table);
                Log.i(TAG, "blastCommand => " + err);
                if (err != KeyMapDeviceError.NO_ERROR) {
                    throw new IllegalStateException("Blast failed: " + err);
                }
            }
        } finally {
            proxy.close();
        }
    }
}
