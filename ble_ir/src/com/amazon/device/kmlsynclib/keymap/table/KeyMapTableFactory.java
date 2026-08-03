package com.amazon.device.kmlsynclib.keymap.table;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes.dex */
public final class KeyMapTableFactory {
    private static final boolean DBG = true;
    private static final String TAG = "KeyMapTableFactory";

    private static KeyMapAction parseAction(JSONObject obj) throws JSONException {
        String commandType;
        commandType = obj.optString("CommandType", "");
        Log.v(TAG, "Parse " + commandType);
        switch (commandType) {
            case "IROptional":
                return new KeyMapActionIr(obj, DBG);
            case "IR":
                return new KeyMapActionIr(obj, false);
            case "BT":
                return new KeyMapActionBle(obj);
            case "LED":
                return new KeyMapActionLed(obj);
            default:
                Log.e(TAG, "CommandType not supported.");
                throw new JSONException("CommandType not provided/supported" + commandType);
        }
    }

    private static Collection<KeyMapAction> parseActions(JSONArray arr) throws JSONException {
        Log.v(TAG, "actions are " + arr.toString());
        ArrayList<KeyMapAction> ret = new ArrayList<>();
        int njsons = arr.length();
        for (int i = 0; i < njsons; i++) {
            JSONObject obj = arr.getJSONObject(i);
            ret.add(parseAction(obj));
        }
        return ret;
    }

    private static KeyMapTable buildTable(HashMap<String, Integer> scanIdMap, String tableName, JSONObject json) throws JSONException {
        String keyName;
        KeyMapTable table = new KeyMapTable(tableName);
        table.setScanIdMap(scanIdMap);
        Iterator<String> itr = json.keys();
        while (itr.hasNext()) {
            keyName = itr.next();
            Log.v(TAG, "For Key " + keyName);
            switch (keyName) {
                case "ID":
                    table.setId(json.optString(keyName, "00000000-0000-0000-0000-000000000000"));
                    break;
                default:
                    table.appendActions(keyName, parseActions(json.optJSONArray(keyName)));
                    break;
            }
        }
        return table;
    }

    private static KeyMapTable[] buildBlastTables(HashMap<String, Integer> scanIdMap, String tableName, JSONArray blasts, boolean enableSDS) throws JSONException {
        ArrayList<KeyMapTable> ret = new ArrayList<>();
        int ntables = blasts.length();
        for (int i = 0; i < ntables; i++) {
            KeyMapTable tbl = new KeyMapTable(tableName);
            tbl.setScanIdMap(scanIdMap);
            tbl.enableSDSOnRemote(enableSDS);
            Collection<KeyMapAction> actions = new ArrayList<>();
            actions.add(parseAction(blasts.getJSONObject(i)));
            tbl.appendActions("", actions);
            ret.add(tbl);
        }
        return (KeyMapTable[]) ret.toArray(new KeyMapTable[ret.size()]);
    }

    private static KeyMapTable[] buildKeyMapTables(HashMap<String, Integer> scanIdMap, JSONObject tables) throws JSONException {
        ArrayList<KeyMapTable> ret = new ArrayList<>();
        Iterator<String> tableItr = tables.keys();
        while (tableItr.hasNext()) {
            String tableName = tableItr.next();
            Log.v(TAG, "Parsing Table: " + tableName);
            KeyMapTable tmp = buildTable(scanIdMap, tableName, tables.getJSONObject(tableName));
            ret.add(tmp);
        }
        return (KeyMapTable[]) ret.toArray(new KeyMapTable[ret.size()]);
    }

    public static KeyMapTable[] fromJson(HashMap<String, Integer> scanIdMap, JSONObject json) throws JSONException {
        JSONObject tables = json.optJSONObject("TableUpdate");
        if (tables == null) {
            JSONArray blasts = json.optJSONArray("InstantFire");
            if (blasts == null) {
                return new KeyMapTable[0];
            }
            boolean enableSDS = json.optBoolean("enableSDS", false);
            Log.v(TAG, "EnabledSDS for InstantFire tables: " + enableSDS);
            return buildBlastTables(scanIdMap, "InstantFire", blasts, enableSDS);
        }
        return buildKeyMapTables(scanIdMap, tables);
    }

    public static KeyMapTableId buildId(JSONObject json) throws JSONException {
        KeyMapTableId id = new KeyMapTableId();
        JSONObject state = json.getJSONObject("StateChanged");
        String newState = state.getString("NewStateID");
        int toggleBitState = state.optInt("ToggleBitRegister", 0);
        id.setUUID(newState).setToggleBitState(toggleBitState);
        return id;
    }
}
