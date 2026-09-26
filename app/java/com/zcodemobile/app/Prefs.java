package com.zcodemobile.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Penyimpanan preferensi (setelan) aplikasi. */
public class Prefs {
    private static final String FILE = "zcode_prefs";

    public static String get(Context c, String key, String def) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return p.getString(key, def);
    }

    public static void set(Context c, String key, String value) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        p.edit().putString(key, value).apply();
    }

    /** Tema: light (default) / dark / system. */
    public static String themeMode(Context c) {
        return get(c, "theme_mode", "light");
    }

    /* ============ Agent mode — 4 mode persis ZCode Desktop ============ */
    // build = "Tanya dulu" (default) | edit = "Ubah otomatis" | plan = "Mode rencana" | yolo = "Akses penuh"
    public static final String MODE_BUILD = "build";
    public static final String MODE_EDIT = "edit";
    public static final String MODE_PLAN = "plan";
    public static final String MODE_YOLO = "yolo";

    public static String agentMode(Context c) {
        String m = get(c, "agent_mode", MODE_BUILD);
        return (MODE_BUILD.equals(m) || MODE_EDIT.equals(m) || MODE_PLAN.equals(m) || MODE_YOLO.equals(m))
                ? m : MODE_BUILD;
    }

    /* ============================ Provider ============================ */

    public static String activeBaseUrl(Context c) {
        String type = get(c, "provider_type", "zai");
        if ("custom".equals(type)) {
            String u = get(c, "custom_base_url", "").trim();
            if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            return u;
        }
        return "https://api.z.ai/api/paas/v4";
    }

    public static String activeApiKey(Context c) {
        String type = get(c, "provider_type", "zai");
        return "custom".equals(type) ? get(c, "custom_api_key", "") : get(c, "zai_api_key", "");
    }

    public static String activeModel(Context c) {
        return get(c, "model_" + get(c, "provider_type", "zai"), defaultModel(c));
    }

    public static String defaultModel(Context c) {
        String type = get(c, "provider_type", "zai");
        return "custom".equals(type) ? "" : "glm-4.5-flash";
    }

    /** Model Z.ai hardcoded — gratis dulu (sesi user: model gratis diprioritaskan). */
    public static String[] zaiFreeModels() {
        return new String[]{"glm-4.5-flash", "glm-4-flash", "glm-4-flash-250414", "glm-4v-flash"};
    }

    public static String[] zaiPaidModels() {
        return new String[]{"glm-4.5-air", "glm-4.6", "glm-4.5", "glm-4.5v", "glm-4-plus", "glm-4-long"};
    }

    /** Nama tampil rapi: glm-4.5-flash -> GLM-4.5-Flash */
    public static String prettyModel(String id) {
        if (id == null || id.isEmpty()) return id;
        String[] parts = id.split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('-');
            if (p.matches("\\d+(\\.\\d+)?")) sb.append(p);
            else sb.append(p.substring(0, 1).toUpperCase()).append(p.substring(1));
        }
        return sb.toString();
    }
}
