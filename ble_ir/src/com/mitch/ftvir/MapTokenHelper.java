package com.mitch.ftvir;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Fetch Amazon MAP access token.
 *
 * Direct MAP needs com.amazon.identity.permission.GENERIC_IPC (Amazon signature).
 * Fallback: Magisk su as DeviceControl uid + TokenDumper (stdout TOKEN:...).
 */
final class MapTokenHelper {
    private static final String TAG = "FtvIrMap";
    private static final String DC_PKG = "com.amazon.tv.devicecontrol";

    private MapTokenHelper() {}

    static String getAccessToken(Context context) throws Exception {
        try {
            return getAccessTokenDirect(context);
        } catch (Throwable t) {
            Log.w(TAG, "Direct MAP failed, trying DeviceControl uid dump: " + t.getMessage());
        }
        return getAccessTokenViaDeviceControlUid(context);
    }

    private static String getAccessTokenDirect(Context context) throws Exception {
        Class<?> mapInitCl = Class.forName("com.amazon.identity.auth.device.api.MAPInit");
        Object mapInit = mapInitCl.getMethod("getInstance", Context.class).invoke(null, context);
        mapInitCl.getMethod("initialize").invoke(mapInit);

        Class<?> acctCl = Class.forName("com.amazon.identity.auth.device.api.MAPAccountManager");
        Object acctMgr = acctCl.getConstructor(Context.class).newInstance(context);
        String account = (String) acctCl.getMethod("getAccount").invoke(acctMgr);
        if (account == null || account.isEmpty()) {
            throw new IllegalStateException("No Amazon account on device");
        }

        Class<?> tokenKeysCl = Class.forName("com.amazon.identity.auth.device.api.TokenKeys");
        Method keyForPkg = tokenKeysCl.getMethod("getAccessTokenKeyForPackage", String.class);
        String tokenKey = (String) keyForPkg.invoke(null, context.getPackageName());

        Class<?> tmCl = Class.forName("com.amazon.identity.auth.device.api.TokenManagement");
        Object tm = tmCl.getConstructor(Context.class).newInstance(context);
        Method getValue = findGetValue(tmCl);
        Object token = getValue.invoke(tm, account, tokenKey, null, Long.valueOf(8000L));
        if (token == null || String.valueOf(token).isEmpty()) {
            tokenKey = (String) keyForPkg.invoke(null, DC_PKG);
            token = getValue.invoke(tm, account, tokenKey, new Bundle(), Long.valueOf(8000L));
        }
        if (token == null || String.valueOf(token).isEmpty()) {
            throw new IllegalStateException("MAP returned empty token");
        }
        Log.i(TAG, "MAP token acquired (direct)");
        return String.valueOf(token);
    }

    private static String getAccessTokenViaDeviceControlUid(Context context) throws Exception {
        int dcUid = resolveDeviceControlUid(context);
        String apk = context.getApplicationInfo().sourceDir;
        String identityJar = "/system/framework/com.amazon.identity.auth.device.jar";
        String classpath = apk + ":" + identityJar;
        // Prefer the freshly pushed apk if present (dev); else installed sourceDir.
        String cmd = "APK=" + apk + "; "
                + "[ -f /data/local/tmp/ftvir.apk ] && APK=/data/local/tmp/ftvir.apk; "
                + "CLASSPATH=$APK:" + identityJar
                + " /system/bin/app_process /system/bin com.mitch.ftvir.TokenDumper";

        ProcessBuilder pb = new ProcessBuilder("su", String.valueOf(dcUid), "-c", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuilder log = new StringBuilder();
        String token = null;
        String err = null;
        String line;
        while ((line = reader.readLine()) != null) {
            log.append(line).append('\n');
            Log.i(TAG, "TokenDumper: " + line);
            if (line.startsWith("TOKEN:")) {
                token = line.substring("TOKEN:".length()).trim();
            } else if (line.startsWith("ERROR:")) {
                err = line.substring("ERROR:".length()).trim();
            }
        }
        int exit = p.waitFor();
        if (token != null && !token.isEmpty()) {
            Log.i(TAG, "MAP token acquired (DeviceControl uid)");
            return token;
        }
        throw new IllegalStateException(
                "TokenDumper failed exit=" + exit + " err=" + err + " log=" + log);
    }

    private static int resolveDeviceControlUid(Context context) throws Exception {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(DC_PKG, 0);
            return ai.uid;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("DeviceControl not installed", e);
        }
    }

    private static Method findGetValue(Class<?> tmCl) throws Exception {
        for (Method m : tmCl.getMethods()) {
            if ("getValue".equals(m.getName()) && m.getParameterTypes().length == 4) {
                return m;
            }
        }
        throw new IllegalStateException("TokenManagement.getValue not found");
    }
}
