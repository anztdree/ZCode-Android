# Arsitektur ZCode Mobile v4 (penulisan ulang dari nol)

## Prinsip
1. Satu-satunya sumber kebenaran perilaku = payload asli ZCode Desktop (deb 3.15.1 → app.asar).
2. UI = renderer asli (HTML/JS/CSS dari asar) di WebView Android. Tidak ada UI tiruan.
3. Engine = host asli (out/host, out/scheduler) di Node glibc di dalam proot.
4. Shell Android Java sesempit mungkin: pasang asset → start engine → WebView → OAuth redirect.

## Komponen baru
- core/main.mjs      — entry: HTTP statis + WS IPC server (di proot, 127.0.0.1)
- core/electron-stub — stub ipcMain/BrowserWindow/app cukup untuk jalur chat+login
- core/renderer-shim — JS di-inject ke WebView: implement API preload via WS
- app/…/EngineService— ekstrak tar.gz, spawn proot+node, baca READY, sertakan port ke WebView

## Protokol IPC
WS JSON: {id, dir:"r"|"e", channel, args[] | result | error, event}
- invoke  : WebView→node, id dipakai untuk mencocokkan respons (ipcRenderer.invoke)
- event   : node→WebView push (webContents.send / host frames)

## Data
- Asset APK: sandbox/proot*+rootfs*, zcode/zhost.tar.gz, zcode/zrt-*.tar.gz, zcode/zui.tar.gz (renderer), zcode/core.tar.gz
- Semua diekstrak ke filesDir saat first-run (installer streaming, sanitasi path).
