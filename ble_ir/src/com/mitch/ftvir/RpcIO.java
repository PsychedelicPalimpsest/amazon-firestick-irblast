package com.mitch.ftvir;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * File-based RPC outbox for ADB clients.
 *
 * Written under the app cache dir (Fire OS blocks app writes to /data/local/tmp).
 * HA/CLI reads via: adb shell su -c 'cat /data/data/com.mitch.ftvir/cache/...'
 */
final class RpcIO {
    static final String OUT_NAME = "ftvir_rpc_out.json";
    static final String STATUS_NAME = "ftvir_rpc_status.json";
    /** Documented absolute paths for clients (app uid cache). */
    static final String OUT_PATH = "/data/data/com.mitch.ftvir/cache/" + OUT_NAME;
    static final String STATUS_PATH = "/data/data/com.mitch.ftvir/cache/" + STATUS_NAME;
    private static final String TAG = "FtvIrRpc";
    private static File sCacheDir;

    private RpcIO() {}

    static void init(Context context) {
        sCacheDir = context.getCacheDir();
        if (sCacheDir != null && !sCacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            sCacheDir.mkdirs();
        }
    }

    private static File file(String name) {
        if (sCacheDir == null) {
            return new File("/data/data/com.mitch.ftvir/cache", name);
        }
        return new File(sCacheDir, name);
    }

    static String readFile(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        try {
            byte[] buf = new byte[Math.max(in.available(), 0)];
            int n = in.read(buf);
            return new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    static void write(File f, String body) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        // Best-effort; SELinux still requires su for shell to read app_data_file.
        //noinspection ResultOfMethodCallIgnored
        f.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        f.setWritable(true, true);
    }

    static void ok(JSONObject payload) {
        try {
            write(file(OUT_NAME), payload.toString());
            JSONObject st = new JSONObject();
            st.put("ok", true);
            st.put("error", JSONObject.NULL);
            write(file(STATUS_NAME), st.toString());
        } catch (Exception e) {
            Log.e(TAG, "write ok failed", e);
        }
    }

    static void fail(String error) {
        try {
            JSONObject st = new JSONObject();
            st.put("ok", false);
            st.put("error", error);
            write(file(STATUS_NAME), st.toString());
            JSONObject empty = new JSONObject();
            empty.put("error", error);
            write(file(OUT_NAME), empty.toString());
        } catch (Exception e) {
            Log.e(TAG, "write fail failed", e);
        }
    }
}
