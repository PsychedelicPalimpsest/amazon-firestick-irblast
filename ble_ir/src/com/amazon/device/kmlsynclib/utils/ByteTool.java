package com.amazon.device.kmlsynclib.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes.dex */
public final class ByteTool {
    public static byte[] i32Tob(int data) {
        ByteBuffer b = ByteBuffer.allocate(4);
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(data);
        return b.array();
    }

    public static byte[] i16Tob(int data) {
        ByteBuffer b = ByteBuffer.allocate(2);
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) data);
        return b.array();
    }

    public static byte[] i8Tob(int data) {
        ByteBuffer b = ByteBuffer.allocate(1);
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) data);
        return b.array();
    }

    public static short bToi16(byte[] in) {
        ByteBuffer b = ByteBuffer.allocate(2);
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.put(in);
        return b.getShort();
    }

    public static String toHexString(byte[] data, int rowWidth) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        if (data == null) {
            return "";
        }
        for (byte b : data) {
            builder.append(String.format("%02X", Byte.valueOf(b)));
            i++;
            if (i > rowWidth) {
                i = 0;
                builder.append("\n");
            }
        }
        return builder.toString();
    }

    public static byte[] concatBytes(byte[]... input) {
        int len = 0;
        for (byte[] b : input) {
            len += b.length;
        }
        ByteBuffer ret = ByteBuffer.allocate(len);
        for (byte[] b2 : input) {
            ret.put(b2);
        }
        return ret.array();
    }
}
