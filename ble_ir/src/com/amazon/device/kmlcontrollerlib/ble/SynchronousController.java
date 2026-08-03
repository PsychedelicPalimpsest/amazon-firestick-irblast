package com.amazon.device.kmlcontrollerlib.ble;

import java.util.UUID;

/* loaded from: classes.dex */
public interface SynchronousController {

    public interface OperationResultListener {
        void onResult(OperationResult operationResult);
    }

    void close();

    void open();

    OperationResult poll(long j);

    OperationResult syncEnableSubscription(UUID uuid, boolean z);

    OperationResult syncRead(UUID uuid);

    void syncSubscribe(UUID uuid, OperationResultListener operationResultListener);

    OperationResult syncWrite(UUID uuid, byte[] bArr);
}
