package com.mitch.ftvir;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;
import com.amazon.device.kmlsynclib.keymap.sync.BleKeyMapDeviceProxyV2;
import com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceError;
import com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy;
import com.amazon.device.kmlsynclib.keymap.table.KeyMapTable;
import com.amazon.device.kmlsynclib.keymap.table.KeyMapTableFactory;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.json.JSONObject;

/**
 * IR-only InstantFire via LuckyL remote GATT. Does NOT touch DeviceControl / HDMI-CEC.
 *
 * adb shell am startservice -n com.mitch.ftvir/.BlastService \
 *   -a com.mitch.ftvir.BLAST \
 *   --es address 14:91:38:B5:D6:69 \
 *   --es json_file /data/local/tmp/luckyl_instant_blast.json
 */
public class BlastService extends IntentService {
    private static final String TAG = "FtvIrBlast";

    public BlastService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        String address = intent.getStringExtra("address");
        String json = intent.getStringExtra("json");
        String jsonFile = intent.getStringExtra("json_file");
        try {
            if ((json == null || json.isEmpty()) && jsonFile != null) {
                FileInputStream in = new FileInputStream(jsonFile);
                byte[] buf = new byte[in.available()];
                int n = in.read(buf);
                in.close();
                json = new String(buf, 0, n, StandardCharsets.UTF_8);
            }
            if (address == null || address.isEmpty() || json == null || json.isEmpty()) {
                Log.e(TAG, "Need --es address and --es json or --es json_file");
                return;
            }
            // KeyMapActionIr requires PostDelay; inject default if missing
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
            KeyMapDeviceProxy proxy = new BleKeyMapDeviceProxyV2(this, address);
            proxy.open();
            try {
                for (KeyMapTable table : tables) {
                    // Skip LED-only tables if desired? Keep all — LED is harmless.
                    KeyMapDeviceError err = proxy.blastCommand(table);
                    Log.i(TAG, "blastCommand => " + err);
                    if (err != KeyMapDeviceError.NO_ERROR) {
                        Log.e(TAG, "Blast failed: " + err);
                        return;
                    }
                }
                Log.i(TAG, "IR blast OK");
            } finally {
                proxy.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Blast error", t);
        }
    }
}
