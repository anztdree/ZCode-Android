# ⚡ ZCode Mobile — ZCode versi Android (Native APK)

Aplikasi Android **native Java 100% murni** — bukan PWA, bukan TWA, bukan WebView wrapper.
Dibangun tanpa Gradle: `aapt2 → ECJ → d8 → zipalign → apksigner`.

Dibuat dari **bedah penuh ZCode Desktop v3.14.3** (Electron): design system, tool registry,
mode agent, prompt, dan alur tool-use disalin sedapat mungkin identik dengan aslinya.

## ✨ Fitur (v2.3.1)

### Agent
- 🤖 **Agent Chat** streaming (SSE) + tool-use loop ala ZCode Desktop (maks 30 ronde)
- 🛠️ **15 tools nama & perilaku ZCode**: `Bash`, `Read`, `Write`, `Edit`, `Glob`, `Grep`,
  `Delete`, `WebFetch`, `WebSearch`, `TodoWrite`, `TodoRead`, `AskUserQuestion`,
  `EnterPlanMode`, `ExitPlanMode`, `Agent`
- 🖥️ **Tool Bash** — shell `sh` persisten (cd & variabel tersimpan antar perintah),
  timeout 120 dtk default / 600 dtk maks (identik ZCode Desktop `12e4/6e5`),
  output ANSI dibersihkan otomatis
- 🤖 **Tool Agent (subagent) ala desktop**: `Explore` (read-only) & `general-purpose` —
  riset/eksplorasi berjalan dengan konteks sendiri, laporan kembali ke chat utama
- 🧠 **Memori proyek AGENTS.md** — berkas di root workspace otomatis disuntik ke system prompt
- 🧹 **Context management** — riwayat otomatis dipangkas (anggaran ±90k karakter) agar sesi panjang tetap jalan
- 🔌 **BYOK hardcore ala Kai 9000** — 12 preset penyedia (Z.ai, OpenRouter, Groq, Mistral,
  DeepSeek, Together, Fireworks, Cerebras, xAI, Gemini, **NVIDIA NIM**, Kustom —
  model lokal Ollama/LM Studio dihapus di v2.3.1):
  daftar model **di-fetch dari `/models`** (parser toleran multi-format + dedupe) + kotak pencarian + input manual,
  validasi otomatis debounce 800ms dengan status granular & **pesan error dibersihkan** (bukan error mentah),
  deep-link ke halaman pembuatan API key, key/model/Base-URL per penyedia (migrasi otomatis dari versi lama)
- 🐧 **Sandbox Linux (proot) ala Kai 9000 (v2.3.1)** — Alpine Linux 3.20 + proot statis
  dibundel di APK; sekali tap **"Pasang sandbox"** di halaman Berkas, lalu **semua perintah
  Bash (tool agent + Terminal) berjalan di dalam rootfs**: `apk add git python3 nodejs`
  bisa dipakai dan **pakete tersimpan**, proyek konsisten di `/workspace`, cwd persisten
  antar perintah, toggle aktif/nonaktif kapan saja (kembali ke shell toybox Android)
- 🔧 **Uji koneksi memakai model aktif** (v2.3.1 — sebelumnya hardcode `glm-4.5-flash`
  sehingga selalu gagal untuk penyedia lain)
- 🚨 **System prompt kini benar-benar dikirim** (perbaikan v2.3.0 — sebelumnya `buildSystemPrompt()`
  tak pernah dipanggil sehingga agent berjalan tanpa instruksi bahasa/mode/lingkungan)
- 🎛️ **4 mode agent** persis ZCode: *Tanya dulu* (build) · *Ubah otomatis* (edit) ·
  *Mode rencana* (plan — read-only) · *Akses penuh* (yolo)
- 🔐 **Dialog izin per-tool** (Izinkan / Tolak / Selalu izinkan sesi ini) — plan mode
  otomatis memblokir tool yang menulis
- 🧠 **Kartu rencana** (ExitPlanMode) dengan tombol persetujuan di chat

### Terminal
- ⌨️ **Halaman Terminal** interaktif — `sh` (toybox) asli Android **atau sandbox Alpine proot** (toggle), direktori kerja = workspace
- Riwayat perintah (tombol ↑), chip perintah cepat (`ls -la`, `pwd`, `df -h`, `ps`, …),
  bersihkan layar, sesi baru
- Output berwarna gaya blok kode ZCode (`$ perintah` hijau, exit code merah)
- 📜 **Protokol shell kuat ala Kai 9000**: perintah di-stage ke berkas sementara lalu
  di-`source` (aman multiline/kutip), sentinel nonce via `>&2` (tahan redirect stdout),
  generation guard anti "stale EOF" antar restart shell
- 🛡️ **Anti-crash**: buffer Editable dipaksa sejak awal (akar force close v2.1.0),
  semua operasi UI terminal ber-pelindung try-catch, crash logger ke `crash-log.txt`
- 🚑 **Anti crash-loop startup (v2.2.1)**: perbaikan NPE `saved == null` pada instalasi
  baru/prefs kosong (akar force close saat aplikasi dibuka), seluruh `onCreate` ber-pelindung,
  **layar pemulihan** (Mulai ulang / Perbaiki data / Salin laporan) — aplikasi tak pernah
  mati tanpa UI, dan laporan error otomatis ditawarkan saat dibuka kembali

### UI & Data
- 🎨 **Design system asli ZCode Desktop** (palet `theme-zai-light`/`theme-zai-dark`,
  ikon Lucide, tool card, bubble user, blok kode, header + model chip + status strip)
- 💾 **Riwayat sesi persisten** (JSON) + drawer "Tasks" ala sidebar desktop
- 📁 **File Explorer** + editor kode + ikon warna per ekstensi
- ✅ **Todo Board** (format todo ZCode: content/status/priority)
- 🌐 **Pratinjau WebView** untuk hasil HTML agent
- ⚙️ **Setelan Provider**: **Z.ai** (hardcoded; model gratis di urutan atas:
  `glm-4.5-flash`, `glm-4-flash`, dst.) · **Kustom OpenAI-compatible**
  (daftar model di-fetch dari `/models`)
- ☀️ Tema **Light default**, 🌙 Dark cadangan · 🇮🇩 UI Bahasa Indonesia

## 📲 Install APK
1. Unduh [`out/ZCodeMobile-v2.3.1.apk`](out/ZCodeMobile-v2.3.1.apk) (4,5 MB — kini termasuk proot + rootfs Alpine untuk sandbox)
2. Salin ke HP Android 8.0+
3. Izinkan **"Install aplikasi dari sumber tidak dikenal"** untuk aplikasi yang dipakai membuka APK
4. Install & buka **ZCode Mobile** (bisa update langsung dari v1.x/v2.x — signature sama)
5. Buka tab **Setelan** → pilih **Z.ai** → tempel **API Key** (z.ai → manage-apikey) → Uji Koneksi
6. Buka tab **Berkas** → **Pasang sandbox** (opsional) → Bash & Terminal berjalan di Alpine Linux

## 🔧 Build dari sumber
```bash
# butuh: JRE/JDK 21+, Android build-tools 34, platform android-34, ECJ jar
bash build.sh
```
Pipeline (tanpa Gradle, tanpa root):
1. `aapt2 compile` + `aapt2 link` (resource + **assets: proot + rootfs Alpine** + R.java)
2. ECJ `-source 8` dengan bootclasspath `stubs.jar` (stub `java.lang.invoke` untuk lambda) + `android.jar`
3. `d8` → classes.dex, pack Python, `zipalign`, `apksigner` (keystore: `zcode.keystore`)

## 🗂️ Struktur
```
AndroidManifest.xml   v2.3.1 — minSdk 26, target 28 (syarat exec proot, pola Termux)
build.sh              pipeline build manual
assets/sandbox/       proot statis ARM64 + rootfs Alpine 3.20 aarch64 (untuk sandbox)
java/com/zcodemobile/app/
  MainActivity.java   nav 5 halaman + drawer + mode + izin + terminal + kartu sandbox
  AgentEngine.java    loop tool-use + system prompt (Indonesia, sadar-sandbox)
  LlmClient.java      SSE OpenAI-compatible + fetch /models toleran + uji koneksi per-model
  Tools.java          registry 15 tools + routing Bash → sandbox
  Sandbox.java        pasang/hapus sandbox proot + ekstraktor tar.gz mandiri + wrap perintah
  ShellSession.java   shell persisten (Terminal + tool Bash)
  ChatAdapter/ChatItem/MarkdownLite.java   chat ala desktop
  SessionStore/SessionAdapter.java         riwayat sesi
  TodoStore/TodoAdapter.java               todo ZCode
  FilesAdapter.java                        file explorer
  Prefs.java                               penyimpanan setelan
res/                  layout + palet tema asli ZCode + ikon Lucide
out/                  APK rilis ter-signed
```

## 📜 Rilis
| Versi | Isi |
|---|---|
| v1.0.0 | MVP: chat streaming, 7 tools, explorer, todo, setelan, tema |
| v1.1.0 | Identitas visual ZCode (ikon asli, palet monokrom, tool cards), sesi, pratinjau HTML |
| v2.0.0 | Penyelarasan total: design system asli, 13 tools ZCode, 4 mode, plan approval, AskUserQuestion, izin per-tool, drawer Tasks |
| v2.1.0 | Terminal + tool Bash (shell persisten ala node-pty), chip perintah cepat, riwayat perintah |
| **v2.2.0** | **Perbaikan force close Terminal (buffer Editable) + protokol shell ala Kai 9000 (staging file, sentinel nonce >&2, generation guard) + crash logger** |
| **v2.2.1** | **Perbaikan force close saat aplikasi dibuka (NPE `saved==null` pada instal baru/prefs kosong) + layar pemulihan anti crash-loop + dialog laporan error otomatis** |
| **v2.3.0** | **BYOK hardcore 13 preset + fetch /models + validasi granular + system prompt benar-benar dikirim + tool Agent (subagent) + AGENTS.md + trim konteks + rename sesi** |
| **v2.3.1** | **Fix bug bagian API key (error mentah dibersihkan, uji koneksi pakai model aktif, penjaga key/Base-URL kosong, respons basi dibasmi) + preset NVIDIA NIM + hapus model lokal + Sandbox Linux proot (Alpine dibundel, apk add tersimpan, cwd persisten, toggle aktif)** |

## 🔒 Lisensi & merek
Proyek pribadi penggemar untuk pengguna Android; "ZCode" adalah merek Z.ai —
aplikasi ini BYOK (pakai API key milikmu sendiri) dan tidak berafiliasi resmi.
