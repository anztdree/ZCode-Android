# ⚡ ZCode Mobile — ZCode versi Android (Native APK)

Aplikasi Android **native Java 100% murni** — bukan PWA, bukan TWA, bukan WebView wrapper.
Dibangun tanpa Gradle: `aapt2 → ECJ → d8 → zipalign → apksigner`.

Dibuat dari **bedah penuh ZCode Desktop v3.14.3** (Electron): design system, tool registry,
mode agent, prompt, dan alur tool-use disalin sedapat mungkin identik dengan aslinya.

## ✨ Fitur (v2.1.0)

### Agent
- 🤖 **Agent Chat** streaming (SSE) + tool-use loop ala ZCode Desktop (maks 30 ronde)
- 🛠️ **14 tools nama & perilaku ZCode**: `Bash`, `Read`, `Write`, `Edit`, `Glob`, `Grep`,
  `Delete`, `WebFetch`, `WebSearch`, `TodoWrite`, `TodoRead`, `AskUserQuestion`,
  `EnterPlanMode`, `ExitPlanMode`
- 🖥️ **Tool Bash** — shell `sh` persisten (cd & variabel tersimpan antar perintah),
  timeout 120 dtk default / 600 dtk maks (identik ZCode Desktop `12e4/6e5`),
  output ANSI dibersihkan otomatis
- 🎛️ **4 mode agent** persis ZCode: *Tanya dulu* (build) · *Ubah otomatis* (edit) ·
  *Mode rencana* (plan — read-only) · *Akses penuh* (yolo)
- 🔐 **Dialog izin per-tool** (Izinkan / Tolak / Selalu izinkan sesi ini) — plan mode
  otomatis memblokir tool yang menulis
- 🧠 **Kartu rencana** (ExitPlanMode) dengan tombol persetujuan di chat

### Terminal
- ⌨️ **Halaman Terminal** interaktif — `sh` (toybox) asli Android, direktori kerja = workspace
- Riwayat perintah (tombol ↑), chip perintah cepat (`ls -la`, `pwd`, `df -h`, `ps`, …),
  bersihkan layar, sesi baru
- Output berwarna gaya blok kode ZCode (`$ perintah` hijau, exit code merah)

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
1. Unduh [`out/ZCodeMobile-v2.1.0.apk`](out/ZCodeMobile-v2.1.0.apk) (270 KB)
2. Salin ke HP Android 8.0+
3. Izinkan **"Install aplikasi dari sumber tidak dikenal"** untuk aplikasi yang dipakai membuka APK
4. Install & buka **ZCode Mobile** (bisa update langsung dari v1.x — signature sama)
5. Buka tab **Setelan** → pilih **Z.ai** → tempel **API Key** (z.ai → manage-apikey) → Uji Koneksi

## 🔧 Build dari sumber
```bash
# butuh: JRE 21+, Android build-tools 34, platform android-34, ECJ jar
bash build.sh
```
Pipeline (tanpa Gradle, tanpa root):
1. `aapt2 compile` + `aapt2 link` (resource + R.java)
2. ECJ `-source 8` dengan bootclasspath `stubs.jar` (stub `java.lang.invoke` untuk lambda) + `android.jar`
3. `d8` → classes.dex, pack Python, `zipalign`, `apksigner` (keystore: `zcode.keystore`)

## 🗂️ Struktur
```
AndroidManifest.xml   v2.1.0 — minSdk 26, target 34
build.sh              pipeline build manual
java/com/zcodemobile/app/
  MainActivity.java   nav 5 halaman + drawer + mode + izin + terminal
  AgentEngine.java    loop tool-use + system prompt (Indonesia)
  LlmClient.java      SSE OpenAI-compatible + fetch /models
  Tools.java          registry 14 tools + sandbox path workspace
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
| **v2.1.0** | **Terminal + tool Bash (shell persisten ala node-pty), chip perintah cepat, riwayat perintah** |

## 🔒 Lisensi & merek
Proyek pribadi penggemar untuk pengguna Android; "ZCode" adalah merek Z.ai —
aplikasi ini BYOK (pakai API key milikmu sendiri) dan tidak berafiliasi resmi.
