package com.amazon.device.kmlsynclib.keymap.table;

import com.amazon.device.kmlsynclib.utils.ByteTool;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes.dex */
final class KeyMapActionBle extends KeyMapAction {
    private static final byte LONG_PRESS = 1;
    private static final byte SHORT_PRESS = 0;
    private final String mCommand;

    KeyMapActionBle(JSONObject json) throws JSONException {
        this.mCommand = json.optString("BTCommand", "BT_KEYPRESS");
    }

    @Override // com.amazon.device.kmlsynclib.keymap.table.KeyMapAction
    public byte[] compile() throws IOException {
        return compileAction(KeyMapActionType.BLE_KEYPRESS, 0, ByteTool.i8Tob(0));
    }

    @Override // com.amazon.device.kmlsynclib.keymap.table.KeyMapAction
    public String describe() {
        return String.format("BLE[%s]", this.mCommand);
    }
}
