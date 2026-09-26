package com.zcodemobile.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Penyimpanan sesi chat (riwayat percakapan) — ala sidebar sesi ZCode Desktop. */
public class SessionStore {

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sessions");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static long newSession() { return System.currentTimeMillis(); }

    public static void save(Context c, long id, String title, JSONArray history) {
        if (history.length() == 0) return;
        try {
            JSONObject o = new JSONObject()
                    .put("id", id)
                    .put("title", title == null || title.isEmpty() ? "Tanpa judul" : title)
                    .put("updated", System.currentTimeMillis())
                    .put("messages", history);
            FileOutputStream fos = new FileOutputStream(new File(dir(c), id + ".json"));
            fos.write(o.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignore) { }
    }

    public static class Meta {
        public long id;
        public String title;
        public long updated;
    }

    public static List<Meta> list(Context c) {
        List<Meta> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                Meta m = new Meta();
                m.id = o.getLong("id");
                m.title = o.optString("title", "Tanpa judul");
                m.updated = o.optLong("updated", f.lastModified());
                out.add(m);
            } catch (Exception ignore) { }
        }
        return out;
    }

    public static JSONArray loadMessages(Context c, long id) {
        try {
            File f = new File(dir(c), id + ".json");
            if (!f.exists()) return new JSONArray();
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return o.optJSONArray("messages") == null ? new JSONArray() : o.optJSONArray("messages");
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void delete(Context c, long id) {
        new File(dir(c), id + ".json").delete();
    }

    public static void deleteAll(Context c) {
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}
