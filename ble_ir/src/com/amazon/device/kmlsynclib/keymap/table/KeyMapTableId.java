package com.amazon.device.kmlsynclib.keymap.table;

import android.util.Log;
import com.amazon.device.kmlsynclib.utils.ByteTool;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/* loaded from: classes.dex */
public class KeyMapTableId {
    private static final UUID NULL_ID_0 = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID NULL_ID_F = UUID.fromString("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF");
    byte[] mHash = new byte[0];
    UUID mIndex = NULL_ID_0;
    int mToggleBitState = 0;

    public KeyMapTableId digest(byte[] bytes) {
        try {
            this.mHash = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            Log.wtf(getClass().getName(), "Unable to hash incoming data");
        }
        return this;
    }

    public KeyMapTableId setUUID(String uuid) {
        this.mIndex = UUID.fromString(uuid);
        return this;
    }

    public KeyMapTableId setUUID(UUID id) {
        this.mIndex = id;
        return this;
    }

    public KeyMapTableId setToggleBitState(int state) {
        this.mToggleBitState = state;
        return this;
    }

    public byte[] getHash() {
        return this.mHash;
    }

    public UUID getUUID() {
        return this.mIndex;
    }

    public byte[] getIndexBytes() {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(this.mIndex.getMostSignificantBits());
        bb.putLong(this.mIndex.getLeastSignificantBits());
        return bb.array();
    }

    public byte[] getToggleBItState() {
        ByteBuffer bb = ByteBuffer.allocate(1);
        bb.put(ByteTool.i8Tob(this.mToggleBitState));
        return bb.array();
    }

    public boolean equals(Object obj) {
        if (!(obj instanceof KeyMapTableId)) {
            return false;
        }
        KeyMapTableId id = (KeyMapTableId) obj;
        return id.getUUID().equals(this.mIndex);
    }

    public int hashCode() {
        return Arrays.hashCode(this.mHash) ^ this.mIndex.hashCode();
    }

    public String toString() {
        return String.format("TableId[%s] : Hash[%s]", this.mIndex.toString().toUpperCase(), ByteTool.toHexString(this.mHash, 80));
    }

    public boolean isDefaultTable() {
        return this.mIndex.equals(NULL_ID_0) || this.mIndex.equals(NULL_ID_F);
    }
}
