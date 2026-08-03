package com.amazon.device.kmlcontrollerlib.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

/* loaded from: classes.dex */
interface BleControllerCallback {
    void onCharacteristicNotify(BluetoothGattCharacteristic bluetoothGattCharacteristic);

    void onCharacteristicNotifyEnabled(BluetoothGattDescriptor bluetoothGattDescriptor);

    void onCharacteristicRead(BluetoothGattCharacteristic bluetoothGattCharacteristic, int i);

    void onCharacteristicWritten(BluetoothGattCharacteristic bluetoothGattCharacteristic, int i);

    void onDisconnect();

    void onServicesDiscovered(BluetoothGatt bluetoothGatt);
}
