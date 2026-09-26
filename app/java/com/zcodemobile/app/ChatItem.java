package com.zcodemobile.app;

import org.json.JSONObject;

/**
 * Satu item di daftar chat. Tipe:
 * 0 = pesan user, 1 = pesan assistant, 2 = tool card, 3 = kartu rencana (plan), 4 = catatan sistem.
 * Bisa di-serialisasi ke JSON agar sesi bertahan antar-restart (ala ZCode session store).
 */
public class ChatItem {
    public static final int TYPE_USER = 0;
    public static final int TYPE_ASSISTANT = 1;
    public static final int TYPE_TOOL = 2;
    public static final int TYPE_PLAN = 3;
    public static final int TYPE_NOTE = 4;

    // Status tool ala ZCode: pending / running / completed / error / denied
    public static final int ST_PENDING = 0;
    public static final int ST_RUNNING = 1;
    public static final int ST_OK = 2;
    public static final int ST_ERR = 3;
    public static final int ST_DENIED = 4;

    public int type;
    public String text = "";        // isi pesan / catatan / output tool / rencana
    public String toolName = "";    // nama tool (Read, Write, ...)
    public String toolDetail = "";  // detail singkat: path / query / url
    public int toolStatus = ST_RUNNING;
    public boolean expanded = false;
    public boolean planResolved = false;

    public ChatItem(int type) { this.type = type; }

    public JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("t", type)
                    .put("x", text)
                    .put("n", toolName)
                    .put("d", toolDetail)
                    .put("s", toolStatus)
                    .put("pr", planResolved);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static ChatItem fromJson(JSONObject o) {
        ChatItem it = new ChatItem(o.optInt("t", TYPE_ASSISTANT));
        it.text = o.optString("x", "");
        it.toolName = o.optString("n", "");
        it.toolDetail = o.optString("d", "");
        it.toolStatus = o.optInt("s", ST_OK);
        it.planResolved = o.optBoolean("pr", true);
        return it;
    }
}
