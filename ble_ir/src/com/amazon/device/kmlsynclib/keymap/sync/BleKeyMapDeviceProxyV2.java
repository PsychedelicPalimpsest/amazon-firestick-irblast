package com.amazon.device.kmlsynclib.keymap.sync;

import android.content.Context;
import android.util.Log;
import com.amazon.device.kmlcontrollerlib.ble.OperationResult;
import com.amazon.device.kmlcontrollerlib.ble.SynchronousBleController;
import com.amazon.device.kmlcontrollerlib.ble.SynchronousController;
import com.amazon.device.kmlsynclib.keymap.table.KeyMapTable;
import com.amazon.device.kmlsynclib.keymap.table.KeyMapTableId;
import com.amazon.device.kmlsynclib.utils.ByteTool;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes.dex */
public class BleKeyMapDeviceProxyV2 implements KeyMapDeviceProxy {
    private static final int CHUNK_SIZE = 200;
    private static final int MAX_RETRY_COUNT = 9;
    private static final String TAG = "SBleKeyMapDeviceProxy";
    SynchronousBleController mController;

    public BleKeyMapDeviceProxyV2(Context context, String address) {
        this.mController = new SynchronousBleController(context, address, BleConfig.KEYMAP_SERVICE_UUID);
    }

    @Override // com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy
    public void open() {
        this.mController.open();
        this.mController.syncEnableSubscription(BleConfig.KEYMAP_BLAST_CHAR_UUID, true);
        this.mController.syncEnableSubscription(BleConfig.KEYMAP_MAPPING_CHAR_UUID, true);
    }

    @Override // com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy
    public KeyMapDeviceError writeTable(KeyMapTable table) {
        byte[] binary = table.compileWithChecksum();
        Log.v(TAG, ByteTool.toHexString(binary, 80));
        Log.v(TAG, String.format("%s", table.describe()));
        OperationResult op = requestStartNewTable(table.getId(), binary.length);
        if (!op.isOK()) {
            return toKMError(op);
        }
        OperationResult op2 = writeBinary(binary, BleConfig.KEYMAP_MAPPING_CHAR_UUID);
        if (!op2.isOK()) {
            return toKMError(op2);
        }
        OperationResult op3 = this.mController.syncRead(BleConfig.KEYMAP_MAPPING_CHAR_UUID);
        if (!op3.isOK()) {
            return toKMError(op3);
        }
        if (tableWriteCompleted(op3.value)) {
            return KeyMapDeviceError.NO_ERROR;
        }
        return KeyMapDeviceError.BAD_STATE;
    }

    @Override // com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy
    public KeyMapDeviceError eraseAllTables() {
        ByteBuffer cmdbuf = ByteBuffer.allocate(1);
        cmdbuf.put(ByteTool.i8Tob(16));
        OperationResult op = this.mController.syncWrite(BleConfig.KEYMAP_CONTROL_CHAR_UUID, cmdbuf.array());
        return toKMError(op);
    }

    @Override // com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy
    public KeyMapDeviceError switchTable(KeyMapTableId target) {
        OperationResult op = requestTables();
        if (!op.isOK()) {
            return toKMError(op);
        }
        KeyMapTableId[] tables = parseTableIds(op.value);
        boolean found = false;
        int len$ = tables.length;
        int i$ = 0;
        while (true) {
            if (i$ >= len$) {
                break;
            }
            KeyMapTableId id = tables[i$];
            if (!target.equals(id)) {
                i$++;
            } else {
                found = true;
                break;
            }
        }
        if (!found && !target.isDefaultTable()) {
            return KeyMapDeviceError.TABLE_NOT_FOUND;
        }
        Log.v(TAG, "Found table" + target.getUUID().toString());
        ByteBuffer cmdbuf = ByteBuffer.allocate(18);
        cmdbuf.put(ByteTool.i8Tob(1));
        cmdbuf.put(target.getIndexBytes());
        cmdbuf.put(target.getToggleBItState());
        if (this.mController.syncWrite(BleConfig.KEYMAP_CONTROL_CHAR_UUID, cmdbuf.array()).isOK()) {
            return KeyMapDeviceError.NO_ERROR;
        }
        return KeyMapDeviceError.BAD_STATE;
    }

    @Override // com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy
    public KeyMapDeviceError blastCommand(KeyMapTable table) {
        Log.v(TAG, ByteTool.toHexString(table.compileWithChecksum(), 80));
        Log.v(TAG, String.format("%s", table.describe()));
        byte[] binary = table.compileAsBlast();
        if (!requestStartNewTable(table.getId(), binary.length).isOK()) {
            return KeyMapDeviceError.BAD_STATE;
        }
        final CountDownLatch blastDoneLatch = new CountDownLatch(1);
        final AtomicBoolean blastCompleted = new AtomicBoolean(false);
        this.mController.syncSubscribe(BleConfig.KEYMAP_BLAST_CHAR_UUID, new SynchronousController.OperationResultListener() { // from class: com.amazon.device.kmlsynclib.keymap.sync.BleKeyMapDeviceProxyV2.1
            @Override // com.amazon.device.kmlcontrollerlib.ble.SynchronousController.OperationResultListener
            public void onResult(OperationResult result) {
                if (result.isOK()) {
                    blastCompleted.set(BleKeyMapDeviceProxyV2.this.tableWriteCompleted(result.value));
                }
                blastDoneLatch.countDown();
            }
        });
        if (!writeBinary(binary, BleConfig.KEYMAP_BLAST_CHAR_UUID).isOK()) {
            return KeyMapDeviceError.BAD_STATE;
        }
        if (!commitBlast().isOK()) {
            return KeyMapDeviceError.BAD_STATE;
        }
        try {
            int delay = Math.abs(table.getActionDelay()) + 60;
            Log.i(TAG, "Action Delay is " + delay);
            blastDoneLatch.await(delay, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        this.mController.syncSubscribe(BleConfig.KEYMAP_BLAST_CHAR_UUID, null);
        if (blastDoneLatch.getCount() == 0) {
            return KeyMapDeviceError.NO_ERROR;
        }
        Log.i(TAG, "Got no notification from remote, going to fallback ");
        int retries = 1;
        while (retries < MAX_RETRY_COUNT) {
            try {
                Thread.sleep(retries * 20);
            } catch (InterruptedException e2) {
                e2.printStackTrace();
            }
            retries++;
            OperationResult op = this.mController.syncRead(BleConfig.KEYMAP_BLAST_CHAR_UUID);
            if (!op.isOK()) {
                return KeyMapDeviceError.BAD_STATE;
            }
            if (tableWriteCompleted(op.value)) {
                return KeyMapDeviceError.NO_ERROR;
            }
            Log.i(TAG, "Blast not done, retrying...");
        }
        return KeyMapDeviceError.BAD_STATE;
    }

    private OperationResult writeBinary(byte[] binary, UUID destination) {
        OperationResult op = new OperationResult(OperationResult.Status.MISC);
        ByteArrayInputStream stream = new ByteArrayInputStream(binary);
        while (stream.available() > 0) {
            byte[] chunk = new byte[CHUNK_SIZE];
            try {
                int bytesRead = stream.read(chunk);
                Log.i(TAG, String.format("%d bytes read %d left", Integer.valueOf(bytesRead), Integer.valueOf(stream.available())));
                op = this.mController.syncWrite(destination, chunk);
                if (!op.isOK()) {
                    return op;
                }
            } catch (IOException e) {
                return new OperationResult(OperationResult.Status.MISC);
            }
        }
        return op;
    }

    @Override // com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy
    public void enableSDSOnRemote() {
        this.mController.syncWrite(BleConfig.KEYMAP_CONTROL_CHAR_UUID, ByteTool.i8Tob(32));
    }

    @Override // com.amazon.device.kmlsynclib.keymap.sync.KeyMapDeviceProxy
    public void close() {
        this.mController.syncEnableSubscription(BleConfig.KEYMAP_BLAST_CHAR_UUID, false);
        this.mController.syncEnableSubscription(BleConfig.KEYMAP_MAPPING_CHAR_UUID, false);
        this.mController.close();
    }

    private KeyMapDeviceError toKMError(OperationResult op) {
        switch (op.status) {
            case OK:
                return KeyMapDeviceError.NO_ERROR;
            case DSICONNECTED:
                return KeyMapDeviceError.KEYMAP_DISCONNECTED;
            case NOT_SUPPORTED:
                return KeyMapDeviceError.INVALID_TYPE;
            default:
                return KeyMapDeviceError.BAD_STATE;
        }
    }

    private OperationResult requestTables() {
        return this.mController.syncRead(BleConfig.KEYMAP_CONTROL_CHAR_UUID);
    }

    private KeyMapTableId[] parseTableIds(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new KeyMapTableId[0];
        }
        int nTables = (byte) (bytes[0] & Byte.MAX_VALUE);
        int expectedSize = (nTables * 16) + 1;
        if (bytes.length != expectedSize) {
            Log.e(TAG, String.format("Query does not match expected size got %d, expected %d", Integer.valueOf(bytes.length), Integer.valueOf(expectedSize)));
            return new KeyMapTableId[0];
        }
        KeyMapTableId[] ret = new KeyMapTableId[nTables];
        for (int i = 0; i < nTables; i++) {
            int offset = (i * 16) + 1;
            ret[i] = new KeyMapTableId();
            ByteBuffer buf = ByteBuffer.wrap(Arrays.copyOfRange(bytes, offset, offset + 16));
            ret[i].setUUID(new UUID(buf.getLong(), buf.getLong()));
            Log.v(TAG, "Fetch Table" + ret[i].toString());
        }
        return ret;
    }

    private OperationResult commitBlast() {
        return this.mController.syncWrite(BleConfig.KEYMAP_CONTROL_CHAR_UUID, new byte[]{5});
    }

    private OperationResult requestStartNewTable(KeyMapTableId id, int length) {
        ByteBuffer cmdbuf = ByteBuffer.allocate(22);
        cmdbuf.put(ByteTool.i8Tob(2));
        cmdbuf.put(ByteTool.i8Tob(1));
        cmdbuf.put(id.getIndexBytes());
        cmdbuf.put(ByteTool.i16Tob(0));
        cmdbuf.put(ByteTool.i16Tob(length));
        return this.mController.syncWrite(BleConfig.KEYMAP_CONTROL_CHAR_UUID, cmdbuf.array());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean tableWriteCompleted(byte[] bytes) {
        Log.v(TAG, "Verification Result=" + ByteTool.toHexString(bytes, 80));
        return bytes != null && bytes.length >= 1 && bytes[0] == 2;
    }
}
