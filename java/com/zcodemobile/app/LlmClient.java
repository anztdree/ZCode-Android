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

/** Klien LLM OpenAI-compatible: streaming SSE + tool_calls + usage (ala ZCode: stream_options.include_usage). */
public class LlmClient {

    public interface StreamCallback {
        /** Dipanggil setiap potongan teks (delta). */
        void onDelta(String content);
        /** Dipanggil saat ada tool_call (kumulatif dipanggil sekali per tool lengkap). */
        void onToolCall(int index, String id, String name, String arguments);
        /** Dipanggil bila chunk membawa pemakaian token (chunk terakhir). */
        void onUsage(int promptTokens, int completionTokens);
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

    /** Nama model klien ini (dipakai system prompt). */
    public String model() { return model; }

    /** Pesan error ramah + granular ala Kai 9000 (status per kode HTTP). */
    static String friendlyError(int code, String body) {
        String detail = sanitizeDetail(body);
        String base;
        switch (code) {
            case 400: base = "Permintaan ditolak — model mungkin salah / tidak tersedia di akun ini"; break;
            case 401: base = "API Key tidak valid / belum terpasang"; break;
            case 403: base = "Akses ditolak — key belum berhak atau region diblokir"; break;
            case 402: base = "Kuota/kredit penyedia habis"; break;
            case 404: base = "Endpoint tidak ditemukan — periksa Base URL (biasanya harus berakhiran /v1)"; break;
            case 429: base = "Rate limit / kuota gratis terlampaui — tunggu sebentar"; break;
            default: base = code >= 500 ? "Server penyedia sedang bermasalah" : "Permintaan gagal"; break;
        }
        return base + " (HTTP " + code + ")" + (detail.isEmpty() ? "" : ": " + detail);
    }

    /**
     * Ambil inti pesan error — JANGAN tampilkan body mentah panjang/HTML di
     * bagian API key (sumber keluhan "Full error"). Coba field JSON
     * message/error.message, kalau HTML beri pesan singkat.
     */
    static String sanitizeDetail(String body) {
        if (body == null) return "";
        String b = body.trim();
        if (b.isEmpty()) return "";
        try {
            JSONObject o = new JSONObject(b);
            JSONObject err = o.optJSONObject("error");
            String m = null;
            if (err != null) m = err.optString("message", null);
            if (m == null || m.isEmpty()) m = o.optString("message", null);
            if (m == null || m.isEmpty()) {
                if (err != null) m = err.optString("type", null);
            }
            if (m == null || m.isEmpty()) m = o.optString("error", null);
            if (m != null && !m.isEmpty() && !"{".equals(m.trim())) return cap(m.trim());
        } catch (Exception ignore) { }
        String low = b.toLowerCase();
        if (b.startsWith("<") || low.contains("<html") || low.contains("<!doctype"))
            return "(respons berupa HTML — Base URL kemungkinan salah)";
        return cap(b.replace('\n', ' ').replace('\r', ' '));
    }

    private static String cap(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** Pesan ramah utk eksepsi jaringan. */
    static String friendlyNetworkError(Throwable e) {
        String n = e.getClass().getSimpleName();
        if (n.contains("UnknownHost")) return "Host tidak terjangkau — periksa Base URL & koneksi internet";
        if (n.contains("Timeout") || n.contains("SocketTimeout")) return "Waktu tunggu habis — koneksi lambat / host salah";
        if (n.contains("Connect")) return "Tidak bisa terhubung — periksa Base URL, port & internet";
        return n + ": " + e.getMessage();
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
                body.put("stream_options", new JSONObject().put("include_usage", true));
                if (tools != null && tools.length() > 0) body.put("tools", tools);

                conn = (HttpURLConnection) new URL(baseUrl + "/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(180000);
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
                if (is == null) { cb.onError(friendlyError(code, "")); return; }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8));
                if (code >= 400) {
                    StringBuilder sb = new StringBuilder();
                    String ln;
                    while ((ln = reader.readLine()) != null) sb.append(ln).append('\n');
                    cb.onError(friendlyError(code, sb.toString()));
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

                        // usage (chunk terakhir) — cek SEBELUM choices (choices bisa kosong)
                        JSONObject usage = chunk.optJSONObject("usage");
                        if (usage != null) {
                            cb.onUsage(usage.optInt("prompt_tokens", 0),
                                    usage.optInt("completion_tokens", 0));
                        }

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
                if (!cancelled) cb.onError(friendlyNetworkError(e));
                else cb.onDone("cancelled");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "llm-stream").start();
    }

    /** Ambil daftar model dari endpoint /models (toleran multi-format). */
    public interface ModelsCallback {
        void onModels(List<String> ids);
        void onError(String message);
    }

    public static void fetchModels(String baseUrl, String apiKey, final ModelsCallback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                if (baseUrl == null || baseUrl.trim().isEmpty()) {
                    cb.onError("Base URL masih kosong — isi dulu di Setelan");
                    return;
                }
                conn = (HttpURLConnection) new URL(baseUrl + "/models").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                if (apiKey != null && !apiKey.isEmpty())
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = r.readLine()) != null) sb.append(ln);
                if (code >= 400) { cb.onError(friendlyError(code, sb.toString())); return; }

                String trimmed = sb.toString().trim();
                List<String> ids = new ArrayList<>();
                try {
                    if (trimmed.startsWith("[")) {
                        collectIds(new JSONArray(trimmed), ids);
                    } else {
                        JSONObject root = new JSONObject(trimmed);
                        JSONArray data = root.optJSONArray("data");
                        if (data == null) data = root.optJSONArray("models");
                        if (data == null) {
                            JSONObject dObj = root.optJSONObject("data");
                            if (dObj != null) data = dObj.optJSONArray("models");
                        }
                        if (data != null) {
                            collectIds(data, ids);
                        } else {
                            JSONObject err = root.optJSONObject("error");
                            if (err != null) {
                                String m = err.optString("message", null);
                                if (m == null || m.isEmpty()) m = err.optString("type", "penyedia menolak permintaan");
                                cb.onError("Gagal mengambil model: " + m);
                                return;
                            }
                        }
                    }
                } catch (Exception pe) {
                    cb.onError("Respons /models bukan JSON — periksa Base URL (biasanya harus berakhiran /v1)");
                    return;
                }
                // dedupe + urutkan + batasi (penyedia sepele NVIDIA ratusan model)
                java.util.Set<String> seen = new java.util.LinkedHashSet<>(ids);
                ids = new ArrayList<>(seen);
                java.util.Collections.sort(ids);
                if (ids.size() > 400) ids = ids.subList(0, 400);
                cb.onModels(ids);
            } catch (Exception e) {
                cb.onError(friendlyNetworkError(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "fetch-models").start();
    }

    /** Kumpulkan id model dari array: {id}|{name}|{model}|string mentah. */
    private static void collectIds(JSONArray arr, List<String> out) {
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    String s = arr.optString(i, null);
                    if (s != null && !s.isEmpty()) out.add(s);
                    continue;
                }
                String id = o.optString("id", null);
                if (id == null || id.isEmpty()) id = o.optString("name", null);
                if (id == null || id.isEmpty()) id = o.optString("model", null);
                if (id != null && !id.isEmpty()) out.add(id);
            } catch (Exception ignore) { }
        }
    }

    /** Uji koneksi sederhana (non-streaming) — pakai model aktif penyedia. */
    public interface TestCallback { void onResult(boolean ok, String message); }

    public static void testConnection(String baseUrl, String apiKey, final String model, final TestCallback cb) {
        // TANPA model terpilih: cukup cek /models (jangan kirim model ngawur)
        if (model == null || model.trim().isEmpty()) {
            fetchModels(baseUrl, apiKey, new ModelsCallback() {
                @Override public void onModels(List<String> ids) {
                    cb.onResult(true, "Terhubung — " + ids.size() + " model tersedia (pilih model dulu)");
                }
                @Override public void onError(String message) { cb.onResult(false, message); }
            });
            return;
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
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
                    cb.onResult(false, friendlyError(code, sb.toString()));
                } else {
                    String content = "";
                    try {
                        content = new JSONObject(sb.toString()).getJSONArray("choices")
                                .getJSONObject(0).getJSONObject("message").optString("content", "");
                    } catch (Exception ignore) { }
                    cb.onResult(true, "Terhubung! Model " + model + " merespons"
                            + (content.isEmpty() ? "." : ": " + content.trim()));
                }
            } catch (Exception e) {
                cb.onResult(false, friendlyNetworkError(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "test-conn").start();
    }
}
