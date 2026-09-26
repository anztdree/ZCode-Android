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

    public static boolean isDarkTheme(Context c) {
        return "dark".equals(get(c, "theme", "light"));
    }

    /** Base URL aktif sesuai provider. */
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

    /** Daftar model hardcoded untuk Z.ai (gratis di atas). */
    public static String[] zaiModels() {
        return new String[]{
                "glm-4.5-flash",
                "glm-4-flash",
                "glm-4-flash-250414",
                "glm-4v-flash",
                "glm-4.5-air",
                "glm-4.6",
                "glm-4.5",
                "glm-4.5v",
                "glm-4-plus",
                "glm-4-long"
        };
    }
}
