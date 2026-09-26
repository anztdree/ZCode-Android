# ⚡ ZCode Mobile — ZCode versi Android (Native APK)

Aplikasi Android **native Java 100% murni** — bukan PWA, bukan TWA, bukan WebView wrapper.
Dibangun tanpa Gradle: `aapt2 → ECJ → d8 → zipalign → apksigner`.

Terinspirasi arsitektur **ZCode Desktop** (hasil bedah v3.14.3): agent loop + tool-use
terhadap API LLM OpenAI-compatible.

## ✨ Fitur
- 🤖 **Agent Chat** streaming (SSE) dengan tool-use loop ala ZCode
- 🛠️ **Tools agent**: `read_file`, `write_file`, `edit_file`, `list_files`, `grep`, `delete_path`, `todo_write`
- 📁 **File Explorer** + editor kode monospace
- ✅ **Todo Board** (dipakai juga oleh agent untuk perencanaan)
- ⚙️ **Setelan Provider**:
  - **Z.ai** (hardcoded; model gratis di urutan atas: `glm-4.5-flash`, `glm-4-flash`, dst.)
  - **Kustom OpenAI-compatible** (OpenRouter/Groq/Ollama/LM Studio…) — daftar model **di-fetch** dari `/models`
- ☀️ Tema **Light default**, 🌙 Dark sebagai cadangan
- 🇮🇩 UI Bahasa Indonesia

## 📲 Install APK
1. Unduh `out/ZCodeMobile-v1.0.0.apk`
2. Salin ke HP Android (8.0+)
3. Izinkan **"Install aplikasi dari sumber tidak dikenal"** untuk aplikasi yang dipakai membuka APK
4. Install & buka **ZCode Mobile**
5. Buka tab **Setelan** → pilih **Z.ai** → tempel **API Key** (dari z.ai → manage-apikey) → Uji Koneksi

## 🔧 Build dari sumber
```bash
# butuh: JRE 21+, Android build-tools 34, platform android-34, ECJ jar
bash build.sh
```

## 📁 Struktur
```
java/com/zcodemobile/app/
├── MainActivity.java   # 4 halaman: Chat/Berkas/Tugas/Setelan
├── AgentEngine.java    # loop tool-use (maks 12 ronde)
├── LlmClient.java      # streaming SSE + fetch models + test koneksi
├── Tools.java          # 7 tools agent + sandbox path safety
├── TodoStore.java      # persistensi tugas (JSON)
├── ChatAdapter.java    # bubble chat + render markdown-lite
├── FilesAdapter.java   # penjelajah berkas
├── TodoAdapter.java    # daftar tugas
├── MarkdownLite.java   # blok kode/heading → Spannable
└── Prefs.java          # setelan & daftar model Z.ai
```

## ⚠️ Catatan
- API key disimpan **lokal** di perangkat (SharedPreferences), dikirim hanya ke base URL provider yang kamu pilih.
- Workspace agent = penyimpanan internal app (`files/workspace`), aman dari file sistem.
- v1.0 — WebSearch agent menyusul versi berikutnya.
