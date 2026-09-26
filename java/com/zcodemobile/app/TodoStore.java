package com.zcodemobile.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Penyimpanan daftar tugas (JSON) — dipakai UI & tool todo_write. */
public class TodoStore {

    private static File file(Context c) {
        return new File(c.getFilesDir(), "todo.json");
    }

    public static JSONArray load(Context c) {
        try {
            File f = file(c);
            if (!f.exists()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void save(Context c, JSONArray items) {
        try {
            FileOutputStream fos = new FileOutputStream(file(c));
            fos.write(items.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignore) { }
    }

    public static void add(Context c, String title) {
        try {
            JSONArray arr = load(c);
            JSONObject o = new JSONObject().put("title", title).put("done", false);
            arr.put(o);
            save(c, arr);
        } catch (Exception ignore) { }
    }

    public static void toggle(Context c, int pos) {
        try {
            JSONArray arr = load(c);
            JSONObject o = arr.getJSONObject(pos);
            o.put("done", !o.optBoolean("done"));
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
}
