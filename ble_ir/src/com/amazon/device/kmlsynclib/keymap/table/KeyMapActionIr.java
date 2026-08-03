package com.amazon.device.kmlsynclib.keymap.table;

import com.amazon.device.kmlsynclib.utils.ByteTool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes.dex */
final class KeyMapActionIr extends KeyMapAction {
    private static final int REPEAT_FLAG_SEQUENCE = 32;
    private static final int REPEAT_FLAG_TOGGLE = 16;
    private static final String TAG = "KeyMapActionIr";
    private final KeyMapActionType mAction;
    private int mDutyCycle;
    private int mFreq;
    private int mPostDelay;
    private int[][] mRawCodes;
    private int mRepeat;
    private String mRepeatType;
    private int mToggleMask;

    KeyMapActionIr(JSONObject json, boolean optional) throws JSONException {
        this.mRepeat = 0;
        parseJsonRawCode(json);
        this.mFreq = json.getInt("Frequency");
        this.mRepeat = json.getInt("Repeat");
        this.mPostDelay = json.getInt("PostDelay");
        this.mDutyCycle = json.getInt("DutyCycle");
        this.mRepeatType = json.optString("RepeatType", "Basic");
        this.mToggleMask = json.optInt("ToggleBitMask", 0);
        if (optional) {
            this.mAction = KeyMapActionType.IR_CODE_RAW_OPT;
        } else {
            this.mAction = KeyMapActionType.IR_CODE_RAW;
        }
    }

    private void parseJsonRawCode(JSONObject json) throws JSONException {
        JSONArray rawCodeArray = json.getJSONArray("IRCode");
        this.mRawCodes = new int[rawCodeArray.length()][];
        for (int i = 0; i < rawCodeArray.length(); i++) {
            String rawCodeString = rawCodeArray.getString(i);
            String[] codes = rawCodeString.trim().split("s");
            this.mRawCodes[i] = new int[codes.length];
            for (int j = 0; j < codes.length; j++) {
                this.mRawCodes[i][j] = Integer.parseInt(codes[j]);
            }
        }
    }

    private byte[] compileRawCode() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(String.format("f[%d]", Integer.valueOf(this.mFreq)).getBytes(StandardCharsets.US_ASCII));
        payload.write(String.format("c[%d]", Integer.valueOf(this.mDutyCycle)).getBytes(StandardCharsets.US_ASCII));
        payload.write("l".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < 2; i++) {
            if (i < this.mRawCodes.length) {
                payload.write(String.format("[%d]", Integer.valueOf(this.mRawCodes[i].length)).getBytes(StandardCharsets.US_ASCII));
            } else {
                payload.write("[0]".getBytes(StandardCharsets.US_ASCII));
            }
        }
        payload.write(String.format("r[%d]", Integer.valueOf(this.mRepeat)).getBytes(StandardCharsets.US_ASCII));
        payload.write(String.format("d[%d]", Integer.valueOf(this.mPostDelay)).getBytes(StandardCharsets.US_ASCII));
        payload.write(String.format("t[%d]", Integer.valueOf(this.mToggleMask)).getBytes(StandardCharsets.US_ASCII));
        payload.write(0);
        for (int i2 = 0; i2 < Math.min(this.mRawCodes.length, 2); i2++) {
            int[] arr$ = this.mRawCodes[i2];
            for (int code : arr$) {
                payload.write(ByteTool.i16Tob(code));
            }
        }
        return payload.toByteArray();
    }

    @Override // com.amazon.device.kmlsynclib.keymap.table.KeyMapAction
    public byte[] compile() throws IOException {
        int repeatFlags;
        repeatFlags = 0;
        switch (this.mRepeatType) {
            case "Toggle":
                repeatFlags = 0 | 16;
                break;
            case "Sequence":
                repeatFlags = 0 | 32;
                break;
        }
        return compileAction(this.mAction, repeatFlags, compileRawCode());
    }

    @Override // com.amazon.device.kmlsynclib.keymap.table.KeyMapAction
    public String describe() {
        StringBuilder description = new StringBuilder();
        description.append(this.mAction.toString());
        int[][] arr$ = this.mRawCodes;
        for (int[] rawCode : arr$) {
            description.append(String.format("[%d]", Integer.valueOf(rawCode.length)));
        }
        description.append(String.format("(t: %s, f: %d, dc: %d, r: %d, pd: %d tb:%d)", this.mRepeatType, Integer.valueOf(this.mFreq), Integer.valueOf(this.mDutyCycle), Integer.valueOf(this.mRepeat), Integer.valueOf(this.mPostDelay), Integer.valueOf(this.mToggleMask)));
        return description.toString();
    }

    @Override // com.amazon.device.kmlsynclib.keymap.table.KeyMapAction
    public int getActionDelay() {
        return this.mPostDelay;
    }
}
