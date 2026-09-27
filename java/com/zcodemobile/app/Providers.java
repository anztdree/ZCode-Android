package com.zcodemobile.app;

/**
 * Registry penyedia model BYOK "hardcore" ala Kai 9000:
 * preset siap pakai (Base URL + tautan tempat membuat API key + catatan),
 * fetch daftar model dari /models, dan pemilihan model bebas.
 * Semua preset OpenAI-compatible (chat/completions + models).
 * Semua penyedia cloud — model lokal (Ollama/LM Studio) dihapus sesuai
 * permintaan pengguna (v2.3.1), NVIDIA NIM ditambahkan.
 */
public class Providers {

    public static class P {
        public final String id;
        public final String name;
        public final String baseUrl;
        public final String apiKeyUrl;   // tautan halaman pembuatan API key ("": tidak ada)
        public final String note;        // catatan singkat utk UI
        public final boolean editableUrl;// Base URL boleh diubah (provider lokal/kustom)

        public P(String id, String name, String baseUrl, String apiKeyUrl, String note, boolean editableUrl) {
            this.id = id; this.name = name; this.baseUrl = baseUrl;
            this.apiKeyUrl = apiKeyUrl; this.note = note; this.editableUrl = editableUrl;
        }
    }

    private static final P[] ALL = new P[]{
            new P("zai", "Z.ai · GLM", "https://api.z.ai/api/paas/v4",
                    "https://z.ai/manage-apikey/apikey-list",
                    "Default ZCode Mobile — model GLM gratis di urutan atas.", false),
            new P("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                    "https://openrouter.ai/settings/keys",
                    "Ratusan model dari semua lab — banyak berlabel :free.", false),
            new P("groq", "Groq", "https://api.groq.com/openai/v1",
                    "https://console.groq.com/keys",
                    "Inferensi super cepat, kuota gratis harian.", false),
            new P("mistral", "Mistral AI", "https://api.mistral.ai/v1",
                    "https://console.mistral.ai/api-keys",
                    "Tier gratis tersedia ( experiment ).", false),
            new P("deepseek", "DeepSeek", "https://api.deepseek.com/v1",
                    "https://platform.deepseek.com/api_keys",
                    "Sangat kuat untuk kode, harga murah.", false),
            new P("together", "Together AI", "https://api.together.xyz/v1",
                    "https://api.together.ai/settings/api-keys",
                    "Banyak open-model (Llama, Qwen, DeepSeek…).", false),
            new P("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1",
                    "https://fireworks.ai/account/api-keys",
                    "Open-model cepat, siap produksi.", false),
            new P("cerebras", "Cerebras", "https://api.cerebras.ai/v1",
                    "https://cloud.cerebras.ai/platform/apikeys",
                    "Token/s paling tinggi, ada tier gratis.", false),
            new P("xai", "xAI · Grok", "https://api.x.ai/v1",
                    "https://console.x.ai",
                    "Model keluarga Grok.", false),
            new P("gemini", "Gemini (OpenAI-compat)", "https://generativelanguage.googleapis.com/v1beta/openai",
                    "https://aistudio.google.com/apikey",
                    "Endpoint OpenAI-compatible resmi Google — ada tier gratis.", false),
            new P("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1",
                    "https://build.nvidia.com/",
                    "Ratusan open-model (Llama, DeepSeek, Qwen…) — key berawalan nvapi- dari build.nvidia.com.", false),
            new P("custom", "Kustom (OpenAI-compatible)", "",
                    "",
                    "Provider apa pun — isi Base URL sendiri, model diambil dari /models.", true),
    };

    public static P[] all() { return ALL; }

    public static P byId(String id) {
        if (id != null) {
            for (P p : ALL) if (p.id.equals(id)) return p;
        }
        return ALL[0]; // zai
    }

    public static boolean exists(String id) {
        if (id == null) return false;
        for (P p : ALL) if (p.id.equals(id)) return true;
        return false;
    }
}
