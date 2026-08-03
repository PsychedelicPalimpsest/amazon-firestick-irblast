package com.amazon.tv.devicecontrol.api.v1;

import android.os.Parcel;
import android.os.Parcelable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;

/** Minimal DeviceControl AutoParcelable + ClassLoader-aware deserialize. */
public class AutoParcelable implements Parcelable {
    public static ClassLoader sDeserializeClassLoader;

    public static final Parcelable.Creator<AutoParcelable> CREATOR =
            new Parcelable.Creator<AutoParcelable>() {
                @Override
                public AutoParcelable createFromParcel(Parcel parcel) {
                    return new AutoParcelable(readSerializable(parcel, sDeserializeClassLoader));
                }

                @Override
                public AutoParcelable[] newArray(int i) {
                    return new AutoParcelable[i];
                }
            };

    private Serializable serializableField;

    public AutoParcelable(Serializable serializable) {
        this.serializableField = serializable;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        writeSerializable(parcel, this.serializableField);
    }

    public Serializable getSerializableField() {
        return serializableField;
    }

    /** Mirror Android Parcel.writeSerializable for API 22. */
    public static void writeSerializable(Parcel parcel, Serializable s) {
        if (s == null) {
            parcel.writeString(null);
            return;
        }
        parcel.writeString(s.getClass().getName());
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(s);
            oos.close();
            parcel.writeByteArray(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Serializable readSerializable(Parcel parcel, final ClassLoader cl) {
        String name = parcel.readString();
        if (name == null) {
            return null;
        }
        byte[] data = parcel.createByteArray();
        try {
            ObjectInputStream ois =
                    new ObjectInputStream(new ByteArrayInputStream(data)) {
                        @Override
                        protected Class<?> resolveClass(ObjectStreamClass desc)
                                throws java.io.IOException, ClassNotFoundException {
                            String n = desc.getName();
                            if (cl != null) {
                                try {
                                    return Class.forName(n, false, cl);
                                } catch (ClassNotFoundException ignored) {
                                }
                            }
                            return super.resolveClass(desc);
                        }
                    };
            try {
                return (Serializable) ois.readObject();
            } finally {
                ois.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("deserialize " + name + ": " + e.getMessage(), e);
        }
    }
}
