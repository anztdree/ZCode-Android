package com.zcodemobile.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/** Klien LLM OpenAI-compatible: streaming SSE + fetch daftar model + uji koneksi. */
public class LlmClient {

    public interface StreamCallback {
        /** Dipanggil setiap potongan teks (delta). */
        void onDelta(String content);
        /** Dipanggil saat ada tool_call (kumulatif dipanggil sekali per tool lengkap). */
        void onToolCall(int index, String id, String name, String arguments);
        void onDone(String stopReason);
        void onError(String message);
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private volatile boolean cancelled = false;

    public LlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public void cancel() { cancelled = true; }

    /** Chat streaming. messages: JSONArray dari {role, content} + tool messages. */
    public void streamChat(JSONArray messages, JSONArray tools, final StreamCallback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", messages);
                body.put("stream", true);
                if (tools != null && tools.length() > 0) body.put("tools", tools);

                conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(120000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Accept", "text/event-stream");

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                    os.flush();
                }

                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (is == null) { cb.onError("HTTP " + code + " (tanpa isi)"); return; }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8));
                if (code >= 400) {
                    StringBuilder sb = new StringBuilder();
                    String ln;
                    while ((ln = reader.readLine()) != null) sb.append(ln).append('\n');
                    cb.onError("HTTP " + code + ": " + sb.substring(0, Math.min(500, sb.length())));
                    return;
                }

                // Akumulasi tool_calls: index -> {id, name, args}
                List<String> tcIds = new ArrayList<>();
                List<String> tcNames = new ArrayList<>();
                List<StringBuilder> tcArgs = new ArrayList<>();

                String line;
                StringBuilder contentBuf = new StringBuilder();
                String stop = null;
                while ((line = reader.readLine()) != null && !cancelled) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    try {
                        JSONObject chunk = new JSONObject(data);
                        JSONArray choices = chunk.optJSONArray("choices");
                        if (choices == null || choices.length() == 0) continue;
                        JSONObject c0 = choices.getJSONObject(0);
                        JSONObject delta = c0.optJSONObject("delta");
                        if (delta != null) {
                            String piece = delta.optString("content", null);
                            if (piece != null && !piece.isEmpty()) {
                                contentBuf.append(piece);
                                cb.onDelta(piece);
                            }
                            JSONArray tcs = delta.optJSONArray("tool_calls");
                            if (tcs != null) {
                                for (int i = 0; i < tcs.length(); i++) {
                                    JSONObject tc = tcs.getJSONObject(i);
                                    int idx = tc.optInt("index", i);
                                    while (tcIds.size() <= idx) {
                                        tcIds.add(null); tcNames.add(null); tcArgs.add(new StringBuilder());
                                    }
                                    String id = tc.optString("id", null);
                                    if (id != null && !id.isEmpty()) tcIds.set(idx, id);
                                    JSONObject fn = tc.optJSONObject("function");
                                    if (fn != null) {
                                        String fnName = fn.optString("name", null);
                                        if (fnName != null && !fnName.isEmpty()) tcNames.set(idx, fnName);
                                        String args = fn.optString("arguments", "");
                                        if (args != null) tcArgs.get(idx).append(args);
                                    }
                                }
                            }
                        }
                        String fr = c0.optString("finish_reason", null);
                        if (fr != null && !"null".equals(fr)) stop = fr;
                    } catch (Exception ignoreParse) { /* baris SSE rusak — lewati */ }
                }

                // Laporkan tool_calls yang terkumpul
                for (int i = 0; i < tcIds.size(); i++) {
                    if (tcNames.get(i) != null) {
                        cb.onToolCall(i,
                                tcIds.get(i) != null ? tcIds.get(i) : ("call_" + i),
                                tcNames.get(i),
                                tcArgs.get(i).toString());
                    }
                }
                cb.onDone(stop);
            } catch (Exception e) {
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "llm-stream").start();
    }

    /** Ambil daftar model dari endpoint /models (OpenAI-compatible). */
    public interface ModelsCallback {
        void onModels(List<String> ids);
        void onError(String message);
    }

    public static void fetchModels(String baseUrl, String apiKey, final ModelsCallback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(baseUrl + "/models").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = r.readLine()) != null) sb.append(ln);
                if (code >= 400) { cb.onError("HTTP " + code + ": " + sb.substring(0, Math.min(300, sb.length()))); return; }
                JSONObject root = new JSONObject(sb.toString());
                JSONArray data = root.optJSONArray("data");
                List<String> ids = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        String id = data.getJSONObject(i).optString("id", null);
                        if (id != null) ids.add(id);
                    }
                }
                java.util.Collections.sort(ids);
                cb.onModels(ids);
            } catch (Exception e) {
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "fetch-models").start();
    }

    /** Uji koneksi sederhana (non-streaming). */
    public interface TestCallback { void onResult(boolean ok, String message); }

    public static void testConnection(String baseUrl, String apiKey, final TestCallback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("model", "glm-4.5-flash");
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "user").put("content", "Balas hanya: OK"));
                body.put("messages", msgs);
                body.put("max_tokens", 8);
                body.put("stream", false);

                conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = r.readLine()) != null) sb.append(ln);
                if (code >= 400) {
                    cb.onResult(false, "HTTP " + code + ": " + sb.substring(0, Math.min(300, sb.length())));
                } else {
                    JSONObject root = new JSONObject(sb.toString());
                    String content = root.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "");
                    cb.onResult(true, "Terhubung! Balasan model: " + content);
                }
            } catch (Exception e) {
                cb.onResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "test-conn").start();
    }
}
