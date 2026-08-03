package com.amazon.device.kmlsynclib.keymap.sync;

import com.amazon.device.kmlsynclib.keymap.table.KeyMapTable;
import com.amazon.device.kmlsynclib.keymap.table.KeyMapTableId;

/* loaded from: classes.dex */
public interface KeyMapDeviceProxy {
    KeyMapDeviceError blastCommand(KeyMapTable keyMapTable);

    void close();

    void enableSDSOnRemote();

    KeyMapDeviceError eraseAllTables();

    void open();

    KeyMapDeviceError switchTable(KeyMapTableId keyMapTableId);

    KeyMapDeviceError writeTable(KeyMapTable keyMapTable);
}
