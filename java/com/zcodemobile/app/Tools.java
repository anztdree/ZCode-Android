package com.zcodemobile.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Registry & eksekutor tools agent — nama + perilaku mengikuti ZCode Desktop:
 * Bash, Read, Write, Edit, Glob, Grep, Delete, WebFetch, WebSearch, TodoWrite,
 * TodoRead, AskUserQuestion, EnterPlanMode, ExitPlanMode. Semua path dibatasi
 * di workspace.
 */
public class Tools {

    /** Jembatan ke UI (dialog) — dipasang oleh AgentEngine/MainActivity. */
    public interface UiBridge {
        /** Tampilkan dialog pertanyaan; kembalikan jawaban terformat, atau null jika dibatalkan. */
        String askUser(JSONArray questions);
        /** Ajukan rencana (ExitPlanMode); kembalikan "approved" / "rejected". */
        String submitPlan(String plan);
        /** Minta izin sebelum tool berbahaya; true = diizinkan. */
        boolean permission(String toolName, String detail);
    }

    public interface ModeHook { void onModeChanged(String newMode); }

    private final File workspace;
    private final android.content.Context appCtx;
    private final Set<String> readPaths = new HashSet<>();
    public UiBridge bridge;
    public ModeHook modeHook;
    public String mode = Prefs.MODE_BUILD;

    public Tools(Context ctx, File workspace) {
        this.appCtx = ctx.getApplicationContext();
        this.workspace = workspace;
    }

    public File getWorkspace() { return workspace; }

    private static final int MAX_TOOL_ROUNDS = 30;

    public static int maxRounds() { return MAX_TOOL_ROUNDS; }

    /* ================= Definisi tools (format OpenAI functions) ================= */

    public JSONArray definitions() {
        JSONArray arr = new JSONArray();
        try {
            arr.put(tool("Read", "Reads a file from the local filesystem. Reads up to 2000 lines by default, in cat -n format. You must Read a file before editing it.",
                    str("file_path", "Path file relatif workspace, mis: src/app.js"),
                    num("offset", "Nomor baris awal (opsional)"),
                    num("limit", "Jumlah baris maksimum (opsional)")));
            arr.put(tool("Write", "Writes a file to the local filesystem, overwriting if it exists.",
                    str("file_path", "Path file relatif workspace"),
                    str("content", "Isi lengkap file")));
            arr.put(tool("Edit", "Performs exact string replacement in a file. old_string must match exactly once unless replace_all is true. You must Read the file before editing, or the call will fail.",
                    str("file_path", "Path file relatif workspace"),
                    str("old_string", "Teks lama yang diganti"),
                    str("new_string", "Teks pengganti"),
                    bool("replace_all", "Ganti semua kemunculan (default false)")));
            arr.put(tool("Bash", "Executes a given sh command in the workspace shell and returns its output. The shell is persistent: cd and variables survive between calls. Available binaries are Android toybox (ls, cat, grep, sed, awk, find, df, du, ps, tar, gzip, ping, wget, sh) — git, curl, python and package managers do NOT exist. Interactive commands (top, vim) and servers will block until timeout — avoid them.",
                    str("command", "Perintah sh yang dijalankan, mis: ls -la src/"),
                    num("timeout", "Batas waktu milidetik (default 120000, maksimum 600000)")));
            arr.put(tool("Glob", "Fast file pattern matching, e.g. \"**/*.js\" or \"src/**/*.ts\". Sorted by modification time.",
                    str("pattern", "Pola glob, mis: **/*.html"),
                    str("path", "Folder awal relatif (opsional)")));
            arr.put(tool("Grep", "Content search across workspace files. Prefer this over reading many files. Returns path:line matches.",
                    str("pattern", "Teks yang dicari"),
                    str("glob", "Filter nama file, mis: *.js (opsional)"),
                    bool("ignore_case", "Pencarian tanpa peduli huruf besar-kecil (default true)"),
                    num("head_limit", "Batas jumlah hasil (default 50)")));
            arr.put(tool("Delete", "Deletes a file or directory (recursive). Destructive — permission will be requested.",
                    str("path", "Path relatif yang dihapus")));
            arr.put(tool("WebFetch", "Fetches a URL, converts the page to readable text, and returns it. Use for documentation and references.",
                    str("url", "URL lengkap https://…")));
            arr.put(tool("WebSearch", "Search the web. Returns result blocks with titles, URLs and snippets.",
                    str("query", "Kata kunci pencarian (min 2 karakter)")));
            arr.put(tool("TodoWrite", "Create and update a task list for the current session. The list is rendered to the user as your working plan. At most one item may be in_progress at a time; send the full list each call — it replaces the previous one.",
                    arrOf("todos", "Daftar todo lengkap",
                            str("content", "Isi tugas"),
                            str("status", "pending | in_progress | completed"),
                            str("priority", "high | medium | low"))));
            arr.put(tool("TodoRead", "Read the current session todo list."));
            arr.put(tool("AskUserQuestion", "Use this tool only when you are blocked on a decision that is genuinely the user's to make. Ask 1-4 questions, each with 2-4 clear options.",
                    arrOf("questions", "Daftar pertanyaan",
                            str("question", "Pertanyaan lengkap"),
                            str("header", "Judul singkat maks 12 karakter"),
                            arrOf("options", "2-4 pilihan jawaban",
                                    str("label", "Label singkat pilihan"),
                                    str("description", "Penjelasan pilihan")))));
            arr.put(tool("EnterPlanMode", "Use this tool proactively when you're about to start a non-trivial implementation task: it lets you explore and design before writing any code."));
            arr.put(tool("ExitPlanMode", "Use this tool when you are in plan mode and have finished writing your plan, ready for user approval. The plan parameter is REQUIRED.",
                    str("plan", "Rencana implementasi lengkap (markdown)")));
        } catch (Exception ignore) { }
        return arr;
    }

    private JSONObject tool(String name, String desc, Object... props) throws Exception {
        JSONObject props_ = new JSONObject();
        JSONArray req = new JSONArray();
        for (Object p : props) {
            if (p instanceof JSONObject) {
                JSONObject o = (JSONObject) p;
                props_.put(o.getString("n"), o.getJSONObject("s"));
                if (o.optBoolean("r")) req.put(o.getString("n"));
            } else if (p instanceof String && name.isEmpty()) {
                // tak terpakai
            }
        }
        return new JSONObject()
                .put("type", "function")
                .put("function", new JSONObject()
                        .put("name", name)
                        .put("description", desc)
                        .put("parameters", new JSONObject()
                                .put("type", "object")
                                .put("properties", props_)
                                .put("required", req)));
    }

    private JSONObject str(String n, String d) throws Exception {
        return new JSONObject().put("n", n).put("r", true).put("s",
                new JSONObject().put("type", "string").put("description", d));
    }

    private JSONObject num(String n, String d) throws Exception {
        return new JSONObject().put("n", n).put("r", false).put("s",
                new JSONObject().put("type", "number").put("description", d));
    }

    private JSONObject bool(String n, String d) throws Exception {
        return new JSONObject().put("n", n).put("r", false).put("s",
                new JSONObject().put("type", "boolean").put("description", d));
    }

    private JSONObject arrOf(String n, String d, Object... itemProps) throws Exception {
        JSONObject itemProps_ = new JSONObject();
        for (Object p : itemProps) {
            JSONObject o = (JSONObject) p;
            itemProps_.put(o.getString("n"), o.getJSONObject("s"));
        }
        return new JSONObject().put("n", n).put("r", true).put("s",
                new JSONObject().put("type", "array").put("description", d)
                        .put("items", new JSONObject().put("type", "object").put("properties", itemProps_)));
    }

    /* ============================ Eksekusi ============================ */

    /** Eksekusi tool; kembalikan hasil (teks) untuk pesan role:tool. */
    public String execute(String name, JSONObject args) {
        try {
            if (args == null) args = new JSONObject();
            switch (name) {
                case "Read": return tRead(args);
                case "Bash": return tBash(args);
                case "Write": return tWrite(args);
                case "Edit": return tEdit(args);
                case "Glob": return tGlob(args);
                case "Grep": return tGrep(args);
                case "Delete": return tDelete(args);
                case "WebFetch": return tWebFetch(args);
                case "WebSearch": return tWebSearch(args);
                case "TodoWrite": return tTodoWrite(args);
                case "TodoRead": return TodoStore.load(appCtx).toString(2);
                case "AskUserQuestion": return tAskUser(args);
                case "EnterPlanMode": return tEnterPlan();
                case "ExitPlanMode": return tExitPlan(args);
                default: return "Error: tool tidak dikenal: " + name;
            }
        } catch (Exception e) {
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Apakah tool ini read-only (boleh di plan mode)? */
    public static boolean isReadOnly(String name) {
        return "Read".equals(name) || "Glob".equals(name) || "Grep".equals(name)
                || "WebFetch".equals(name) || "WebSearch".equals(name) || "TodoRead".equals(name)
                || "TodoWrite".equals(name) || "AskUserQuestion".equals(name)
                || "EnterPlanMode".equals(name);
    }

    private File resolve(String p) {
        if (p == null || p.trim().isEmpty()) return workspace;
        File f = new File(p);
        File abs = f.isAbsolute() ? f : new File(workspace, p);
        try {
            String cw = workspace.getCanonicalPath();
            String ca = abs.getCanonicalPath();
            if (!ca.equals(cw) && !ca.startsWith(cw + File.separator)) return null;
        } catch (Exception e) { return null; }
        return abs;
    }

    /* --------------------------------- Read --------------------------------- */

    private String tRead(JSONObject a) throws Exception {
        String path = a.getString("file_path");
        File f = resolve(path);
        if (f == null) return "Error: path di luar workspace";
        if (!f.exists() || f.isDirectory()) return "Error: file tidak ditemukan: " + path;
        int offset = a.optInt("offset", 1);
        int limit = a.optInt("limit", 2000);
        if (limit > 2000) limit = 2000;
        StringBuilder sb = new StringBuilder();
        int n = 0, lineno = 0, total = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        String ln;
        while ((ln = r.readLine()) != null) {
            total++;
            lineno++;
            if (lineno < offset) continue;
            if (n >= limit) break;
            sb.append(String.format("%6d\t%s%n", lineno, ln));
            n++;
        }
        r.close();
        readPaths.add(f.getAbsolutePath());
        if (total == 0) return "(file kosong)";
        sb.append(String.format("%n(total %d baris)", total));
        return sb.length() > 30000 ? sb.substring(0, 30000) + "… (dipotong)" : sb.toString();
    }

    /* --------------------------------- Bash --------------------------------- */

    private String tBash(JSONObject a) throws Exception {
        String command = a.getString("command");
        long timeout = a.optLong("timeout", 120_000);
        if (timeout < 1_000) timeout = 1_000;
        if (timeout > 600_000) timeout = 600_000; // ala ZCode Desktop: 12e4 default / 6e5 maks
        if (!checkPermission("Bash", "menjalankan perintah: " + command))
            return "User declined this command. Adjust your approach — don't retry verbatim.";
        if (shellAgent == null) shellAgent = new ShellSession(workspace);
        ShellSession.Result r = shellAgent.run(command, timeout);
        String head = r.ok() ? "" : "exit code " + r.exitCode + "\n";
        return head + r.output;
    }

    /** Sesi shell agent (persisten antar panggilan — cd/variabel tersimpan). */
    private ShellSession shellAgent;

    /* --------------------------------- Write -------------------------------- */

    private String tWrite(JSONObject a) throws Exception {
        String path = a.getString("file_path");
        String content = a.optString("content", "");
        File f = resolve(path);
        if (f == null) return "Error: path di luar workspace";
        if (!checkPermission("Write", "menulis " + path)) return "User declined this write. Adjust your approach — don't retry verbatim.";
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        fos.write(content.getBytes(StandardCharsets.UTF_8));
        fos.close();
        readPaths.add(f.getAbsolutePath());
        return "File ditulis: " + path + " (" + content.length() + " karakter)";
    }


    /* --------------------------------- Edit --------------------------------- */

    private String tEdit(JSONObject a) throws Exception {
        String path = a.getString("file_path");
        String oldS = a.optString("old_string", "");
        String newS = a.optString("new_string", "");
        boolean all = a.optBoolean("replace_all", false);
        File f = resolve(path);
        if (f == null) return "Error: path di luar workspace";
        if (!f.exists()) return "Error: file tidak ditemukan: " + path;
        if (!readPaths.contains(f.getAbsolutePath()))
            return "Error: FILE_NOT_READ. Anda harus Read file ini lebih dulu sebelum Edit.";
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (oldS.isEmpty()) return "Error: old_string kosong";
        if (!src.contains(oldS)) return "Error: OLD_STRING_NOT_FOUND. old_string tidak ditemukan di file — pastikan sama persis.";
        if (!all && src.indexOf(oldS) != src.lastIndexOf(oldS))
            return "Error: AMBIGUOUS_REPLACE. old_string muncul lebih dari sekali — sertakan konteks lebih banyak atau pakai replace_all.";
        String out = all ? src.replace(oldS, newS) : src.replaceFirst(Pattern.quote(oldS), Matcher.quoteReplacement(newS));
        if (!checkPermission("Edit", "mengedit " + path)) return "User declined this edit. Adjust your approach — don't retry verbatim.";
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        fos.write(out.getBytes(StandardCharsets.UTF_8));
        fos.close();
        return "File diedit: " + path;
    }

    /* --------------------------------- Glob --------------------------------- */

    private String tGlob(JSONObject a) throws Exception {
        String pat = a.optString("pattern", "**/*");
        String base = a.optString("path", "");
        File rootDir = resolve(base);
        if (rootDir == null || !rootDir.isDirectory()) rootDir = workspace;
        final String regex = globToRegex(pat);
        List<File> hits = new ArrayList<>();
        walk(rootDir, f -> {
            String rel = workspace.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            if (rel.matches(regex)) hits.add(f);
        });
        if (hits.isEmpty()) return "Tidak ada file yang cocok.";
        hits.sort(new Comparator<File>() {
            @Override public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
        });
        StringBuilder sb = new StringBuilder();
        int max = Math.min(hits.size(), 100);
        for (int i = 0; i < max; i++) {
            sb.append(workspace.toPath().relativize(hits.get(i).toPath())).append('\n');
        }
        return sb.toString();
    }

    private interface FileVisitor { void visit(File f); }

    private void walk(File dir, FileVisitor v) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".") || f.getName().equals("node_modules") || f.getName().equals("build")) continue;
            if (f.isDirectory()) walk(f, v);
            else v.visit(f);
        }
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        char[] cs = glob.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];
            if (c == '*') {
                if (i + 1 < cs.length && cs[i + 1] == '*') { sb.append(".*"); i++; }
                else sb.append("[^/]*");
            } else if (c == '?') sb.append("[^/]");
            else if (c == '.') sb.append("\\.");
            else if (c == '/') { sb.append('/'); }
            else sb.append(Pattern.quote(String.valueOf(c)).replaceAll("\\\\Q|\\\\E", ""));
        }
        return sb.toString();
    }

    /* --------------------------------- Grep --------------------------------- */

    private String tGrep(JSONObject a) throws Exception {
        String pat = a.optString("pattern", "");
        String glob = a.optString("glob", "");
        boolean ic = a.optBoolean("ignore_case", true);
        int head = a.optInt("head_limit", 50);
        if (head > 200) head = 200;
        if (pat.isEmpty()) return "Error: pattern kosong";
        Pattern p = Pattern.compile(Pattern.quote(pat), ic ? Pattern.CASE_INSENSITIVE : 0);
        Pattern gf = glob.isEmpty() ? null : Pattern.compile(globToRegex(glob));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        List<File> files = new ArrayList<>();
        walk(workspace, f -> { if (f.length() < 2_000_000) files.add(f); });
        for (File f : files) {
            if (count >= head) break;
            String rel = workspace.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            if (gf != null && !rel.matches(gf.pattern())) continue;
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                String ln;
                int lineno = 0;
                while ((ln = r.readLine()) != null && count < head) {
                    lineno++;
                    if (p.matcher(ln).find()) {
                        sb.append(rel).append(':').append(lineno).append(": ").append(ln.trim()).append('\n');
                        count++;
                    }
                }
                r.close();
            } catch (Exception ignore) { }
        }
        return count == 0 ? "Tidak ada hasil." : sb.toString();
    }

    /* -------------------------------- Delete -------------------------------- */

    private String tDelete(JSONObject a) throws Exception {
        String path = a.optString("path", "");
        File f = resolve(path);
        if (f == null || !f.exists()) return "Error: path tidak ditemukan: " + path;
        if (!checkPermission("Delete", "menghapus " + path)) return "User declined this deletion. Adjust your approach.";
        if (f.isDirectory()) {
            deleteRec(f);
            return "Folder dihapus: " + path;
        }
        f.delete();
        return "File dihapus: " + path;
    }

    private void deleteRec(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        f.delete();
    }

    /* ------------------------------- WebFetch ------------------------------- */

    private String tWebFetch(JSONObject a) throws Exception {
        String url = a.optString("url", "").trim();
        if (url.isEmpty()) return "Error: url kosong";
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        int code = conn.getResponseCode();
        if (code >= 400) {
            conn.disconnect();
            return "Error: HTTP " + code + " untuk " + url;
        }
        String ct = conn.getContentType();
        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder raw = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0 && raw.length() < 400_000) raw.append(buf, 0, n);
        r.close();
        conn.disconnect();
        String text;
        if (ct != null && ct.contains("text/html")) {
            text = htmlToText(raw.toString());
        } else {
            text = raw.toString();
        }
        if (text.length() > 8000) text = text.substring(0, 8000) + "… (dipotong)";
        return "Isi " + url + ":\n" + text;
    }

    private String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|li|h[1-6]|tr)>", "\n");
        s = s.replaceAll("(?is)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&quot;", "\"");
        s = s.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    /* ------------------------------- WebSearch ------------------------------ */

    private String tWebSearch(JSONObject a) throws Exception {
        String q = a.optString("query", "").trim();
        if (q.length() < 2) return "Error: query minimal 2 karakter";
        String enc = URLEncoder.encode(q, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL("https://html.duckduckgo.com/html/?q=" + enc).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        int code = conn.getResponseCode();
        if (code >= 400) {
            conn.disconnect();
            return "Error: pencarian web gagal (HTTP " + code + "). Coba WebFetch langsung ke URL sumber.";
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder raw = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0 && raw.length() < 300_000) raw.append(buf, 0, n);
        r.close();
        conn.disconnect();

        StringBuilder sb = new StringBuilder("Hasil pencarian web untuk \"").append(q).append("\":\n\n");
        Matcher ma = Pattern.compile("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").matcher(raw);
        Matcher ms = Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL).matcher(raw);
        int i = 0;
        while (ma.find() && i < 6) {
            String href = ma.group(1);
            String title = stripTags(ma.group(2));
            String snippet = ms.find() ? stripTags(ms.group(1)) : "";
            String real = href;
            Matcher uddg = Pattern.compile("uddg=([^&]+)").matcher(href);
            if (uddg.find()) real = URLDecoder.decode(uddg.group(1), "UTF-8");
            sb.append(i + 1).append(". ").append(title).append('\n')
              .append("   ").append(real).append('\n');
            if (!snippet.isEmpty()) sb.append("   ").append(snippet, 0, Math.min(200, snippet.length())).append('\n');
            sb.append('\n');
            i++;
        }
        if (i == 0) return "Tidak ada hasil pencarian (jaringan mungkin membatasi). Coba WebFetch langsung ke URL sumber.";
        return sb.toString();
    }

    private String stripTags(String s) {
        return s.replaceAll("(?is)<[^>]+>", "").replace("&amp;", "&").replace("&#x27;", "'")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    /* ------------------------------- TodoWrite ------------------------------ */

    private String tTodoWrite(JSONObject a) throws Exception {
        JSONArray todos = a.optJSONArray("todos");
        return TodoStore.writeAll(appCtx, todos);
    }

    /* ----------------------------- AskUserQuestion -------------------------- */

    private String tAskUser(JSONObject a) throws Exception {
        JSONArray qs = a.optJSONArray("questions");
        if (qs == null || qs.length() == 0) return "Error: questions wajib";
        if (qs.length() > 4) {
            JSONArray cut = new JSONArray();
            for (int i = 0; i < 4; i++) cut.put(qs.get(i));
            qs = cut;
        }
        if (bridge == null) return "Error: tidak bisa bertanya (UI tidak tersedia)";
        String answer = bridge.askUser(qs);
        if (answer == null) return "User tidak menjawab pertanyaan. Lanjutkan dengan asumsi terbaik Anda dan sebutkan asumsinya.";
        return "Jawaban user:\n" + answer;
    }

    /* ----------------------------- Plan mode -------------------------------- */

    private String tEnterPlan() {
        if (modeHook != null) modeHook.onModeChanged(Prefs.MODE_PLAN);
        mode = Prefs.MODE_PLAN;
        return "Entered plan mode. You should now focus on exploring the codebase and designing an implementation approach. Only read-only tools are allowed.";
    }

    private String tExitPlan(JSONObject a) throws Exception {
        String plan = a.optString("plan", "").trim();
        if (plan.isEmpty()) return "Error: parameter plan wajib (isi rencana Anda)";
        if (bridge == null) return "Error: UI tidak tersedia";
        String verdict = bridge.submitPlan(plan);
        if ("approved".equals(verdict)) {
            if (modeHook != null) modeHook.onModeChanged(Prefs.MODE_BUILD);
            mode = Prefs.MODE_BUILD;
            return "User approved the plan. Proceed with implementation. You may now use write tools.";
        }
        if (modeHook != null) modeHook.onModeChanged(Prefs.MODE_PLAN);
        mode = Prefs.MODE_PLAN;
        return "User rejected the plan. Ask what they want changed (via AskUserQuestion or plain text) and refine the plan.";
    }

    /* ------------------------------ Permission ------------------------------ */

    private boolean checkPermission(String toolName, String detail) {
        if (Prefs.MODE_YOLO.equals(mode)) return true;
        if (Prefs.MODE_EDIT.equals(mode) && ("Write".equals(toolName) || "Edit".equals(toolName))) return true;
        if (bridge == null) return true;
        return bridge.permission(toolName, detail);
    }
}
