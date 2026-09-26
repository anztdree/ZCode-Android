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
        if (isBusy()) return;
        cancelled = false;
        try {
            history.put(new JSONObject().put("role", "user").put("content", userText));
        } catch (Exception ignore) { }
        worker = new Thread(this::runLoop, "agent-loop");
        worker.start();
    }

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

    public static String buildSystemPrompt(String mode, String model, File workspace) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ZCode, an interactive coding agent running on Android (ZCode Mobile). ")
          .append("Help the user with software engineering tasks in their workspace.\n\n");

        sb.append("# Bahasa\n")
          .append("Selalu merespons pengguna dalam Bahasa Indonesia. Nama tool dan kode tetap dalam istilah aslinya.\n\n");

        sb.append("# Harness\n")
          .append("- Text you output outside of tool use is displayed as GitHub-flavored markdown in a chat UI.\n")
          .append("- Prefer the dedicated file/search tools (Read, Grep, Glob) before reading many files.\n")
          .append("- Independent tool calls can be requested in parallel in one response.\n")
          .append("- Reference code as `path:line` when discussing it.\n")
          .append("- Write complete, runnable files. Use Edit for small precise changes.\n\n");

        sb.append("# Mode agent aktif: ").append(mode).append("\n");
        if (Prefs.MODE_BUILD.equals(mode)) {
            sb.append("- \"Tanya dulu\": setiap penulisan/mengedit file akan dimintai izin user oleh sistem; hasil tool akan menyatakan bila user menolak. Jika ditolak, ubah pendekatan, jangan ulangi hal yang sama.\n");
        } else if (Prefs.MODE_EDIT.equals(mode)) {
            sb.append("- \"Ubah otomatis\": penulisan/mengedit file dijalankan otomatis tanpa izin.\n");
        } else if (Prefs.MODE_PLAN.equals(mode)) {
            sb.append("- \"Mode rencana\": HANYA tool read-only (Read, Glob, Grep, WebFetch, WebSearch, TodoWrite, AskUserQuestion) yang diizinkan. Jelajahi workspace, rancang pendekatan, lalu panggil ExitPlanMode dengan parameter plan berisi rencana implementasi markdown yang ringkas dan jelas. JANGAN mencoba menulis file di mode ini.\n");
        } else if (Prefs.MODE_YOLO.equals(mode)) {
            sb.append("- \"Akses penuh\": semua operasi file dijalankan tanpa konfirmasi.\n");
        }

        sb.append("\n# TodoWrite\n")
          .append("- Untuk tugas multi-langkah, buat daftar todo terlebih dahulu dan perbarui setiap langkah selesai.\n")
          .append("- At most one item may be in_progress at a time. Send the full list each call.\n\n");

        sb.append("# Lingkungan\n")
          .append("- Platform: Android (tidak ada shell/terminal — jangan mencoba perintah shell)\n")
          .append("- Workspace: ").append(workspace.getAbsolutePath()).append("\n")
          .append("- Model: ").append(model).append("\n");

        try {
            String date = new SimpleDateFormat("EEEE, d MMMM yyyy", new Locale("id", "ID")).format(new Date());
            sb.append("- Tanggal hari ini: ").append(date).append("\n");
        } catch (Exception ignore) { }

        return sb.toString();
    }
}
