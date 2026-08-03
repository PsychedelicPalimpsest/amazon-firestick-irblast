package com.mitch.ftvir;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class RemoteDiscovery {
    private RemoteDiscovery() {}

    static JSONObject listRemotes() throws Exception {
        JSONObject out = new JSONObject();
        JSONArray remotes = new JSONArray();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            out.put("remotes", remotes);
            out.put("error", "no bluetooth adapter");
            return out;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                String name = d.getName() != null ? d.getName() : "";
                String addr = d.getAddress();
                // Prefer Amazon Fire remotes; still list all bonded for picker
                JSONObject r = new JSONObject();
                r.put("address", addr);
                r.put("name", name);
                r.put("bond_state", d.getBondState());
                boolean amazon = name.toLowerCase().contains("amazon")
                        || name.toLowerCase().contains("fire tv")
                        || name.toLowerCase().contains("firetv");
                r.put("likely_fire_remote", amazon);
                remotes.put(r);
            }
        }
        out.put("remotes", remotes);
        return out;
    }
}
