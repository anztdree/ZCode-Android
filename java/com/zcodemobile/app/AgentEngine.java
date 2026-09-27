package com.zcodemobile.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Mesin agent ala ZCode Desktop: loop tool-use ke LLM (OpenAI-compatible).
 * Memiliki 4 mode agent (build/edit/plan/yolo), paksa aturan plan mode,
 * retry, pengukuran token, dan cancel.
 */
public class AgentEngine {

    public interface Callbacks {
        /** Delta teks assistant (stream). */
        void onDelta(String piece);
        /** Dimulai saat tool call dieksekusi. */
        void onToolStart(String callId, String name, String detail);
        /** Selesai: status = ChatItem.ST_*. */
        void onToolEnd(String callId, int status, String output);
        /** Pemakaian token kumulatif (prompt, completion). */
        void onUsage(int promptTokens, int completionTokens);
        void onStatus(String text);
        void onError(String message);
        void onDone(String reason);
    }

    private LlmClient client;
    private final Tools tools;
    private JSONArray history = new JSONArray();
    private final Callbacks cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private Thread worker;

    public AgentEngine(LlmClient client, Tools tools, Callbacks cb) {
        this.client = client;
        this.tools = tools;
        this.cb = cb;
    }

    /** Ganti klien (model/penyedia bisa berubah antar-kirim). */
    public void setClient(LlmClient c) { this.client = c; }

    /** Ganti isi riwayat (saat membuka sesi lain). */
    public void replaceHistory(JSONArray h) {
        JSONArray nh = new JSONArray();
        if (h != null) {
            for (int i = 0; i < h.length(); i++) {
                try { nh.put(h.get(i)); } catch (Exception ignore) { }
            }
        }
        this.history = nh;
    }

    public JSONArray history() { return history; }

    public Tools tools() { return tools; }

    public void cancel() {
        cancelled = true;
        client.cancel();
        if (worker != null) worker.interrupt();
    }

    public boolean isBusy() {
        return worker != null && worker.isAlive();
    }

    /** Kirim pesan user & jalankan loop agent. */
    public void send(final String userText) {
        send(userText, null);
    }

    /**
     * Kirim pesan user dengan lampiran gambar opsional (vision, ala ZCode PC).
     * imageDataUrl = "data:image/jpeg;base64,…" — dibungkus sebagai content
     * parts OpenAI-compatible: [{type:text},{type:image_url}].
     */
    public void send(final String userText, final String imageDataUrl) {
        if (isBusy()) return;
        cancelled = false;
        try {
            JSONObject m = new JSONObject().put("role", "user");
            if (imageDataUrl == null || imageDataUrl.isEmpty()) {
                m.put("content", userText);
            } else {
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("type", "text")
                        .put("text", userText == null ? "" : userText));
                parts.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", imageDataUrl)));
                m.put("content", parts);
            }
            history.put(m);
        } catch (Exception ignore) { }
        worker = new Thread(this::runLoop, "agent-loop");
        worker.start();
    }

    /** /ringkas — paksa pemadatan konteks sekarang (ala /compact ZCode PC). */
    public void compactNow() { trimHistory(); }

    /** /bersihkan — kosongkan konteks agent tanpa matikan aplikasi. */
    public void clearHistory() { history = new JSONArray(); }

    /** Lanjutkan loop setelah plan disetujui (tanpa pesan user baru). */
    public void resumeAfterPlan() {
        if (isBusy()) return;
        cancelled = false;
        worker = new Thread(this::runLoop, "agent-loop");
        worker.start();
    }

    private void runLoop() {
        int maxRounds = Tools.maxRounds();
        int round = 0;
        try {
            while (!cancelled && round < maxRounds) {
                round++;
                final int r = round;
                main.post(() -> cb.onStatus("Berpikir… (ronde " + r + ")"));

                // PERBAIKAN PENTING v2.3.0: system prompt KINI benar-benar dikirim.
                // Sebelumnya buildSystemPrompt() tak pernah dipanggil sehingga agent
                // berjalan tanpa instruksi (bahasa, mode, lingkungan toybox, tanggal).
                ensureSystem();
                trimHistory();

                final StringBuilder textBuf = new StringBuilder();
                final Box err = new Box();
                final Box stop = new Box();
                final Object lock = new Object();
                final JSONArray tcAcc = new JSONArray(); // akumulasi tool_calls
                final int[] usageP = {0}, usageC = {0};

                LlmClient.StreamCallback scb = new LlmClient.StreamCallback() {
                    @Override public void onDelta(String piece) {
                        textBuf.append(piece);
                        main.post(() -> cb.onDelta(piece));
                    }
                    @Override public void onToolCall(int index, String id, String name, String arguments) {
                        synchronized (lock) {
                            try {
                                tcAcc.put(new JSONObject()
                                        .put("id", id)
                                        .put("name", name)
                                        .put("arguments", arguments));
                            } catch (Exception ignore) { }
                        }
                    }
                    @Override public void onUsage(int pt, int ct) {
                        usageP[0] = pt; usageC[0] = ct;
                        main.post(() -> cb.onUsage(pt, ct));
                    }
                    @Override public void onDone(String reason) { stop.v = reason == null ? "" : reason; }
                    @Override public void onError(String message) { err.v = message; }
                };

                client.streamChat(history, tools.definitions(), scb);
                // streamChat async — tunggu selesai
                while (stop.v == null && err.v == null) {
                    Thread.sleep(80);
                }

                if (cancelled) { finish("cancelled"); return; }
                if (err.v != null) { final String e0 = err.v; main.post(() -> cb.onError(e0)); finish("error"); return; }

                String text = textBuf.toString();
                JSONArray toolCalls;
                synchronized (lock) { toolCalls = tcAcc; }

                if (toolCalls.length() == 0) {
                    // Jawaban akhir
                    try {
                        history.put(new JSONObject().put("role", "assistant").put("content", text));
                    } catch (Exception ignore) { }
                    finish("done");
                    return;
                }

                // Assistant memakai tools — simpan pesan + jalankan tools
                try {
                    JSONObject asst = new JSONObject().put("role", "assistant").put("content", text);
                    JSONArray tcs = new JSONArray();
                    for (int i = 0; i < toolCalls.length(); i++) {
                        JSONObject t = toolCalls.getJSONObject(i);
                        tcs.put(new JSONObject()
                                .put("id", t.getString("id"))
                                .put("type", "function")
                                .put("function", new JSONObject()
                                        .put("name", t.getString("name"))
                                        .put("arguments", t.optString("arguments", "{}"))));
                    }
                    asst.put("tool_calls", tcs);
                    history.put(asst);
                } catch (Exception ignore) { }

                for (int i = 0; i < toolCalls.length() && !cancelled; i++) {
                    JSONObject t = toolCalls.getJSONObject(i);
                    String callId = t.getString("id");
                    String name = t.getString("name");
                    JSONObject args;
                    try { args = new JSONObject(t.optString("arguments", "{}")); }
                    catch (Exception e) { args = new JSONObject(); }
                    String detail = describe(name, args);

                    final String fCallId = callId;
                    main.post(() -> cb.onToolStart(fCallId, name, detail));

                    String result;
                    if (Prefs.MODE_PLAN.equals(tools.mode) && !Tools.isReadOnly(name)) {
                        // Aturan ZCode: plan mode hanya tool read-only
                        result = "Denied: Plan mode only allows read-only, non-destructive tools. Finish your plan and call ExitPlanMode.";
                        main.post(() -> cb.onToolEnd(fCallId, ChatItem.ST_DENIED, result));
                    } else {
                        result = tools.execute(name, args);
                        int st = result.startsWith("Error:") || result.startsWith("Error ")
                                ? ChatItem.ST_ERR
                                : (result.startsWith("User declined") || result.startsWith("Denied") || result.startsWith("User rejected")
                                    ? ChatItem.ST_DENIED : ChatItem.ST_OK);
                        final String fRes = result;
                        final int fSt = st;
                        main.post(() -> cb.onToolEnd(fCallId, fSt, fRes));
                    }

                    try {
                        history.put(new JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", callId)
                                .put("content", result.length() > 30000 ? result.substring(0, 30000) + "…" : result));
                    } catch (Exception ignore) { }
                }
            }
            if (!cancelled) {
                main.post(() -> cb.onError("Mencapai batas ronde maksimum (" + maxRounds + "). Kirim pesan lanjutan bila perlu."));
                finish("max-rounds");
            } else {
                finish("cancelled");
            }
        } catch (InterruptedException ie) {
            finish("cancelled");
        } catch (Exception e) {
            final String m = e.getClass().getSimpleName() + ": " + e.getMessage();
            main.post(() -> cb.onError(m));
            finish("error");
        }
    }

    /**
     * Sisipkan/perbarui pesan system di awal history (mode, model, AGENTS.md
     * selalu terkini — memori proyek bisa berubah saat agent bekerja).
     */
    private void ensureSystem() {
        try {
            JSONArray body = new JSONArray();
            for (int i = 0; i < history.length(); i++) {
                JSONObject o = history.optJSONObject(i);
                if (o != null && "system".equals(o.optString("role"))) continue;
                body.put(history.get(i));
            }
            JSONObject sys = new JSONObject()
                    .put("role", "system")
                    .put("content", buildSystemPrompt(tools.mode, client.model(), tools.getWorkspace(), tools.sandboxActive()));
            JSONArray out = new JSONArray();
            out.put(sys);
            for (int i = 0; i < body.length(); i++) out.put(body.get(i));
            history = out;
        } catch (Exception ignore) { }
    }

    /**
     * Jaga konteks tetap muat: buang pesan tertua (setelah system) bila total
     * karakter melebihi anggaran — padanan sederhana context management ZCode.
     */
    private void trimHistory() {
        try {
            final int budget = 90_000; // ≈22k token
            int total = 0;
            for (int i = 0; i < history.length(); i++) {
                JSONObject o = history.optJSONObject(i);
                if (o != null) total += o.optString("content").length();
            }
            while (total > budget && history.length() > 6) {
                JSONObject o = history.optJSONObject(1);
                int len = o == null ? 0 : o.optString("content").length();
                JSONArray nh = new JSONArray();
                for (int i = 0; i < history.length(); i++) if (i != 1) nh.put(history.get(i));
                history = nh;
                total -= len;
            }
            if (total > budget) {
                main.post(() -> cb.onStatus("Konteks padat — riwayat lama diringkas"));
            }
        } catch (Exception ignore) { }
    }

    private void finish(String reason) {
        final String r = reason;
        main.post(() -> cb.onDone(r));
    }

    /** Wadah volatile lintas-thread. */
    private static final class Box {
        volatile String v;
    }

    /** Deskripsi singkat kartu tool (bahasa Indonesia, gaya ZCode). */
    public static String describe(String name, JSONObject args) {
        try {
            switch (name) {
                case "Read": return "Membaca " + opt(args, "file_path");
                case "Write": return "Menulis " + opt(args, "file_path");
                case "Edit": return "Mengedit " + opt(args, "file_path");
                case "Delete": return "Menghapus " + opt(args, "path");
                case "Glob": return "Mencari berkas " + opt(args, "pattern");
                case "Grep": return "Mencari teks \"" + opt(args, "pattern") + "\"";
                case "WebFetch": return "Mengambil halaman " + opt(args, "url");
                case "WebSearch": return "Mencari web: " + opt(args, "query");
                case "TodoWrite": return "Memperbarui daftar todo";
                case "TodoRead": return "Membaca daftar todo";
                case "AskUserQuestion": return "Menanyakan sesuatu";
                case "Agent": return "Menjalankan subagent " + opt(args, "agent_type") + ": " + opt(args, "description");
                case "EnterPlanMode": return "Masuk mode rencana";
                case "ExitPlanMode": return "Mengajukan rencana";
                default: return name;
            }
        } catch (Exception e) {
            return name;
        }
    }

    private static String opt(JSONObject a, String k) {
        String v = a.optString(k, "").trim();
        if (v.length() > 48) v = v.substring(0, 48) + "…";
        return v;
    }

    /* ============================ System prompt ============================ */

    public static String buildSystemPrompt(String mode, String model, File workspace, boolean sandbox) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ZCode, an interactive coding agent running on Android (ZCode Mobile). ")
          .append("Help the user with software engineering tasks in their workspace.\n\n");

        sb.append("# Bahasa\n")
          .append("Selalu merespons pengguna dalam Bahasa Indonesia. Nama tool dan kode tetap dalam istilah aslinya.\n\n");

        sb.append("# Harness\n")
          .append("- Text you output outside of tool use is displayed as GitHub-flavored markdown in a chat UI.\n")
          .append("- Prefer the dedicated file/search tools (Read, Grep, Glob) before reading many files.\n")
          .append("- Use Bash when the user asks to run a command, inspect the system, or when shell utilities (ls, grep, sed, awk, tar, df) are the most natural fit.\n")
          .append("- Independent tool calls can be requested in parallel in one response.\n")
          .append("- Reference code as `path:line` when discussing it.\n")
          .append("- Write complete, runnable files. Use Edit for small precise changes.\n\n");

        sb.append("# Mode agent aktif: ").append(mode).append("\n");
        if (Prefs.MODE_BUILD.equals(mode)) {
            sb.append("- \"Tanya dulu\": setiap penulisan/mengedit file dan menjalankan perintah Bash akan dimintai izin user oleh sistem; hasil tool akan menyatakan bila user menolak. Jika ditolak, ubah pendekatan, jangan ulangi hal yang sama.\n");
        } else if (Prefs.MODE_EDIT.equals(mode)) {
            sb.append("- \"Ubah otomatis\": penulisan/mengedit file dijalankan otomatis tanpa izin; perintah Bash tetap dimintai izin.\n");
        } else if (Prefs.MODE_PLAN.equals(mode)) {
            sb.append("- \"Mode rencana\": HANYA tool read-only (Read, Glob, Grep, WebFetch, WebSearch, TodoWrite, AskUserQuestion) yang diizinkan. Jelajahi workspace, rancang pendekatan, lalu panggil ExitPlanMode dengan parameter plan berisi rencana implementasi markdown yang ringkas dan jelas. JANGAN mencoba menulis file atau menjalankan Bash di mode ini.\n");
        } else if (Prefs.MODE_YOLO.equals(mode)) {
            sb.append("- \"Akses penuh\": semua operasi file dan perintah Bash dijalankan tanpa konfirmasi.\n");
        }

        sb.append("\n# TodoWrite\n")
          .append("- Untuk tugas multi-langkah, buat daftar todo terlebih dahulu dan perbarui setiap langkah selesai.\n")
          .append("- At most one item may be in_progress at a time. Send the full list each call.\n\n");

        sb.append("# Lingkungan\n");
        if (sandbox) {
            sb.append("- Platform: Android. Perintah Bash berjalan DI DALAM sandbox Alpine Linux (proot) — direktori proyek ter-mount di /workspace (cd /workspace otomatis, cwd tersimpan antar perintah).\n")
              .append("- Tersedia busybox lengkap (ash, sed, awk, grep, tar, gzip…) dan `apk` — pasang alat sesuai kebutuhan, mis: `apk add git python3 nodejs npm` (butuh internet; paket terpasang TERSIMPAN antar perintah).\n")
              .append("- Binari Android (pm, am, settings, logcat) TIDAK ada di dalam sandbox — itu host.\n")
              .append("- Perintah interaktif (top, vim) atau server jangka panjang tetap mengunci sampai timeout — hindari.\n")
              .append("- Workspace: ").append(workspace.getAbsolutePath()).append(" (di sandbox = /workspace)\n")
              .append("- Model: ").append(model).append("\n");
        } else {
            sb.append("- Platform: Android. Shell tersedia via tool Bash, tapi binnernya terbatas pada toybox bawaan Android: ls, cat, cp, mv, rm, mkdir, grep, sed, awk, find, df, du, ps, tar, gzip, gunzip, head, tail, wc, sort, uniq, date, echo, ping, wget, sh.\n")
              .append("- TIDAK ADA: git, curl, python, node, npm, apt, sudo. Jangan mencoba menginstal paket.\n")
              .append("- Perintah interaktif (top, vim) atau server (httpd, ping tanpa -c) akan mengunci sampai timeout — hindari.\n")
              .append("- Workspace: ").append(workspace.getAbsolutePath()).append("\n")
              .append("- Model: ").append(model).append("\n");
        }

        try {
            String date = new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("id", "ID")).format(new Date());
            sb.append("- Tanggal hari ini: ").append(date).append("\n");
        } catch (Exception ignore) { }

        // Memori proyek ala ZCode Desktop: berkas AGENTS.md di root workspace.
        try {
            File ag = new File(workspace, "AGENTS.md");
            if (ag.exists()) {
                String mem = new String(java.nio.file.Files.readAllBytes(ag.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!mem.isEmpty()) {
                    if (mem.length() > 8000) mem = mem.substring(0, 8000) + "… (dipotong)";
                    sb.append("\n# Memori proyek (AGENTS.md)\n")
                      .append("Preferensi & konteks proyek yang ditetapkan pengguna:\n")
                      .append(mem).append("\n");
                }
            }
        } catch (Exception ignore) { }

        return sb.toString();
    }
}
