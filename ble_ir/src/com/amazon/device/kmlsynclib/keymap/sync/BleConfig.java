package com.amazon.device.kmlsynclib.keymap.sync;

import java.util.UUID;

/* loaded from: classes.dex */
public final class BleConfig {
    public static final int KEYMAP_CONTROL_COMMIT_BLAST_TABLE = 5;
    public static final int KEYMAP_CONTROL_CONTEXT_SWITCH = 1;
    public static final int KEYMAP_CONTROL_DELETE_TABLE = 16;
    public static final int KEYMAP_CONTROL_ENABLE_SDS = 32;
    public static final int KEYMAP_CONTROL_RESET_STAGING_TABLE = 2;
    public static final int KEYMAP_UPDATE_STATUS_SUCCESS = 2;
    public static final int KEYMAP_VERIFICATION_NONE = 0;
    public static final int KEYMAP_VERIFICATION_SHA2 = 1;
    public static final UUID KEYMAP_SERVICE_UUID = UUID.fromString("fe151500-5e8d-11e6-8b77-86f30ca893d3");
    public static final UUID KEYMAP_MAPPING_CHAR_UUID = UUID.fromString("fe151501-5e8d-11e6-8b77-86f30ca893d3");
    public static final UUID KEYMAP_CONTROL_CHAR_UUID = UUID.fromString("fe151502-5e8d-11e6-8b77-86f30ca893d3");
    public static final UUID KEYMAP_BLAST_CHAR_UUID = UUID.fromString("fe151503-5e8d-11e6-8b77-86f30ca893d3");

    BleConfig() {
    }
}
