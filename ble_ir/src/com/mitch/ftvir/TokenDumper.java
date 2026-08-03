package com.mitch.ftvir;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import java.lang.reflect.Method;

/**
 * Run as DeviceControl uid (has GENERIC_IPC) via Magisk.
 * Prints TOKEN:&lt;value&gt; or ERROR:&lt;msg&gt; on stdout (no file I/O).
 */
public final class TokenDumper {
    private static final String DC_PKG = "com.amazon.tv.devicecontrol";

    public static void main(String[] args) {
        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
            Context ctx = createPackageContext(DC_PKG);

            Class<?> mapInitCl = Class.forName("com.amazon.identity.auth.device.api.MAPInit");
            Object mapInit = mapInitCl.getMethod("getInstance", Context.class).invoke(null, ctx);
            mapInitCl.getMethod("initialize").invoke(mapInit);

            Class<?> acctCl = Class.forName("com.amazon.identity.auth.device.api.MAPAccountManager");
            Object acctMgr = acctCl.getConstructor(Context.class).newInstance(ctx);
            String account = (String) acctCl.getMethod("getAccount").invoke(acctMgr);
            if (account == null || account.isEmpty()) {
                System.out.println("ERROR:No Amazon account");
                System.exit(2);
                return;
            }

            Class<?> tokenKeysCl = Class.forName("com.amazon.identity.auth.device.api.TokenKeys");
            String tokenKey = (String) tokenKeysCl
                    .getMethod("getAccessTokenKeyForPackage", String.class)
                    .invoke(null, DC_PKG);

            Class<?> tmCl = Class.forName("com.amazon.identity.auth.device.api.TokenManagement");
            Object tm = tmCl.getConstructor(Context.class).newInstance(ctx);
            Method getValue = null;
            for (Method m : tmCl.getMethods()) {
                if ("getValue".equals(m.getName()) && m.getParameterTypes().length == 4) {
                    getValue = m;
                    break;
                }
            }
            if (getValue == null) {
                System.out.println("ERROR:TokenManagement.getValue missing");
                System.exit(3);
                return;
            }
            Object token = getValue.invoke(tm, account, tokenKey, new Bundle(), Long.valueOf(10000L));
            if (token == null || String.valueOf(token).isEmpty()) {
                System.out.println("ERROR:Empty MAP token");
                System.exit(4);
                return;
            }
            System.out.println("TOKEN:" + token);
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("ERROR:" + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()));
            System.exit(1);
        }
    }

    private static Context createPackageContext(String pkg) throws Exception {
        Class<?> atCl = Class.forName("android.app.ActivityThread");
        Object thread = atCl.getMethod("systemMain").invoke(null);
        Context sys = (Context) atCl.getMethod("getSystemContext").invoke(thread);
        return sys.createPackageContext(pkg, 0);
    }

    private TokenDumper() {}
}
