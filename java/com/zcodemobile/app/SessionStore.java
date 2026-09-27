package com.zcodemobile.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Penyimpanan sesi tugas — padanan tabel `session` + `message` di ZCode Desktop
 * (di sini cukup JSON per sesi di filesDir/sessions/).
 */
public class SessionStore {

    public static File dir(android.content.Context c) {
        File d = new File(c.getFilesDir(), "sessions");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String currentId(android.content.Context c) {
        return Prefs.get(c, "session_current", "");
    }

    public static void setCurrent(android.content.Context c, String id) {
        Prefs.set(c, "session_current", id);
    }

    public static class Meta {
        public String id, title;
        public long updated;
        public Meta(String id, String title, long updated) {
            this.id = id; this.title = title; this.updated = updated;
        }
    }

    public static List<Meta> list(android.content.Context c) {
        List<Meta> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    out.add(new Meta(o.optString("id"), o.optString("title", "Tanpa judul"), o.optLong("updated")));
                } catch (Exception ignore) { }
            }
        }
        Collections.sort(out, new Comparator<Meta>() {
            @Override public int compare(Meta a, Meta b) { return Long.compare(b.updated, a.updated); }
        });
        return out;
    }

    /** Buat sesi baru dan jadikan aktif. */
    public static String create(android.content.Context c, String title) {
        String id = "sess_" + System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title == null || title.isEmpty() ? "Tugas baru" : title);
            o.put("created", System.currentTimeMillis());
            o.put("updated", System.currentTimeMillis());
            o.put("mode", Prefs.agentMode(c));
            o.put("items", new JSONArray());
            write(c, id, o);
        } catch (Exception ignore) { }
        setCurrent(c, id);
        return id;
    }

    public static void delete(android.content.Context c, String id) {
        new File(dir(c), id + ".json").delete();
        if (id.equals(currentId(c))) {
            List<Meta> rest = list(c);
            setCurrent(c, rest.isEmpty() ? "" : rest.get(0).id);
        }
    }

    /** Simpan isi chat + mode. Judul diperbarui bila diberikan (non-null). */
    public static void save(android.content.Context c, String id, JSONArray items, String mode, String newTitle) {
        if (id == null || id.isEmpty()) return;
        try {
            File f = new File(dir(c), id + ".json");
            JSONObject o = f.exists()
                    ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    : new JSONObject().put("id", id).put("created", System.currentTimeMillis());
            o.put("updated", System.currentTimeMillis());
            o.put("mode", mode);
            o.put("items", items);
            if (newTitle != null && !newTitle.isEmpty()) o.put("title", newTitle);
            write(c, id, o);
        } catch (Exception ignore) { }
    }

    public static JSONArray loadItems(android.content.Context c, String id) {
        try {
            File f = new File(dir(c), id + ".json");
            if (!f.exists()) return new JSONArray();
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return o.optJSONArray("items") == null ? new JSONArray() : o.optJSONArray("items");
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static String loadMode(android.content.Context c, String id) {
        try {
            File f = new File(dir(c), id + ".json");
            if (!f.exists()) return Prefs.MODE_BUILD;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .optString("mode", Prefs.MODE_BUILD);
        } catch (Exception e) {
            return Prefs.MODE_BUILD;
        }
    }

    private static void write(android.content.Context c, String id, JSONObject o) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(dir(c), id + ".json"));
            fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignore) { }
    }
}
