package com.zcodemobile.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Penyimpanan preferensi (setelan) aplikasi — BYOK per-penyedia ala Kai 9000. */
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

    /* ==================== Penyedia model (BYOK hardcore) ==================== */

    /** ID penyedia aktif (zai/openrouter/groq/…/custom). Nilai tak dikenal → zai. */
    public static String providerType(Context c) {
        String t = get(c, "provider_type", "zai");
        return Providers.exists(t) ? t : "zai";
    }

    /** API key per penyedia: key_<id>; kompatibel dengan kunci lama zai_api_key/custom_api_key. */
    public static String apiKeyOf(Context c, String providerId) {
        String k = get(c, "key_" + providerId, "");
        if (!k.isEmpty()) return k;
        // migrasi versi ≤2.2.x
        if ("zai".equals(providerId)) return get(c, "zai_api_key", "");
        if ("custom".equals(providerId)) return get(c, "custom_api_key", "");
        return "";
    }

    /** Base URL per penyedia: url_<id> utk preset editable; kompatibel custom_base_url lama. */
    public static String baseUrlOf(Context c, String providerId) {
        Providers.P p = Providers.byId(providerId);
        String u = get(c, "url_" + providerId, "");
        if (!u.isEmpty()) return stripSlash(u);
        if (p.editableUrl && "custom".equals(providerId)) return stripSlash(get(c, "custom_base_url", ""));
        return stripSlash(p.baseUrl);
    }

    private static String stripSlash(String u) {
        if (u == null) return "";
        u = u.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public static void setApiKey(Context c, String providerId, String key) {
        set(c, "key_" + providerId, key == null ? "" : key.trim());
    }

    public static void setBaseUrl(Context c, String providerId, String url) {
        set(c, "url_" + providerId, url == null ? "" : url.trim());
    }

    /** Base URL penyedia AKTIF — dipakai LlmClient. */
    public static String activeBaseUrl(Context c) {
        return baseUrlOf(c, providerType(c));
    }

    /** API key penyedia AKTIF. */
    public static String activeApiKey(Context c) {
        return apiKeyOf(c, providerType(c));
    }

    /** Model aktif per penyedia: model_<id> (format ini sama sejak awal — aman). */
    public static String activeModel(Context c) {
        return get(c, "model_" + providerType(c), defaultModel(c));
    }

    public static String defaultModel(Context c) {
        return "zai".equals(providerType(c)) ? "glm-4.5-flash" : "";
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
            else if (p.length() > 1) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            else sb.append(p);
        }
        return sb.toString();
    }
}
