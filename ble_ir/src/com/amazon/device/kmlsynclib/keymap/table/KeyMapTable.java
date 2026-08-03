package com.amazon.device.kmlsynclib.keymap.table;

import android.util.Log;
import com.amazon.device.kmlsynclib.utils.ByteTool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/* loaded from: classes.dex */
public class KeyMapTable {
    private static final String TAG = "KeyMapTable";
    private byte[] mCompiledTable;
    private boolean mDataChanged;
    private boolean mEnableSDS;
    private int mMaxActionDelay;
    private String mName;
    private LinkedHashMap<String, ArrayList<KeyMapAction>> mRows;
    private HashMap<String, Integer> mScanIdMap;
    private String mTableIdStr;

    KeyMapTable(String tableName) {
        this();
        setName(tableName);
    }

    KeyMapTable() {
        this.mRows = new LinkedHashMap<>();
        this.mName = "";
        this.mTableIdStr = "00000000-0000-0000-0000-000000000000";
        this.mDataChanged = true;
        this.mMaxActionDelay = 0;
        this.mEnableSDS = false;
    }

    public synchronized KeyMapTable setId(String str) {
        this.mTableIdStr = str;
        return this;
    }

    public synchronized KeyMapTable setName(String name) {
        this.mName = name;
        return this;
    }

    public synchronized KeyMapTable setScanIdMap(HashMap<String, Integer> scanIdMap) {
        this.mScanIdMap = scanIdMap;
        return this;
    }

    public synchronized KeyMapTable enableSDSOnRemote(boolean flag) {
        this.mEnableSDS = flag;
        return this;
    }

    public boolean isSDSEnabled() {
        return this.mEnableSDS;
    }

    public synchronized void appendActions(String key, Collection<KeyMapAction> actions) {
        ArrayList<KeyMapAction> actionList;
        if (this.mRows.containsKey(key)) {
            actionList = this.mRows.get(key);
        } else {
            actionList = new ArrayList<>();
            this.mRows.put(key, actionList);
        }
        for (KeyMapAction action : actions) {
            this.mMaxActionDelay += action.getActionDelay();
        }
        actionList.addAll(actions);
        this.mDataChanged = true;
    }

    public synchronized String describe() {
        StringBuilder buffer;
        buffer = new StringBuilder();
        buffer.append("+------------------\n|");
        buffer.append(this.mName + "\n|");
        buffer.append(String.format("%s%n", getId().toString()));
        buffer.append("EnableSDS: " + this.mEnableSDS);
        buffer.append("|Key\t\t|Action\n+-------------------\n");
        for (Map.Entry<String, ArrayList<KeyMapAction>> row : this.mRows.entrySet()) {
            buffer.append(String.format("%-12s|", row.getKey() + ((int) getScanId(row.getKey()))));
            ArrayList<KeyMapAction> actions = row.getValue();
            for (KeyMapAction action : actions) {
                buffer.append(action.describe() + " ");
            }
            buffer.append("\n");
        }
        buffer.append("+-------------------\n");
        return buffer.toString();
    }

    public synchronized byte[] compile() {
        if (this.mDataChanged) {
            compileTable();
            this.mDataChanged = false;
        }
        return this.mCompiledTable;
    }

    public synchronized byte[] compileWithChecksum() {
        compile();
        return ByteTool.concatBytes(this.mCompiledTable, new KeyMapTableId().digest(this.mCompiledTable).getHash());
    }

    private byte getScanId(String keyName) {
        Integer scanid;
        if (this.mScanIdMap == null || (scanid = this.mScanIdMap.get(keyName)) == null) {
            return (byte) -1;
        }
        return scanid.byteValue();
    }

    private void compileTable() {
        try {
            ByteArrayOutputStream binary = new ByteArrayOutputStream();
            binary.write(ByteTool.i8Tob(this.mRows.size()));
            for (Map.Entry<String, ArrayList<KeyMapAction>> row : this.mRows.entrySet()) {
                String k = row.getKey();
                ArrayList<KeyMapAction> v = row.getValue();
                binary.write(ByteTool.i8Tob(getScanId(k)));
                binary.write(ByteTool.i8Tob(v.size()));
                ByteArrayOutputStream actionBytes = new ByteArrayOutputStream();
                for (KeyMapAction action : v) {
                    actionBytes.write(action.compile());
                }
                binary.write(ByteTool.i16Tob(actionBytes.size()));
                binary.write(actionBytes.toByteArray());
            }
            this.mCompiledTable = binary.toByteArray();
        } catch (IOException e) {
            Log.wtf(TAG, "KeyMap Table Compilation Error!");
        }
    }

    public synchronized KeyMapTableId getId() {
        return new KeyMapTableId().digest(compile()).setUUID(this.mTableIdStr);
    }

    public byte[] compileAsBlast() {
        byte[] payload = compile();
        return Arrays.copyOfRange(payload, 1, payload.length);
    }

    public int getActionDelay() {
        return this.mMaxActionDelay;
    }
}
