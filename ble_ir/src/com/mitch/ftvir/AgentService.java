package com.mitch.ftvir;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;
import org.json.JSONObject;

/**
 * ADB-friendly RPC agent: IR blast + Amazon IDC TV catalog (via MAP token).
 *
 * am startservice -n com.mitch.ftvir/.AgentService -a com.mitch.ftvir.RPC \
 *   --es op brands|profiles|profile|blast|remotes|ping \
 *   [--es region na] [--es brand_id 123] [--es ids 4696] \
 *   [--es address MAC] [--es json_file PATH] [--es token OVERRIDE]
 *
 * Results: /data/local/tmp/ftvir_rpc_out.json + ftvir_rpc_status.json
 */
public class AgentService extends IntentService {
    private static final String TAG = "FtvIrAgent";

    public AgentService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        RpcIO.init(this);
        if (intent == null) {
            RpcIO.fail("null intent");
            return;
        }
        String op = intent.getStringExtra("op");
        if (op == null || op.isEmpty()) {
            // Back-compat: BLAST action without op
            if ("com.mitch.ftvir.BLAST".equals(intent.getAction())) {
                op = "blast";
            } else {
                RpcIO.fail("missing op");
                return;
            }
        }
        try {
            if ("ping".equals(op)) {
                JSONObject o = new JSONObject();
                o.put("pong", true);
                o.put("version", 1);
                RpcIO.ok(o);
                return;
            }
            if ("remotes".equals(op)) {
                RpcIO.ok(RemoteDiscovery.listRemotes());
                return;
            }
            if ("blast".equals(op)) {
                handleBlast(intent);
                return;
            }
            if ("tv_state".equals(op) || "screen_state".equals(op)) {
                RpcIO.ok(DeviceControlCatalog.tvPower(this));
                return;
            }
            if ("brands".equals(op) || "profiles".equals(op) || "profile".equals(op)) {
                handleIdc(op, intent);
                return;
            }
            RpcIO.fail("unknown op: " + op);
        } catch (Throwable t) {
            Log.e(TAG, "RPC failed", t);
            RpcIO.fail(t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
    }

    private void handleBlast(Intent intent) throws Exception {
        String address = intent.getStringExtra("address");
        String json = intent.getStringExtra("json");
        String jsonFile = intent.getStringExtra("json_file");
        if ((json == null || json.isEmpty()) && jsonFile != null) {
            json = RpcIO.readFile(jsonFile);
        }
        if (address == null || address.isEmpty() || json == null || json.isEmpty()) {
            throw new IllegalArgumentException("blast needs address + json/json_file");
        }
        IrBlaster.blast(this, address, json);
        JSONObject o = new JSONObject();
        o.put("blasted", true);
        o.put("address", address);
        RpcIO.ok(o);
    }

    private void handleIdc(String op, Intent intent) throws Exception {
        // Prefer DeviceControl AIDL (has MAP). Fall back to direct IDC + MAP token.
        try {
            if ("brands".equals(op)) {
                RpcIO.ok(DeviceControlCatalog.brands(this));
                return;
            }
            if ("profiles".equals(op)) {
                String brandId = intent.getStringExtra("brand_id");
                if (brandId == null || brandId.isEmpty()) {
                    throw new IllegalArgumentException("profiles needs brand_id");
                }
                RpcIO.ok(DeviceControlCatalog.profilesForBrand(this, Long.parseLong(brandId)));
                return;
            }
            String ids = intent.getStringExtra("ids");
            if (ids == null || ids.isEmpty()) {
                throw new IllegalArgumentException("profile needs ids (codeset ids, comma-separated)");
            }
            String[] parts = ids.split(",");
            long[] arr = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                arr[i] = Long.parseLong(parts[i].trim());
            }
            RpcIO.ok(DeviceControlCatalog.profilesByIds(this, arr));
            return;
        } catch (Throwable aidlErr) {
            Log.w(TAG, "DeviceControl AIDL catalog failed, trying IDC HTTP: " + aidlErr.getMessage());
        }

        String region = intent.getStringExtra("region");
        if (region == null || region.isEmpty()) region = "na";
        String token = intent.getStringExtra("token");
        if (token == null || token.isEmpty()) {
            try {
                token = RpcIO.readFile("/data/local/tmp/ftvir_token").trim();
            } catch (Exception ignored) {
                token = null;
            }
        }
        if (token == null || token.isEmpty()) {
            token = MapTokenHelper.getAccessToken(this);
        }
        IdcClient client = new IdcClient(region, token);
        if ("brands".equals(op)) {
            RpcIO.ok(client.getBrands());
            return;
        }
        if ("profiles".equals(op)) {
            String brandId = intent.getStringExtra("brand_id");
            if (brandId == null || brandId.isEmpty()) {
                throw new IllegalArgumentException("profiles needs brand_id");
            }
            RpcIO.ok(client.getProfilesForBrand(Long.parseLong(brandId)));
            return;
        }
        String ids = intent.getStringExtra("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("profile needs ids (codeset ids, comma-separated)");
        }
        RpcIO.ok(client.getProfilesByIds(ids));
    }
}
