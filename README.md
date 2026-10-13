# ZCode Mobile v1.0

ZCode Desktop v3.15.1 (Linux arm64) asli — dikonversi menjadi APK Android.

- Engine: out/main + out/host asli di Node.js v22 glibc (payload 1:1 dari .deb resmi)
- UI: renderer Desktop asli di WebView + preload asli
- Sandbox: TANPA proot/chroot — node glibc dieksekusi langsung via loader glibc
- Metode: bedah .deb → paham → convert (lihat PLAN_v1.0.md)
