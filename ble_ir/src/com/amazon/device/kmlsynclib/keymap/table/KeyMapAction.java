package com.amazon.device.kmlsynclib.keymap.table;

import com.amazon.device.kmlsynclib.utils.ByteTool;
import java.io.IOException;

/* loaded from: classes.dex */
abstract class KeyMapAction {
    int FLAG_LOG_ENALBE = 1;

    abstract byte[] compile() throws IOException;

    abstract String describe();

    KeyMapAction() {
    }

    protected byte[] compileAction(KeyMapActionType type, int flags, byte[] payload) {
        return ByteTool.concatBytes(ByteTool.i8Tob(type.value), ByteTool.i8Tob(flags), ByteTool.i16Tob(payload.length), payload);
    }

    public int getActionDelay() {
        return 0;
    }
}
