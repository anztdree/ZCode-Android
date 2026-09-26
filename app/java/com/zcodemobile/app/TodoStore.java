package com.zcodemobile.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Penyimpanan todo — format PERSIS ZCode Desktop:
 * {content, status: "pending"|"in_progress"|"completed", priority: "high"|"medium"|"low"}
 */
public class TodoStore {

    private static File file(Context c) {
        return new File(c.getFilesDir(), "todo.json");
    }

    public static JSONArray load(Context c) {
        try {
            File f = file(c);
            if (!f.exists()) return new JSONArray();
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return migrate(arr);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Migrasi format lama {title, done} → format ZCode {content, status, priority}. */
    private static JSONArray migrate(JSONArray arr) {
        boolean need = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.has("title") && !o.has("content")) { need = true; break; }
        }
        if (!need) return arr;
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            try {
                out.put(new JSONObject()
                        .put("content", o.optString("title", o.optString("content", "")))
                        .put("status", o.optBoolean("done") ? "completed" : "pending")
                        .put("priority", "medium"));
            } catch (Exception ignore) { }
        }
        return out;
    }

    public static void save(Context c, JSONArray items) {
        try {
            FileOutputStream fos = new FileOutputStream(file(c));
            fos.write(items.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignore) { }
    }

    /** Tulis daftar penuh dari tool TodoWrite; validasi ala ZCode. */
    public static String writeAll(Context c, JSONArray todos) {
        if (todos == null || todos.length() == 0) return "Error: todos tidak boleh kosong";
        JSONArray out = new JSONArray();
        int inProg = 0;
        try {
            for (int i = 0; i < todos.length(); i++) {
                JSONObject t = todos.getJSONObject(i);
                String content = t.optString("content", "").trim();
                if (content.isEmpty()) return "Error: content wajib";
                String status = t.optString("status", "pending");
                if (!"pending".equals(status) && !"in_progress".equals(status) && !"completed".equals(status))
                    status = "pending";
                if ("in_progress".equals(status)) inProg++;
                String priority = t.optString("priority", "medium");
                if (!"high".equals(priority) && !"medium".equals(priority) && !"low".equals(priority))
                    priority = "medium";
                out.put(new JSONObject().put("content", content).put("status", status).put("priority", priority));
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
        if (inProg > 1) return "Error: maksimal satu item boleh in_progress";
        save(c, out);
        return summary(out);
    }

    public static String summary(JSONArray arr) {
        int total = arr.length(), done = 0, inProg = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String s = o.optString("status");
            if ("completed".equals(s)) done++;
            else if ("in_progress".equals(s)) inProg++;
        }
        return "Todo diperbarui: " + total + " item, " + inProg + " berjalan, " + done + " selesai";
    }

    public static void add(Context c, String content) {
        try {
            JSONArray arr = load(c);
            arr.put(new JSONObject().put("content", content).put("status", "pending").put("priority", "medium"));
            save(c, arr);
        } catch (Exception ignore) { }
    }

    /** Set status item pos ke-n. */
    public static void setStatus(Context c, int pos, String status) {
        try {
            JSONArray arr = load(c);
            JSONObject o = arr.getJSONObject(pos);
            o.put("status", status);
            save(c, arr);
        } catch (Exception ignore) { }
    }

    public static void cycle(Context c, int pos) {
        try {
            JSONArray arr = load(c);
            JSONObject o = arr.getJSONObject(pos);
            String s = o.optString("status", "pending");
            o.put("status", "pending".equals(s) ? "in_progress" : ("in_progress".equals(s) ? "completed" : "pending"));
            save(c, arr);
        } catch (Exception ignore) { }
    }

    public static void remove(Context c, int pos) {
        try {
            JSONArray src = load(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < src.length(); i++) if (i != pos) out.put(src.getJSONObject(i));
            save(c, out);
        } catch (Exception ignore) { }
    }

    public static void clearDone(Context c) {
        try {
            JSONArray src = load(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.getJSONObject(i);
                if (!"completed".equals(o.optString("status"))) out.put(o);
            }
            save(c, out);
        } catch (Exception ignore) { }
    }
}
