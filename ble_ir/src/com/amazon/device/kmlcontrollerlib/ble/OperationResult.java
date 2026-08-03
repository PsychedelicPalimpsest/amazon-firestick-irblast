package com.amazon.device.kmlcontrollerlib.ble;

/* loaded from: classes.dex */
public class OperationResult {
    public Status status;
    public byte[] value;

    public enum Status {
        OK("OK"),
        DSICONNECTED("Disconnected from gatt server."),
        BUSY("Device busy."),
        NOT_SUPPORTED("Operation not supported."),
        TIMEOUT("Device timeout."),
        BAD_STATE("Remote is in a bad state."),
        RETRY("Unabile to initiate operation"),
        MISC("Misc error.");

        private String mReason;

        Status(String reason) {
            this.mReason = reason;
        }

        public String getReason() {
            return this.mReason;
        }
    }

    public OperationResult(Status status) {
        this.status = status;
    }

    public boolean isOK() {
        return this.status == Status.OK;
    }

    public String toString() {
        Object[] objArr = new Object[2];
        objArr[0] = this.status.getReason();
        objArr[1] = Integer.valueOf(this.value != null ? this.value.length : 0);
        return String.format("OperationResult %s with data length of", objArr);
    }
}
