package com.amazon.device.kmlsynclib.keymap.sync;

/* loaded from: classes.dex */
public enum KeyMapDeviceError {
    NO_ERROR("No Error", 0),
    KEYMAP_DISCONNECTED("Disconnected from gatt server", -1),
    EMPTY_TABLE("Compiled table is empty.", -2),
    INVALID_TYPE("Table type is invalid", -3),
    TABLE_NOT_FOUND("Unable to find table", -4),
    BAD_STATE("Remote is in bad state", -5),
    NOT_CONNECTED("Remote is not available", -6),
    MISC("Misc error", -99);

    private int mError;
    private String mReason;

    KeyMapDeviceError(String reason, int errno) {
        this.mReason = reason;
        this.mError = errno;
    }

    public int getInt() {
        return this.mError;
    }

    public String getReason() {
        return this.mReason;
    }
}
