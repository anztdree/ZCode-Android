package com.zcodemobile.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

/** Registry & eksekutor tools agent. Semua path dibatasi di workspace. */
public class Tools {

    public static final int MAX_TOOL_ROUNDS = 12;

    private final File workspace;
    private final android.content.Context appCtx;

    public Tools(android.content.Context ctx, File workspace) {
        this.appCtx = ctx.getApplicationContext();
        this.workspace = workspace;
    }

    public File getWorkspace() { return workspace; }

    /* ---------------- Definisi tools (format OpenAI functions) ---------------- */

    public JSONArray definitions() {
        JSONArray arr = new JSONArray();
        arr.put(tool("read_file", "Baca isi berkas teks di workspace",
                str("path", "Path relatif berkas, mis: src/app.js")));
        arr.put(tool("write_file", "Tulis/buat berkas (menimpa isi lama)",
                str("path", "Path relatif berkas"), str("content", "Isi lengkap berkas")));
        arr.put(tool("edit_file", "Edit berkas: ganti potongan teks lama dengan baru",
                str("path", "Path relatif berkas"), str("old_text", "Teks lama yang dicari"),
                str("new_text", "Teks pengganti")));
        arr.put(tool("list_files", "Daftar isi folder di workspace",
                str("dir", "Path folder relatif, kosongkan untuk root")));
        arr.put(tool("grep", "Cari teks/pattern di semua berkas workspace",
                str("pattern", "Teks yang dicari (case-insensitive)")));
        arr.put(tool("delete_path", "Hapus berkas atau folder (rekursif)",
                str("path", "Path relatif yang dihapus")));
        arr.put(tool("todo_write", "Simpan/ubah daftar tugas rencana kerja",
                arr_("items", "Daftar tugas", str("title", "Judul tugas"),
                        bool("done", "Selesai?"))));
        arr.put(tool("web_fetch", "Ambil isi halaman web (teks) dari URL",
                str("url", "URL lengkap https://…")));
        return arr;
    }

    private JSONObject tool(String name, String desc, JSONObject... props) {
        try {
            JSONObject p = new JSONObject();
            for (JSONObject o : props) p.put(o.getString("name"), o);
            JSONObject fn = new JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put("parameters", new JSONObject()
                            .put("type", "object")
                            .put("properties", p)
                            .put("required", new JSONArray()));
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject str(String name, String desc) {
        try { return new JSONObject().put("name", name).put("type", "string").put("description", desc); }
        catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject bool(String name, String desc) {
        try { return new JSONObject().put("name", name).put("type", "boolean").put("description", desc); }
        catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject arr_(String name, String desc, JSONObject... items) {
        try {
            JSONObject itemProps = new JSONObject();
            for (JSONObject o : items) itemProps.put(o.getString("name"), o);
            return new JSONObject().put("name", name).put("type", "array").put("description", desc)
                    .put("items", new JSONObject().put("type", "object").put("properties", itemProps));
        } catch (Exception e) { return new JSONObject(); }
    }

    /* ---------------- Keamanan path ---------------- */

    private File resolve(String rel) {
        if (rel == null) rel = "";
        rel = rel.trim();
        if (rel.startsWith("/")) rel = rel.substring(1);
        File f = new File(workspace, rel);
        try {
            String cw = workspace.getCanonicalPath();
            String fc = f.getCanonicalPath();
            if (!fc.equals(cw) && !fc.startsWith(cw + File.separator)) return null;
        } catch (IOException e) { return null; }
        return f;
    }

    /* ---------------- Eksekusi tool ---------------- */

    public String execute(String name, JSONObject args) {
        try {
            switch (name) {
                case "read_file": return doRead(args);
                case "write_file": return doWrite(args);
                case "edit_file": return doEdit(args);
                case "list_files": return doList(args);
                case "grep": return doGrep(args);
                case "delete_path": return doDelete(args);
                case "todo_write": return doTodo(args, appCtx);
                case "web_fetch": return doWebFetch(args);
                default: return "Error: tool tidak dikenal: " + name;
            }
        } catch (Exception e) {
            return "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    private String doRead(JSONObject a) throws Exception {
        File f = resolve(a.optString("path"));
        if (f == null || !f.isFile()) return "Error: berkas tidak ditemukan: " + a.optString("path");
        byte[] bytes = Files.readAllBytes(f.toPath());
        if (bytes.length > 200_000) return "Error: berkas terlalu besar (" + bytes.length + " byte)";
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String doWrite(JSONObject a) throws Exception {
        File f = resolve(a.optString("path"));
        if (f == null) return "Error: path di luar workspace";
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), a.optString("content", "").getBytes(StandardCharsets.UTF_8));
        return "OK — " + f.getPath().replace(workspace.getPath(), ".") + " (" + f.length() + " byte)";
    }

    private String doEdit(JSONObject a) throws Exception {
        File f = resolve(a.optString("path"));
        if (f == null || !f.isFile()) return "Error: berkas tidak ditemukan: " + a.optString("path");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String oldText = a.optString("old_text", "");
        String newText = a.optString("new_text", "");
        if (oldText.isEmpty()) return "Error: old_text kosong";
        if (!src.contains(oldText)) return "Error: old_text tidak ditemukan di berkas";
        src = src.replace(oldText, newText);
        Files.write(f.toPath(), src.getBytes(StandardCharsets.UTF_8));
        return "OK — berkas diedit";
    }

    private String doList(JSONObject a) throws Exception {
        File d = resolve(a.optString("dir", ""));
        if (d == null || !d.isDirectory()) return "Error: folder tidak ditemukan: " + a.optString("dir", "");
        File[] items = d.listFiles();
        if (items == null || items.length == 0) return "(folder kosong)";
        Arrays.sort(items, (x, y) -> {
            if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
            return x.getName().compareToIgnoreCase(y.getName());
        });
        StringBuilder sb = new StringBuilder();
        for (File f : items) {
            sb.append(f.isDirectory() ? "[DIR]  " : "       ")
              .append(f.getName())
              .append(f.isFile() ? "  (" + f.length() + "b)" : "")
              .append('\n');
        }
        return sb.toString();
    }

    private String doGrep(JSONObject a) throws Exception {
        String pat = a.optString("pattern", "").toLowerCase();
        if (pat.isEmpty()) return "Error: pattern kosong";
        Path root = workspace.toPath();
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 8)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains("/.git"))
                .filter(p -> { try { return Files.size(p) < 1_000_000; } catch (Exception e) { return false; } })
                .forEach(p -> {
                    if (hits.size() >= 40) return;
                    try {
                        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).toLowerCase().contains(pat)) {
                                String rel = root.relativize(p).toString();
                                hits.add(rel + ":" + (i + 1) + ": " + lines.get(i).trim());
                                if (hits.size() >= 40) return;
                            }
                        }
                    } catch (Exception ignore) { }
                });
        }
        if (hits.isEmpty()) return "(tidak ada hasil untuk: " + pat + ")";
        StringBuilder sb = new StringBuilder();
        for (String h : hits) sb.append(h).append('\n');
        return sb.toString();
    }

    private String doDelete(JSONObject a) throws Exception {
        File f = resolve(a.optString("path"));
        if (f == null || !f.exists()) return "Error: path tidak ditemukan";
        if (f.getCanonicalPath().equals(workspace.getCanonicalPath()))
            return "Error: tidak boleh menghapus root workspace";
        if (f.isDirectory()) {
            Files.walk(f.toPath())
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (Exception ignore) {} });
            return "OK — folder dihapus";
        }
        Files.delete(f.toPath());
        return "OK — berkas dihapus";
    }

    private String doTodo(JSONObject a, Context ctx) {
        JSONArray items = a.optJSONArray("items");
        if (items == null) return "Error: items wajib array";
        TodoStore.save(ctx, items);
        return "OK — " + items.length() + " tugas tersimpan";
    }

    private String doWebFetch(JSONObject a) {
        String url = a.optString("url", "").trim();
        if (url.isEmpty()) return "Error: url kosong";
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "Error: url harus http(s)";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (ZCodeMobile)");
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return "Error: HTTP " + code;
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // buang script/style/tag
            html = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                       .replaceAll("(?s)<[^>]+>", " ")
                       .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                       .replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&quot;", "\"")
                       .replaceAll("\\s+", " ").trim();
            if (html.length() > 4000) html = html.substring(0, 4000) + "…(dipotong)";
            return "HTTP " + code + " — isi:\n" + html;
        } catch (Exception e) {
            return "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
