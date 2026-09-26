# 🔬 Laporan Bedah ZCode Desktop v3.14.3 (build 7762)

> Sumber: `ZCode-3.14.3-linux-x64.deb` (155 MB) dari CDN resmi `cdn-zcode.z.ai`
> Diekstrak penuh: control deb, `app.asar` (326 MB), folder `glm/`, `tools/`, `config/`

## 1. Identitas Aplikasi
- **Package**: `@zcode/desktop` — "ZCode Desktop App" — v3.14.3 (production)
- **Framework**: **Electron** (bukan VS Code fork) + **React 19** renderer (59 MB assets!)
- **Lisensi**: proprietary, vendor ZCode `<dev@zcode.z.ai>`

## 2. Arsitektur Internal (monorepo workspace)
| Modul | Fungsi |
|---|---|
| `@zcode/main` (out/main, 2 MB) | Electron main process, window, updater |
| `@zcode/host` (out/host, 2.7 MB) | **Otak agent**: system prompt + agent loop + tool registry |
| `@zcode/scheduler` (out/scheduler) | Orkestrasi task agent |
| `@zcode/rpc`, `@zcode/server` | Komunikasi renderer ↔ host |
| `@zcode/ui` (renderer) | UI React 19: chat, usage charts, artifact viewer, KaTeX |
| `@zcode/zcode-cua` | Computer-Use Agent (+ `cua-permission-panel.html`) |
| `@zcode/provider`, `@zcode/provider-node` | Koneksi ke LLM API |

## 3. Cara ZCode Berbicara dengan GLM (KUNCI UTAMA 🔑)
Dari `config/provider/zcode-builtin.json`:
- **API Anthropic-compatible**: `https://api.z.ai/api/anthropic` (dipakai Z.ai Coding Plan)
- **API OpenAI-compatible**: `https://api.z.ai/api/paas/v4`
- **Auth**: API Key (BYOK) — dikelola di `z.ai/manage-apikey`
- **Model**: `GLM-5.3`, `GLM-5.3-Flash`, `GLM-5V-Turbo`, `GLM-5.1`, `GLM-5`, dsb.

➡️ **Kesimpulan: agent ZCode = HTTP client + loop tool-use.** Tidak butuh binary khusus — bisa direplikasi 100% di Android (Java `HttpURLConnection`/OkHttp + SSE).

## 4. Tool Agent (ditemukan di host/scheduler)
`Bash, Read, Write, Edit, Glob, Grep, TodoWrite, Task, TaskOutput, TaskStop, WebSearch, WebFetch, AskUserQuestion, Skill, EnterPlanMode, ExitPlanMode`
- Implementasi: `node-pty` (terminal), `ssh2` (remote), `playwright-core` (browser), `sharp` (gambar)
- Tools pencarian dibundel sebagai binary: `ripgrep`, `ugrep`, `bfs`

## 5. Sistem Plugin & Skills
- Marker `.zcode-plugin`, folder `skills/<nama>/SKILL.md`, MCP servers (`@modelcontextprotocol/server`)
- Plugin bawaan: `image-search`, `pdf`, `spreadsheets`, `node-repl-host`, `bundled-skills/dynamic-workflows`
- ⭐ **`android-emulator-plugin`**: MCP server untuk develop Android — `android_create_app` (generate **Kotlin + Jetpack Compose**), `android_build_and_run` (Gradle), screenshot, UI automation ADB. Z.ai sendiri sudah menyiapkan jalur Android!

## 6. Infra Lain
- OAuth login via `chat.z.ai/api/oauth`, event report `zcode.z.ai/api/v1/event/report`
- Auto-update via `electron-updater` + CDN releases
- Observability: OpenTelemetry + Arms RUM

---

# 📱 RENCANA EKSEKUSI: "ZCode Mobile" — APK ANDROID MURNI

## Prinsip
- **BUKAN PWA, BUKAN TWA, BUKAN WebView-wrapper** — aplikasi **native Java** (dikompil ke dex, jalan di ART sama seperti Kotlin)
- **Tanpa Gradle**: pipeline manual di sandbox (sandbox: JRE 21 + keytool ✅, tanpa sudo — javac digantikan **ECJ jar**, build-tools Android diunduh langsung dari Google, tanpa root)
- Hasil: **ZCodeMobile.apk ter-signed, siap install** (sideload / "unknown sources")

## Toolchain yang akan diunduh (~200 MB)
1. ECJ (Eclipse Compiler for Java) — standalone jar, jalan di JRE
2. Android `build-tools` 34: `aapt2`, `d8`, `zipalign`, `apksigner`
3. `android.jar` (platform-34) untuk kompilasi
4. `keytool` (sudah ada) untuk debug keystore signing

## Fitur v1 (scope MVP — realistis untuk Android)
| # | Fitur | Detail |
|---|---|---|
| 1 | **Agent Chat** | Streaming SSE ke `api.z.ai/api/anthropic`, model GLM-5.3/Flash (BYOK API key di Settings) |
| 2 | **Agent Loop + Tools** | Read/Write/Edit/Glob/Grep file di workspace app + TodoWrite; jalur tool-use persis ala ZCode desktop |
| 3 | **File Explorer** | Kelola file proyek di storage app, buka editor dengan syntax highlight |
| 4 | **Todo Board** | Progress rencana agent secara visual |
| 5 | **Settings** | API key, pilih model, base URL (bisa diarahkan ke proxy) |
| 6 | **Tema** | Dark IDE ala ZCode, bottom navigation native |

## Etapa Eksekusi (setelah disetujui)
1. **E1** — Download & verifikasi toolchain (ECJ, build-tools, android.jar)
2. **E2** — Skeleton APK murni (Activity, tema, nav) → build → **APK pertama ter-signed** 🎉
3. **E3** — Chat UI + streaming SSE client ke GLM API
4. **E4** — Agent loop + tool registry (Read/Write/Edit/Glob/Grep/Todo)
5. **E5** — File explorer + editor + todo board
6. **E6** — Settings (BYOK) + polish tema
7. **E7** — Build final, verifikasi `aapt2`/`apksigner`, taruh APK di `/public` agar bisa diunduh dari Preview, + panduan install
