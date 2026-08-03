package com.amazon.device.kmlcontrollerlib.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;
import com.amazon.device.kmlcontrollerlib.ble.OperationResult;
import com.amazon.device.kmlcontrollerlib.ble.SynchronousController;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/* loaded from: classes.dex */
public class SynchronousBleController implements BleControllerCallback, SynchronousController {
    private static final boolean DBG = true;
    private static final int MAX_RETRY = 30;
    private static final int OPERATION_TIMEOUT_MS = 5000;
    private static final String TAG = "SyncBleController";
    private boolean mConnectionEstablished;
    private final BleController mDevice;
    private final Hashtable<UUID, BluetoothGattCharacteristic> mGattDB;
    private final CountDownLatch mInitLatch;
    private CountDownLatch mOperationLatch;
    private final Object mOperationLock = new Object();
    private OperationResult mOperationResult;
    private final HashMap<UUID, SynchronousController.OperationResultListener> mResultDB;
    private final UUID mServiceUUID;

    public SynchronousBleController(Context context, String address, UUID service) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        this.mDevice = new BleController(context, device, this);
        this.mInitLatch = new CountDownLatch(1);
        this.mGattDB = new Hashtable<>();
        this.mResultDB = new HashMap<>();
        this.mServiceUUID = service;
        this.mConnectionEstablished = false;
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.SynchronousController
    public synchronized void open() {
        try {
            this.mInitLatch.await(5000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private OperationResult doSyncWrite(UUID uuid, byte[] payload) {
        OperationResult result = initResult(uuid);
        if (result.isOK()) {
            setOperation(result);
            if (!this.mDevice.requestWriteCharacteristic(fetchCharacteristic(uuid), payload)) {
                completeOperation(OperationResult.Status.RETRY, null);
            }
            pollOperation(5000L);
        }
        return result;
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.SynchronousController
    public synchronized OperationResult syncWrite(UUID uuid, byte[] payload) {
        OperationResult res;
        int retries = 0;
        OperationResult res2 = initResult(uuid);
        while (true) {
            if (retries >= MAX_RETRY) {
                res = res2;
                break;
            }
            res2 = doSyncWrite(uuid, payload);
            if (res2.status != OperationResult.Status.RETRY) {
                res = res2;
                break;
            }
            Log.i(TAG, "Operation failed, retrying");
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            retries++;
        }
        return res;
    }

    private OperationResult doSyncRead(UUID uuid) {
        OperationResult result = initResult(uuid);
        if (result.isOK()) {
            setOperation(result);
            if (!this.mDevice.requestReadCharacteristic(fetchCharacteristic(uuid))) {
                completeOperation(OperationResult.Status.RETRY, null);
            }
            pollOperation(5000L);
        }
        return result;
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.SynchronousController
    public synchronized OperationResult syncRead(UUID uuid) {
        OperationResult res;
        int retries = 0;
        OperationResult res2 = initResult(uuid);
        while (true) {
            if (retries >= MAX_RETRY) {
                res = res2;
                break;
            }
            res2 = doSyncRead(uuid);
            if (res2.status != OperationResult.Status.RETRY) {
                res = res2;
                break;
            }
            Log.i(TAG, "Operation failed, retrying");
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            retries++;
        }
        return res;
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.SynchronousController
    public OperationResult syncEnableSubscription(UUID uuid, boolean enable) {
        OperationResult result = initResult(uuid);
        if (result.isOK()) {
            setOperation(result);
            if (enable) {
                if (!this.mDevice.requestNotifyCharacteristic(fetchCharacteristic(uuid), DBG)) {
                    completeOperation(OperationResult.Status.RETRY, null);
                }
            } else if (!this.mDevice.requestNotifyCharacteristic(fetchCharacteristic(uuid), false)) {
                completeOperation(OperationResult.Status.RETRY, null);
            }
            pollOperation(5000L);
            if (result.isOK() && result.value != null && result.value.length == 2) {
                if (enable && result.value[0] == 1) {
                    Log.i(TAG, "Notification enabled for " + uuid);
                } else if (!enable && result.value[0] == 0) {
                    Log.i(TAG, "Notification disabled for " + uuid);
                }
            }
            result.status = OperationResult.Status.BAD_STATE;
        }
        return result;
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.SynchronousController
    public synchronized void syncSubscribe(UUID uuid, SynchronousController.OperationResultListener listener) {
        synchronized (this.mResultDB) {
            this.mResultDB.put(uuid, listener);
        }
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.SynchronousController
    public synchronized OperationResult poll(long timeout) {
        OperationResult result;
        if (hasActiveOperation()) {
            result = new OperationResult(OperationResult.Status.BUSY);
        } else {
            result = new OperationResult(OperationResult.Status.TIMEOUT);
            setOperation(result);
            pollOperation(timeout);
        }
        return result;
    }

    private BluetoothGattCharacteristic fetchCharacteristic(UUID uuid) {
        return this.mGattDB.get(uuid);
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.SynchronousController
    public synchronized void close() {
        synchronized (this.mResultDB) {
            this.mResultDB.clear();
        }
        this.mDevice.close();
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.BleControllerCallback
    public void onServicesDiscovered(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(this.mServiceUUID);
        if (service != null) {
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                Log.i(TAG, "Loaded characteristic " + c.getUuid().toString());
                this.mGattDB.put(c.getUuid(), c);
            }
        } else {
            Log.e(TAG, "Service not supported");
        }
        this.mConnectionEstablished = DBG;
        this.mInitLatch.countDown();
    }

    private void handleCharacteristicCB(BluetoothGattCharacteristic characteristic, int status) {
        if (status == 0) {
            completeOperation(OperationResult.Status.OK, characteristic.getValue());
        } else if (status == 133) {
            completeOperation(OperationResult.Status.RETRY, characteristic.getValue());
        } else {
            completeOperation(OperationResult.Status.BAD_STATE, characteristic.getValue());
        }
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.BleControllerCallback
    public void onCharacteristicRead(BluetoothGattCharacteristic characteristic, int status) {
        handleCharacteristicCB(characteristic, status);
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.BleControllerCallback
    public void onCharacteristicWritten(BluetoothGattCharacteristic characteristic, int status) {
        handleCharacteristicCB(characteristic, status);
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.BleControllerCallback
    public void onCharacteristicNotify(BluetoothGattCharacteristic characteristic) {
        dispatchToSubscribers(characteristic);
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.BleControllerCallback
    public void onCharacteristicNotifyEnabled(BluetoothGattDescriptor descriptor) {
        completeOperation(OperationResult.Status.OK, descriptor.getValue());
    }

    private void dispatchToSubscribers(BluetoothGattCharacteristic characteristic) {
        SynchronousController.OperationResultListener listener;
        synchronized (this.mResultDB) {
            listener = this.mResultDB.get(characteristic.getUuid());
        }
        if (listener != null) {
            OperationResult res = new OperationResult(OperationResult.Status.OK);
            res.value = characteristic.getValue();
            listener.onResult(res);
        }
    }

    private OperationResult initResult(UUID uuid) {
        OperationResult res = new OperationResult(OperationResult.Status.OK);
        if (hasActiveOperation()) {
            res.status = OperationResult.Status.BUSY;
        } else if (!this.mConnectionEstablished) {
            res.status = OperationResult.Status.DSICONNECTED;
        } else if (fetchCharacteristic(uuid) == null) {
            res.status = OperationResult.Status.NOT_SUPPORTED;
        }
        return res;
    }

    @Override // com.amazon.device.kmlcontrollerlib.ble.BleControllerCallback
    public void onDisconnect() {
        this.mConnectionEstablished = false;
        this.mInitLatch.countDown();
        completeOperation(OperationResult.Status.DSICONNECTED, null);
    }

    private boolean hasActiveOperation() {
        boolean ret = false;
        synchronized (this.mOperationLock) {
            if (this.mOperationLatch != null || this.mOperationResult != null) {
                ret = DBG;
            }
        }
        return ret;
    }

    private void setOperation(OperationResult res) {
        synchronized (this.mOperationLock) {
            this.mOperationLatch = new CountDownLatch(1);
            this.mOperationResult = res;
        }
    }

    private void clearOperation() {
        synchronized (this.mOperationLock) {
            this.mOperationLatch = null;
            this.mOperationResult = null;
        }
    }

    private void completeOperation(OperationResult.Status status, byte[] data) {
        synchronized (this.mOperationLock) {
            if (this.mOperationResult != null) {
                this.mOperationResult.status = status;
                this.mOperationResult.value = data;
            }
            if (this.mOperationLatch != null) {
                this.mOperationLatch.countDown();
            }
        }
    }

    private void pollOperation(long timeout) {
        try {
            this.mOperationLatch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            clearOperation();
        }
    }
}
