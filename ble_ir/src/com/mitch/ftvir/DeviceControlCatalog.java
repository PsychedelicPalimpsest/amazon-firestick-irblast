package com.mitch.ftvir;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import com.amazon.tv.devicecontrol.api.v1.AutoParcelable;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Catalog via DeviceControl AIDL (uses DeviceControl's own MAP session).
 * Requires com.amazon.tv.devicecontrol.WRITE (signature|system) — Magisk priv-app.
 */
final class DeviceControlCatalog {
    private static final String TAG = "FtvIrDcCat";
    private static final String DC_PKG = "com.amazon.tv.devicecontrol";
    private static final String DC_SERVICE = DC_PKG + ".api.DeviceControlApiService";
    private static final String ACTION = "com.amazon.tv.devicecontrol.AIDL_SERVICE";
    private static final String DESCRIPTOR = "com.amazon.tv.devicecontrol.api.v1.IDeviceControlApi";
    private static final int TX_BRANDS = 25;
    private static final int TX_PROFILES = 27;
    private static final int TX_REGISTERED_SCREEN_PROVIDERS = 32;
    private static final int TX_SCREEN_STATE = 33;
    private static final int TX_PROFILES_BY_IDS = 56;
    private static final long TV_TYPE = 1L;
    /** Prefer HPD/HDMI link over Stick-internal display for external TV power. */
    private static final String[] PREFERRED_TV_PROVIDERS =
            new String[] {"HPD", "HDMI", "HDMI_ADVANCED", "HDMI_INT_SCREEN", "INTEGRAL_HDMI_SCREEN"};

    private DeviceControlCatalog() {}

    /**
     * Read-only TV/screen power from DeviceControl providers (HPD / HDMI / fused).
     * Does not send IR or CEC. Never invents state from our own blasts.
     */
    static JSONObject tvPower(Context context) throws Exception {
        return withBinder(
                context,
                new BinderFn() {
                    @Override
                    public JSONObject run(IBinder binder) throws Exception {
                        return readTvPowerDetailed(context, binder);
                    }
                });
    }

    static JSONObject brands(Context context) throws Exception {
        return withBinder(
                context,
                new BinderFn() {
                    @Override
                    public JSONObject run(IBinder binder) throws Exception {
                        Object list = transactLongs(context, binder, TX_BRANDS, TV_TYPE);
                        return toBrandsJson(list);
                    }
                });
    }

    static JSONObject profilesForBrand(Context context, final long brandId) throws Exception {
        return withBinder(
                context,
                new BinderFn() {
                    @Override
                    public JSONObject run(IBinder binder) throws Exception {
                        Object list =
                                transactLongs(context, binder, TX_PROFILES, brandId, TV_TYPE);
                        return toProfilesJson(list);
                    }
                });
    }

    static JSONObject profilesByIds(Context context, final long[] ids) throws Exception {
        return withBinder(
                context,
                new BinderFn() {
                    @Override
                    public JSONObject run(IBinder binder) throws Exception {
                        Object list = transactLongArray(context, binder, TX_PROFILES_BY_IDS, ids);
                        return toProfileDetailsJson(list);
                    }
                });
    }

    private interface BinderFn {
        JSONObject run(IBinder binder) throws Exception;
    }

    private static JSONObject withBinder(Context context, BinderFn fn) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<IBinder> ref = new AtomicReference<>();
        Intent intent = new Intent(ACTION);
        intent.setComponent(new ComponentName(DC_PKG, DC_SERVICE));
        ServiceConnection conn =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        ref.set(service);
                        latch.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {}
                };
        // bindService must be called with a Looper; IntentService worker has one.
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        boolean bound = context.getApplicationContext().bindService(
                intent, conn, Context.BIND_AUTO_CREATE);
        if (!bound) {
            throw new IllegalStateException(
                    "bindService failed (need devicecontrol.WRITE as Magisk priv-app?)");
        }
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("bindService timeout");
            }
            IBinder binder = ref.get();
            if (binder == null) {
                throw new IllegalStateException("null binder");
            }
            return fn.run(binder);
        } finally {
            try {
                context.getApplicationContext().unbindService(conn);
            } catch (Exception ignored) {
            }
        }
    }

    private static JSONObject readTvPowerDetailed(Context context, IBinder binder)
            throws Exception {
        JSONObject result = new JSONObject();
        result.put("source", "devicecontrol_screen");

        String[] registered = readRegisteredScreenProviders(binder);
        JSONArray regArr = new JSONArray();
        if (registered != null) {
            for (String p : registered) {
                regArr.put(p);
            }
        }
        result.put("providers", regArr);

        JSONObject byProvider = new JSONObject();
        // Probe preferred TV-link providers one-by-one (single-provider → weight 1.0)
        for (String name : PREFERRED_TV_PROVIDERS) {
            if (registered != null && registered.length > 0 && !contains(registered, name)) {
                continue;
            }
            String state = readScreenStateName(context, binder, new String[] {name});
            if (state != null) {
                byProvider.put(name, state);
            }
        }
        // Also sample INTERNAL_SCREEN so we can contrast Stick vs TV
        if (registered == null || contains(registered, "INTERNAL_SCREEN")) {
            String internal = readScreenStateName(context, binder, new String[] {"INTERNAL_SCREEN"});
            if (internal != null) {
                byProvider.put("INTERNAL_SCREEN", internal);
            }
        }
        // Fused aggregate (all providers)
        String fused = readScreenStateName(context, binder, new String[0]);
        result.put("raw", fused);
        result.put("by_provider", byProvider);

        // Prefer a decisive HPD/HDMI reading over UNKNOWN fused aggregate
        String chosen = null;
        String chosenProvider = null;
        for (String name : PREFERRED_TV_PROVIDERS) {
            if (!byProvider.has(name)) {
                continue;
            }
            String s = byProvider.optString(name, null);
            if ("ON".equals(s) || "OFF".equals(s)) {
                chosen = s;
                chosenProvider = name;
                break;
            }
        }
        if (chosen == null && ("ON".equals(fused) || "OFF".equals(fused))) {
            chosen = fused;
            chosenProvider = "ALL";
        }
        result.put("chosen_provider", chosenProvider == null ? JSONObject.NULL : chosenProvider);
        if ("ON".equals(chosen)) {
            result.put("is_on", true);
        } else if ("OFF".equals(chosen)) {
            result.put("is_on", false);
        } else {
            result.put("is_on", JSONObject.NULL);
        }
        Log.i(TAG, "tvPower fused=" + fused + " chosen=" + chosen + "@" + chosenProvider
                + " detail=" + byProvider);
        return result;
    }

    private static boolean contains(String[] arr, String needle) {
        for (String s : arr) {
            if (needle.equals(s)) {
                return true;
            }
        }
        return false;
    }

    private static String[] readRegisteredScreenProviders(IBinder binder) throws Exception {
        Parcel in = Parcel.obtain();
        Parcel out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(TX_REGISTERED_SCREEN_PROVIDERS, in, out, 0)) {
                return new String[0];
            }
            out.readException();
            return out.createStringArray();
        } finally {
            out.recycle();
            in.recycle();
        }
    }

    private static String readScreenStateName(Context context, IBinder binder, String[] providers)
            throws Exception {
        Parcel in = Parcel.obtain();
        Parcel out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESCRIPTOR);
            in.writeStringArray(providers);
            if (!binder.transact(TX_SCREEN_STATE, in, out, 0)) {
                return null;
            }
            out.readException();
            if (out.readInt() == 0) {
                return null;
            }
            Object raw = readSerializableWithDcLoader(context, out);
            if (raw == null) {
                return null;
            }
            Method m = findMethod(raw.getClass(), "name");
            if (m != null) {
                return String.valueOf(m.invoke(raw));
            }
            return String.valueOf(raw);
        } finally {
            out.recycle();
            in.recycle();
        }
    }

    private static Object transactLongs(Context context, IBinder binder, int code, long... longs)
            throws Exception {
        Parcel in = Parcel.obtain();
        Parcel out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESCRIPTOR);
            for (long v : longs) {
                in.writeLong(v);
            }
            if (!binder.transact(code, in, out, 0)) {
                throw new IllegalStateException("transact failed code=" + code);
            }
            out.readException();
            if (out.readInt() == 0) {
                return null;
            }
            return readSerializableWithDcLoader(context, out);
        } finally {
            out.recycle();
            in.recycle();
        }
    }

    private static Object transactLongArray(Context context, IBinder binder, int code, long[] ids)
            throws Exception {
        Parcel in = Parcel.obtain();
        Parcel out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESCRIPTOR);
            in.writeLongArray(ids);
            if (!binder.transact(code, in, out, 0)) {
                throw new IllegalStateException("transact failed code=" + code);
            }
            out.readException();
            if (out.readInt() == 0) {
                return null;
            }
            return readSerializableWithDcLoader(context, out);
        } finally {
            out.recycle();
            in.recycle();
        }
    }

    private static Object readSerializableWithDcLoader(Context context, Parcel out)
            throws Exception {
        AutoParcelable.sDeserializeClassLoader = dcLoader(context);
        try {
            AutoParcelable ap = AutoParcelable.CREATOR.createFromParcel(out);
            return ap != null ? ap.getSerializableField() : null;
        } finally {
            AutoParcelable.sDeserializeClassLoader = null;
        }
    }

    private static PathClassLoader dcLoader(Context context) {
        String apk = "/system/priv-app/com.amazon.tv.devicecontrol/com.amazon.tv.devicecontrol.apk";
        return new PathClassLoader(apk, context.getClassLoader());
    }

    private static JSONObject toBrandsJson(Object listObj) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray brands = new JSONArray();
        if (listObj instanceof List) {
            for (Object item : (List<?>) listObj) {
                brands.put(reflectBrand(item));
            }
        } else if (listObj != null) {
            Method m = findMethod(listObj.getClass(), "getBrands", "getSupportedBrands", "getList");
            if (m != null) {
                Object inner = m.invoke(listObj);
                if (inner instanceof List) {
                    for (Object item : (List<?>) inner) {
                        brands.put(reflectBrand(item));
                    }
                }
            } else {
                Log.w(TAG, "Unknown brands type: " + listObj.getClass().getName());
            }
        }
        out.put("brands", brands);
        return out;
    }

    private static JSONObject reflectBrand(Object item) throws Exception {
        JSONObject n = new JSONObject();
        n.put("id", reflectLong(item, "getId", "getBrandId", "id"));
        n.put("name", reflectString(item, "getName", "getBrandName", "name"));
        return n;
    }

    private static JSONObject toProfilesJson(Object listObj) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray profiles = new JSONArray();
        List<?> list = extractProfileList(listObj);
        if (list != null) {
            for (Object p : list) {
                profiles.put(summarizeProfile(p));
            }
        }
        out.put("profiles", profiles);
        return out;
    }

    private static JSONObject toProfileDetailsJson(Object listObj) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray profiles = new JSONArray();
        List<?> list = extractProfileList(listObj);
        if (list != null) {
            for (Object p : list) {
                profiles.put(detailProfile(p));
            }
        }
        out.put("profiles", profiles);
        return out;
    }

    private static List<?> extractProfileList(Object listObj) throws Exception {
        if (listObj instanceof List) {
            return (List<?>) listObj;
        }
        if (listObj == null) {
            return null;
        }
        Method m = findMethod(listObj.getClass(), "getProfiles", "getList");
        if (m != null) {
            Object inner = m.invoke(listObj);
            if (inner instanceof List) {
                return (List<?>) inner;
            }
        }
        Log.w(TAG, "Unknown profiles type: " + listObj.getClass().getName());
        return null;
    }

    private static JSONObject summarizeProfile(Object p) throws Exception {
        JSONObject n = new JSONObject();
        Object codeSet = reflectObj(p, "getCodeSet");
        long codesetId = reflectLong(codeSet, "getId");
        String name = reflectString(codeSet, "getName");
        n.put("profile_id", codesetId);
        n.put("codeset_id", codesetId);
        n.put("name", name != null ? name : ("#" + codesetId));
        JSONArray fns = new JSONArray();
        boolean discrete = false;
        Object codes = reflectObj(codeSet, "getIrCodes");
        if (codes instanceof List) {
            for (Object c : (List<?>) codes) {
                String fn = functionName(c);
                if (fn != null) {
                    fns.put(fn);
                    if ("POWER_ON".equals(fn) || "POWER_OFF".equals(fn)) {
                        discrete = true;
                    }
                }
            }
        }
        n.put("functions", fns);
        n.put("has_discrete_power", discrete);
        return n;
    }

    private static JSONObject detailProfile(Object p) throws Exception {
        JSONObject n = summarizeProfile(p);
        Object codeSet = reflectObj(p, "getCodeSet");
        JSONObject codes = new JSONObject();
        Object codeList = reflectObj(codeSet, "getIrCodes");
        if (codeList instanceof List) {
            for (Object c : (List<?>) codeList) {
                String fn = functionName(c);
                String pronto = reflectString(c, "getCode1");
                if (fn == null || pronto == null) {
                    continue;
                }
                JSONObject entry = new JSONObject();
                entry.put("code1", pronto);
                String code2 = reflectString(c, "getCode2");
                if (code2 != null) {
                    entry.put("code2", code2);
                }
                codes.put(fn, entry);
            }
        }
        n.put("codes", codes);
        int duty = (int) reflectLong(codeSet, "getDutyCycle");
        int blast = (int) reflectLong(codeSet, "getBlastCount");
        n.put("dutyCycle", duty > 0 ? duty : 33);
        n.put("blastCount", blast > 0 ? blast : 1);
        return n;
    }

    /** DeviceFunction enum → POWER_TOGGLE etc. */
    private static String functionName(Object irCode) throws Exception {
        Object df = reflectObj(irCode, "getDeviceFunction");
        if (df == null) {
            return null;
        }
        // Enum name()
        Method name = findMethod(df.getClass(), "name");
        if (name != null) {
            return String.valueOf(name.invoke(df));
        }
        return String.valueOf(df);
    }

    private static Method findMethod(Class<?> cl, String... names) {
        for (String name : names) {
            for (Method m : cl.getMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Object reflectObj(Object o, String... methods) throws Exception {
        if (o == null) {
            return null;
        }
        Method m = findMethod(o.getClass(), methods);
        return m != null ? m.invoke(o) : null;
    }

    private static long reflectLong(Object o, String... methods) throws Exception {
        Object v = reflectObj(o, methods);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return -1;
    }

    private static String reflectString(Object o, String... methods) throws Exception {
        Object v = reflectObj(o, methods);
        return v != null ? String.valueOf(v) : null;
    }
}
