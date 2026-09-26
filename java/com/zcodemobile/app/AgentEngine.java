package com.zcodemobile.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AgentEngine — loop tool-use ala ZCode Desktop:
 * kirim pesan → model balas (atau minta tool) → eksekusi tool → kirim hasil → ulangi
 * sampai model selesai (tanpa tool_call) atau batas ronde tercapai.
 */
public class AgentEngine {

    public interface Listener {
        void onStreamDelta(String piece);
        void onToolStart(String name, String argsPreview);
        void onToolResult(String name, String resultPreview, boolean ok);
        void onAssistantDone(String fullText);
        void onError(String message);
        void onRound(int round);
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Tools tools;
    private final String systemPrompt;
    private volatile boolean cancelled = false;

    public AgentEngine(String baseUrl, String apiKey, String model, Tools tools, String systemPrompt) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
    }

    public void cancel() { cancelled = true; }

    public void run(JSONArray history, final Listener l) {
        // history TIDAK termasuk system prompt; system ditambahkan di depan tiap request.
        new Thread(() -> {
            try {
                JSONArray convo = new JSONArray();
                convo.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                for (int i = 0; i < history.length(); i++) convo.put(history.getJSONObject(i));

                for (int round = 1; round <= Tools.MAX_TOOL_ROUNDS && !cancelled; round++) {
                    l.onRound(round);
                    String full = runOneRound(convo, l);
                    if (full == null) return; // error/cancel sudah dilaporkan
                    // runOneRound sudah menambahkan assistant msg + tool results jika ada tool_calls.
                    if (!"has_tool_calls".equals(full)) {
                        l.onAssistantDone(full);
                        return; // selesai
                    }
                }
                if (!cancelled) {
                    l.onError("Batas " + Tools.MAX_TOOL_ROUNDS + " ronde tool tercapai. Coba lanjutkan dengan instruksi baru.");
                }
            } catch (Exception e) {
                l.onError("AgentEngine: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }, "agent-loop").start();
    }

    /** Satu request streaming. Kembalikan teks; "has_tool_calls" bila perlu ronde lanjutan. */
    private String runOneRound(JSONArray convo, final Listener l) {
        final Object lock = new Object();
        final StringBuilder content = new StringBuilder();
        final JSONArray toolCalls = new JSONArray(); // {id, name, args}
        final String[] error = {null};
        final boolean[] finished = {false};

        LlmClient client = new LlmClient(baseUrl, apiKey, model);
        client.streamChat(convo, tools.definitions(), new LlmClient.StreamCallback() {
            @Override public void onDelta(String piece) {
                synchronized (lock) { content.append(piece); }
                l.onStreamDelta(piece);
            }
            @Override public void onToolCall(int index, String id, String name, String arguments) {
                try {
                    JSONObject tc = new JSONObject()
                            .put("id", id).put("name", name).put("args", arguments);
                    synchronized (lock) { toolCalls.put(tc); }
                    String prev = arguments.length() > 120 ? arguments.substring(0, 120) + "…" : arguments;
                    l.onToolStart(name, prev);
                } catch (Exception ignore) { }
            }
            @Override public void onDone(String stopReason) {
                synchronized (lock) { finished[0] = true; lock.notifyAll(); }
            }
            @Override public void onError(String message) {
                synchronized (lock) { error[0] = message; finished[0] = true; lock.notifyAll(); }
            }
        });

        synchronized (lock) {
            while (!finished[0]) {
                try { lock.wait(500); } catch (InterruptedException ie) { break; }
            }
        }
        if (cancelled) return null;
        if (error[0] != null) { l.onError(error[0]); return null; }

        try {
            if (toolCalls.length() > 0) {
                // Tambahkan pesan assistant dengan tool_calls
                JSONArray tcArr = new JSONArray();
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject tc = toolCalls.getJSONObject(i);
                    tcArr.put(new JSONObject()
                            .put("id", tc.getString("id"))
                            .put("type", "function")
                            .put("function", new JSONObject()
                                    .put("name", tc.getString("name"))
                                    .put("arguments", tc.getString("args"))));
                }
                JSONObject assistant = new JSONObject()
                        .put("role", "assistant")
                        .put("content", content.length() > 0 ? content.toString() : JSONObject.NULL)
                        .put("tool_calls", tcArr);
                convo.put(assistant);

                // Eksekusi tiap tool & tambahkan hasil
                for (int i = 0; i < toolCalls.length(); i++) {
                    if (cancelled) return null;
                    JSONObject tc = toolCalls.getJSONObject(i);
                    JSONObject args;
                    try { args = new JSONObject(tc.optString("args", "{}")); }
                    catch (Exception pe) { args = new JSONObject(); }
                    String result = tools.execute(tc.getString("name"), args);
                    boolean ok = !result.startsWith("Error");
                    String prev = result.length() > 400 ? result.substring(0, 400) + "…" : result;
                    l.onToolResult(tc.getString("name"), prev, ok);
                    convo.put(new JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", tc.getString("id"))
                            .put("content", result));
                }
                return "has_tool_calls";
            }
            return content.toString();
        } catch (Exception e) {
            l.onError("parse: " + e.getMessage());
            return null;
        }
    }

    /** System prompt agent (Bahasa Indonesia), mirip semangat ZCode Desktop. */
    public static String buildSystemPrompt(String workspacePath) {
        return "Kamu adalah ZCode Mobile, asisten coding agent di Android (versi mobile dari ZCode Desktop).\n"
             + "Kamu berjalan di perangkat Android pengguna dan bekerja di folder workspace: " + workspacePath + "\n\n"
             + "Aturan penting:\n"
             + "1. Kamu punya tools: read_file, write_file, edit_file, list_files, grep, delete_path, todo_write.\n"
             + "2. Untuk membangun/mengubah proyek: gunakan todo_write untuk merencanakan langkah, lalu kerjakan satu per satu dengan tools.\n"
             + "3. Path selalu relatif dari workspace. Jangan pernah menulis path absolut.\n"
             + "4. Sebelum mengedit berkas yang belum kamu baca, baca dulu dengan read_file.\n"
             + "5. edit_file butuh old_text PERSIS seperti di berkas (termasuk spasi). Jika gagal, baca berkas lagi.\n"
             + "6. Setelah selesai satu tugas besar, ringkas hasilnya secara singkat dan jelas.\n"
             + "7. Jawab selalu dalam Bahasa Indonesia yang ramah.\n"
             + "8. Kode yang kamu tulis harus lengkap dan bisa dijalankan, bukan potongan setengah jadi.\n"
             + "9. Konten web (HTML/CSS/JS) yang kamu buat bisa dibuka pengguna lewat tab Berkas.\n";
    }
}
