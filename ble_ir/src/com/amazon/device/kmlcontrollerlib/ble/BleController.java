package com.amazon.device.kmlcontrollerlib.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.util.Log;

/* loaded from: classes.dex */
class BleController {
    private static final boolean DBG = true;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_SERVICE_READY = 3;
    private static final String TAG = "BleController";
    private BluetoothAdapter mBluetoothAdapter;
    private final BluetoothDevice mBluetoothDevice;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothManager mBluetoothManager;
    private final BleControllerCallback mClientCb;
    private final Context mContext;
    private int mConnectionState = 0;
    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() { // from class: com.amazon.device.kmlcontrollerlib.ble.BleController.1
        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.i(BleController.TAG, "onConnectionStateChange received: " + status);
            BleController.this.handleBleConnectionStateChange(status, newState);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(BleController.TAG, "onServicesDiscovered received: " + status);
            BleController.this.handleBleServicesDiscovered(status);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.i(BleController.TAG, "onCharacteristicRead received: " + status);
            BleController.this.handleBleCharacteristicRead(characteristic, status);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.i(BleController.TAG, "onCharacteristicWrite received: " + status + " charateristic: " + characteristic.getUuid());
            BleController.this.handleBleCharacteristicWrite(characteristic, status);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.i(BleController.TAG, "onCharacteristicChanged received: " + (characteristic == null ? "null" : characteristic.getUuid()));
            BleController.this.handleBleCharacteristicChanged(characteristic);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.i(BleController.TAG, "onDescriptorRead received: " + status);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.i(BleController.TAG, "onDescriptorWrite received: " + status);
            BleController.this.handleBleDescriptorWrite(descriptor, status);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            Log.i(BleController.TAG, "onReliableWriteCompleted received: " + status);
        }

        @Override // android.bluetooth.BluetoothGattCallback
        public synchronized void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            Log.i(BleController.TAG, "onReadRemoteRssi received: " + status);
        }
    };

    public BleController(Context context, BluetoothDevice btDevice, BleControllerCallback callback) {
        this.mBluetoothDevice = btDevice;
        this.mContext = context;
        this.mClientCb = callback;
        if (!initialize()) {
            disconnect();
            Log.w(TAG, "BLE connection can't be created successfully.");
        }
    }

    private synchronized void disconnect() {
        Log.i(TAG, "disconnect enter.");
        if (this.mBluetoothAdapter == null || this.mBluetoothGatt == null) {
            Log.w(TAG, "mBluetoothAdapter or mBluetoothGatt not initialized.");
        } else {
            this.mBluetoothGatt.disconnect();
        }
    }

    private synchronized boolean initialize() {
        boolean z;
        Log.i(TAG, "initialize enter.");
        if (this.mContext == null) {
            Log.e(TAG, "Context not initialized.");
            z = false;
        } else {
            if (this.mBluetoothManager == null) {
                this.mBluetoothManager = (BluetoothManager) this.mContext.getSystemService("bluetooth");
                if (this.mBluetoothManager == null) {
                    Log.e(TAG, "Unable to initialize BluetoothManager.");
                    z = false;
                }
            }
            if (this.mBluetoothAdapter == null) {
                this.mBluetoothAdapter = this.mBluetoothManager.getAdapter();
                if (this.mBluetoothAdapter == null) {
                    Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
                    z = false;
                }
            }
            if (this.mBluetoothDevice == null || this.mBluetoothDevice.getAddress() == null) {
                Log.e(TAG, "BluetoothDevice not initialized.");
                z = false;
            } else {
                Log.i(TAG, "BluetoothDevice name:  " + this.mBluetoothDevice.getName() + " address : " + this.mBluetoothDevice.getAddress() + " bondState :" + this.mBluetoothDevice.getBondState());
                if (!connect(this.mBluetoothDevice)) {
                    Log.w(TAG, "Connect to gatt server failed.");
                    z = false;
                } else {
                    z = DBG;
                }
            }
        }
        return z;
    }

    private synchronized boolean connect(BluetoothDevice bluetoothDevice) {
        boolean z = false;
        synchronized (this) {
            Log.i(TAG, "connect enter.");
            if (this.mBluetoothAdapter == null || bluetoothDevice == null) {
                Log.w(TAG, "BluetoothAdapter or BluetoothDevice not initialized.");
            } else {
                Log.i(TAG, "Trying to create a new connection.");
                if (this.mBluetoothGatt != null) {
                    Log.i(TAG, "Ensure close the old GATT before creating a new connection.");
                    this.mBluetoothGatt.close();
                }
                this.mBluetoothGatt = bluetoothDevice.connectGatt(this.mContext, false, this.mGattCallback);
                if (this.mBluetoothGatt == null) {
                    Log.w(TAG, "BluetoothGatt create new connect failed.");
                } else {
                    Log.i(TAG, "BluetoothGatt create new connect success.");
                    this.mConnectionState = 1;
                    z = true;
                }
            }
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void handleBleConnectionStateChange(int status, int newState) {
        synchronized (this) {
            Log.i(TAG, "handleBleConnectionStateChange : " + status);
            if (newState == 2) {
                this.mConnectionState = 2;
                Log.i(TAG, "Connected to GATT server.");
                if (this.mBluetoothGatt == null || !this.mBluetoothGatt.discoverServices()) {
                    disconnect();
                }
            } else if (newState == 0) {
                this.mConnectionState = 0;
                Log.i(TAG, "Disconnected from GATT server.");
                if (this.mBluetoothGatt != null) {
                    this.mBluetoothGatt.close();
                }
                this.mBluetoothGatt = null;
                this.mClientCb.onDisconnect();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void handleBleServicesDiscovered(int status) {
        synchronized (this) {
            Log.i(TAG, "handleBleServicesDiscovered : " + status);
            if (this.mConnectionState != 2) {
                Log.w(TAG, "handleBleCharacteristicRead ignore : mConnectionState not STATE_CONNECTED");
            } else if (status == 0) {
                this.mConnectionState = STATE_SERVICE_READY;
                Log.i(TAG, "handleBleServicesDiscovered mConnectionState: STATE_SERVICE_READY");
                this.mClientCb.onServicesDiscovered(this.mBluetoothGatt);
            } else {
                disconnect();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void handleBleCharacteristicWrite(BluetoothGattCharacteristic characteristic, int status) {
        Log.i(TAG, "handleBleCharacteristicWrite : " + status);
        this.mClientCb.onCharacteristicWritten(characteristic, status);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void handleBleCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        Log.i(TAG, "handleBleCharacteristicChanged received.");
        if (characteristic == null) {
            Log.i(TAG, "handleBleCharacteristicChanged null characteristic");
        } else {
            Log.i(TAG, "handleBleCharacteristicChanged " + characteristic.getUuid() + " at mConnectionState=" + this.mConnectionState);
            this.mClientCb.onCharacteristicNotify(characteristic);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void handleBleDescriptorWrite(BluetoothGattDescriptor descriptor, int status) {
        Log.i(TAG, "handleBleDescriptorWrite received.");
        if (descriptor == null) {
            Log.i(TAG, "handleBleDescriptorWrite null characteristic");
        } else {
            Log.i(TAG, "handleBleDescriptorWrite " + descriptor.getCharacteristic().getUuid() + " at Status=" + status);
            if (status == 0) {
                this.mClientCb.onCharacteristicNotifyEnabled(descriptor);
            } else {
                disconnect();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void handleBleCharacteristicRead(BluetoothGattCharacteristic characteristic, int status) {
        Log.i(TAG, "handleBleCharacteristicRead : " + status + " characteristic " + characteristic.getUuid().toString());
        this.mClientCb.onCharacteristicRead(characteristic, status);
    }

    public boolean requestReadCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) {
            return false;
        }
        Log.i(TAG, "requestReadCharacteristic: " + characteristic.getUuid().toString());
        return this.mBluetoothGatt.readCharacteristic(characteristic);
    }

    public boolean requestWriteCharacteristic(BluetoothGattCharacteristic characteristic, byte[] data) {
        if (characteristic == null) {
            return false;
        }
        Log.i(TAG, "requestWriteCharacteristic: " + characteristic.getUuid().toString());
        characteristic.setValue(data);
        return this.mBluetoothGatt.writeCharacteristic(characteristic);
    }

    public boolean requestNotifyCharacteristic(BluetoothGattCharacteristic characteristic, boolean enable) {
        if (characteristic == null) {
            return false;
        }
        Log.i(TAG, "requestNotifyCharacteristic: " + characteristic.getUuid().toString());
        boolean ret = this.mBluetoothGatt.setCharacteristicNotification(characteristic, enable);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(BleConsts.CCCD_UUID);
        if (descriptor != null) {
            return ret | descriptor.setValue(enable ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) | this.mBluetoothGatt.writeDescriptor(descriptor);
        }
        return false;
    }

    public void close() {
        disconnect();
    }
}
