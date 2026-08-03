package com.mitch.ftvir;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;
import org.json.JSONObject;

/** Back-compat InstantFire entrypoint (same as AgentService op=blast). */
public class BlastService extends IntentService {
    private static final String TAG = "FtvIrBlast";

    public BlastService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) return;
        try {
            String address = intent.getStringExtra("address");
            String json = intent.getStringExtra("json");
            String jsonFile = intent.getStringExtra("json_file");
            if ((json == null || json.isEmpty()) && jsonFile != null) {
                json = RpcIO.readFile(jsonFile);
            }
            if (address == null || address.isEmpty() || json == null || json.isEmpty()) {
                RpcIO.fail("blast needs address + json/json_file");
                return;
            }
            IrBlaster.blast(this, address, json);
            JSONObject o = new JSONObject();
            o.put("blasted", true);
            o.put("address", address);
            RpcIO.ok(o);
        } catch (Throwable t) {
            Log.e(TAG, "Blast error", t);
            RpcIO.fail(t.getMessage() != null ? t.getMessage() : "blast error");
        }
    }
}
