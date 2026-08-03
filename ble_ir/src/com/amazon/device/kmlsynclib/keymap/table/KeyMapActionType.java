package com.amazon.device.kmlsynclib.keymap.table;

/* loaded from: classes.dex */
enum KeyMapActionType {
    NO_ACTION(0, "NO_ACTION"),
    DELAY_MS(1, "DELAY"),
    BLE_HID(2, "HID"),
    IR_CODE_RAW(3, "IR"),
    CEC_NOTIFY_HOST(4, "NOTIFY_HOST"),
    BLE_KEYPRESS(5, "BT_KEY"),
    IR_CODE_RAW_OPT(6, "IR_OPT"),
    LED(7, "LED");

    public final String name;
    public final byte value;

    KeyMapActionType(int v, String name) {
        this.value = (byte) v;
        this.name = name;
    }

    @Override // java.lang.Enum
    public String toString() {
        return this.name;
    }
}
