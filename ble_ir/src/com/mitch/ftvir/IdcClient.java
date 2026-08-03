package com.mitch.ftvir;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal Amazon IDC (IR catalog) HTTP client. TV deviceTypeId = 1. */
final class IdcClient {
    static final int TV_DEVICE_TYPE = 1;
    private static final String TAG = "FtvIrIdc";

    private final String baseUrl;
    private final String token;

    IdcClient(String region, String token) {
        this.token = token;
        this.baseUrl = baseForRegion(region);
    }

    static String baseForRegion(String region) {
        if (region == null) region = "na";
        switch (region.toLowerCase()) {
            case "eu":
                return "https://idc-service-oz-eu.amazon.com/";
            case "fe":
                return "https://idc-service-oz-fe.amazon.com/";
            case "na":
            default:
                return "https://idc-service-oz.amazon.com/";
        }
    }

    JSONObject getBrands() throws Exception {
        String raw = get("brands/" + TV_DEVICE_TYPE);
        return normalizeBrands(raw);
    }

    JSONObject getProfilesForBrand(long brandId) throws Exception {
        String raw = get("profiles/" + TV_DEVICE_TYPE + "/" + brandId);
        return normalizeProfiles(raw);
    }

    JSONObject getProfilesByIds(String idsCsv) throws Exception {
        String raw = get("profiles?ids=" + idsCsv);
        return normalizeProfileDetails(raw);
    }

    private String get(String path) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("x-amz-access-token", token);
        conn.setRequestProperty("User-Agent", "FtvIr/1.0");
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readAll(stream);
        Log.i(TAG, "GET " + path + " -> " + code + " (" + body.length() + " bytes)");
        if (code >= 400) {
            throw new IllegalStateException("IDC HTTP " + code + ": " + body);
        }
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    /** Normalize various Coral/JSON shapes into {brands:[{id,name}]} */
    private static JSONObject normalizeBrands(String raw) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray brands = new JSONArray();
        Object parsed = raw.trim().startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
        JSONArray src;
        if (parsed instanceof JSONArray) {
            src = (JSONArray) parsed;
        } else {
            JSONObject o = (JSONObject) parsed;
            if (o.has("brands")) src = o.getJSONArray("brands");
            else if (o.has("Brands")) src = o.getJSONArray("Brands");
            else src = new JSONArray();
        }
        for (int i = 0; i < src.length(); i++) {
            JSONObject b = src.getJSONObject(i);
            JSONObject n = new JSONObject();
            long id = b.optLong("id", b.optLong("brandId", b.optLong("BrandId", -1)));
            String name = b.optString("name", b.optString("brandName", b.optString("Name", "")));
            if (id < 0 || name.isEmpty()) continue;
            n.put("id", id);
            n.put("name", name);
            brands.put(n);
        }
        out.put("brands", brands);
        return out;
    }

    /** {profiles:[{profile_id,codeset_id,name,functions[],has_discrete_power}]} */
    private static JSONObject normalizeProfiles(String raw) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray profiles = new JSONArray();
        JSONArray src = extractProfileArray(raw);
        for (int i = 0; i < src.length(); i++) {
            JSONObject p = src.getJSONObject(i);
            JSONObject n = summarizeProfile(p);
            if (n != null) profiles.put(n);
        }
        out.put("profiles", profiles);
        return out;
    }

    /** Full codeset: {profiles:[{profile_id,codeset_id,brand,name,dutyCycle,blastCount,codes:{FN:{code1}}}]} */
    private static JSONObject normalizeProfileDetails(String raw) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray profiles = new JSONArray();
        JSONArray src = extractProfileArray(raw);
        for (int i = 0; i < src.length(); i++) {
            JSONObject detailed = detailProfile(src.getJSONObject(i));
            if (detailed != null) profiles.put(detailed);
        }
        out.put("profiles", profiles);
        return out;
    }

    private static JSONArray extractProfileArray(String raw) throws Exception {
        Object parsed = raw.trim().startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
        if (parsed instanceof JSONArray) return (JSONArray) parsed;
        JSONObject o = (JSONObject) parsed;
        if (o.has("irDeviceProfiles")) return o.getJSONArray("irDeviceProfiles");
        if (o.has("IrDeviceProfiles")) return o.getJSONArray("IrDeviceProfiles");
        if (o.has("profiles")) return o.getJSONArray("profiles");
        if (o.has("codeSets") || o.has("codeSet")) {
            JSONArray one = new JSONArray();
            one.put(o);
            return one;
        }
        return new JSONArray();
    }

    private static JSONObject summarizeProfile(JSONObject p) throws Exception {
        JSONObject cs = p.optJSONObject("codeSet");
        if (cs == null) cs = p.optJSONObject("CodeSet");
        if (cs == null) cs = p;
        long profileId = firstLong(cs, "id", "profileId", "profile_id");
        long codesetId = firstLong(cs, "codeSetId", "codeset_id", "id");
        if (profileId < 0 && codesetId >= 0) profileId = codesetId;
        String name = cs.optString("name", cs.optString("Name", "Code Group"));
        JSONArray functions = new JSONArray();
        boolean discrete = false;
        JSONObject codes = cs.optJSONObject("codes");
        if (codes == null) codes = extractCodesMap(cs);
        if (codes != null) {
            JSONArray names = codes.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String fn = names.getString(i);
                    functions.put(fn);
                    if ("POWER_ON".equals(fn) || "POWER_OFF".equals(fn)) discrete = true;
                }
            }
        }
        // function list may be separate
        JSONArray fl = cs.optJSONArray("functions");
        if (fl != null && functions.length() == 0) {
            for (int i = 0; i < fl.length(); i++) {
                Object item = fl.get(i);
                String fn = item instanceof String ? (String) item : ((JSONObject) item).optString("name", "");
                if (!fn.isEmpty()) {
                    functions.put(fn);
                    if ("POWER_ON".equals(fn) || "POWER_OFF".equals(fn)) discrete = true;
                }
            }
        }
        JSONObject n = new JSONObject();
        n.put("profile_id", profileId);
        n.put("codeset_id", codesetId > 0 ? codesetId : profileId);
        n.put("name", name);
        n.put("functions", functions);
        n.put("has_discrete_power", discrete);
        return n;
    }

    private static JSONObject detailProfile(JSONObject p) throws Exception {
        JSONObject summary = summarizeProfile(p);
        if (summary == null) return null;
        JSONObject cs = p.optJSONObject("codeSet");
        if (cs == null) cs = p.optJSONObject("CodeSet");
        if (cs == null) cs = p;
        JSONObject codes = cs.optJSONObject("codes");
        if (codes == null) codes = extractCodesMap(cs);
        JSONObject outCodes = new JSONObject();
        if (codes != null) {
            JSONArray names = codes.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String fn = names.getString(i);
                    Object val = codes.get(fn);
                    String code1 = "";
                    if (val instanceof String) {
                        code1 = (String) val;
                    } else if (val instanceof JSONObject) {
                        JSONObject c = (JSONObject) val;
                        code1 = c.optString("code1", c.optString("Code1", c.optString("pronto", "")));
                    }
                    if (!code1.isEmpty()) {
                        JSONObject entry = new JSONObject();
                        entry.put("code1", code1);
                        outCodes.put(fn, entry);
                    }
                }
            }
        }
        summary.put("brand", cs.optString("brand", p.optString("brand", "")));
        summary.put("dutyCycle", cs.optInt("dutyCycle", cs.optInt("DutyCycle", 33)));
        summary.put("blastCount", cs.optInt("blastCount", cs.optInt("BlastCount", 1)));
        summary.put("codes", outCodes);
        return summary;
    }

    private static JSONObject extractCodesMap(JSONObject cs) {
        // Some payloads nest irCodes / functions as list of {name, code1}
        JSONArray list = cs.optJSONArray("irCodes");
        if (list == null) list = cs.optJSONArray("IrCodes");
        if (list == null) return null;
        try {
            JSONObject map = new JSONObject();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                String fn = item.optString("name", item.optString("function", ""));
                String code1 = item.optString("code1", item.optString("pronto", ""));
                if (!fn.isEmpty() && !code1.isEmpty()) {
                    JSONObject e = new JSONObject();
                    e.put("code1", code1);
                    map.put(fn, e);
                }
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    private static long firstLong(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                long v = o.optLong(k, -1);
                if (v >= 0) return v;
            }
        }
        return -1;
    }
}
