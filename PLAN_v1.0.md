# RENCANA v1.0 — ZCode Mobile (bedah .deb → convert) — REVISI 2

> Revisi 2 (13 Okt 2026) — merespons pertanyaan user: *"4 tambahan ini mau pakai
> aplikasi luar?? titik gagal anda di sini dari tadi — perlu diskusi lebih matang."*
> Perubahan inti hasil diskusi + eksperimen lab: **PROOT & ROOTFS KELUAR dari jalur
> utama** (jadi fallback otomatis), karena terbukti tidak diperlukan untuk boot engine.

---

## A. Jawaban langsung: "ini mau pakai aplikasi luar??"

**TIDAK. Nol aplikasi luar.** APK 100% mandiri. Yang saya sebut "4 tambahan" itu
adalah **FILE yang dibawa di dalam APK kita** dan diekstrak ke penyimpanan privat
aplikasi sendiri — bukan aplikasi yang diinstal/dipanggil:

| # | Sebutan | Sebenarnya apa (file) | Sumber file | Ukuran | Status v1.0 |
|---|---|---|---|---|---|
| 1 | node+glibc | `node` (ELF arm64) + ~30 lib `.so` (libc, libstdc++, libgcc, libm…) | tarball resmi nodejs.org v22 + .deb Ubuntu jammy | ~55MB | **WAJIB** (dipertahankan) |
| 2 | rootfs shell | minirootfs Alpine (tar.gz) — cuma penyedia `/bin/sh` + kerangka dir | Alpine minirootfs resmi | 3.8MB | **DIHAPUS** dari jalur utama |
| 3 | proot Termux | 1 biner ELF `proot` + loader + 2 lib (hanya FILE-nya; **app Termux tidak dipakai/diinstal sama sekali**) | .deb resmi packages.termux.dev | 350KB | **TURUN jadi fallback diam** |
| 4 | bridge ~200 baris | `core/src/main.mjs` + stub — **kode kita sendiri**, murni JS | ditulis kita | 30KB | WAJIB (dipertahankan) |

Istilah "Termux"/"Alpine" hanyalah **sumber unduhan biner** (seperti kita mengambil
node dari nodejs.org) — bukan dependensi runtime. Bedah .deb ZCode Desktop itu sendiri
mengajarkan pola ini: aplikasi Linux = file + loader + libc; kita tinggal membawa
file-file itu di APK.

---

## B. Bukti lab BARU (x64 sandbox — engine boot TANPA proot & TANPA rootfs)

Dijalankan 13 Okt 2026. Payload lengkap (`zmain` 63MB + `zui` 14MB + `zhost` 42MB +
node v22.20.0 glibc resmi) diekstrak FLAT ke `/tmp/np/opt/zhost` — **tanpa rootfs,
tanpa proot** — lalu di-boot dengan env saja:

```
exec: /lib64/ld-linux-x86-64.so.2 --library-path <ROOT>/glibc/lib  <ROOT>/glibc/bin/node  core/src/main.mjs
env : ZCODE_APPROOT=<ROOT> ZCODE_PORT ZCODE_RES ZCODE_USERDATA HOME TMPDIR LD_LIBRARY_PATH=<ROOT>/glibc/lib
```

| Tes | Hasil | Arti |
|---|---|---|
| E1: `GET /health` | **200** | bridge + main ASLI hidup tanpa proot |
| E1: `GET /` | **302** → `index.html?restoreSession=true&initialWorkspacePath=…&zcodeLaunchMarks=…` | boot alur asli identik E2E v4.x |
| E1: `GET /index.html` | **200 (27.978 B)** | renderer ASLI tersaji |
| E2: `process.dlopen(pty.node)` | **LOAD_OK** | native module glibc kritis (terminal) termuat hanya dengan `LD_LIBRARY_PATH` |
| E3: `sshcrypto.node` | FAIL → ternyata **ELF ARM64** tercampur di pohon x64 | **bukti fisik payload lama CAMPUR ARSITEKTUR** → gerbang verify-payload.py wajib |
| E4: audit path absolut `out/main/index.js` | hanya string deteksi browser (`lsappinfo`, `mdfind`, `google-chrome`); **0 hardcode `/opt`** | tidak ada terjemahan path yang dibutuhkan |

**Kenapa bisa tanpa proot?** Karena bridge kita env-driven: `main.mjs` baris 10:
`const ROOT = process.env.ZCODE_APPROOT`. Satu-satunya alasan proot dipakai selama ini
adalah interp `PT_INTERP` node (`/lib64/ld-linux-aarch64.so.1`) — dan itu diselesaikan
tanpa proot (bagian E, gaya D1/D2).

---

## C. Post-mortem: kenapa titik gagal selalu di area ini

| Versi | Lapisan yang gagal | Bukti |
|---|---|---|
| v4.0.0–4.0.1 | Tar extractor (Java) — bukan sandbox | EISDIR `glib-2.0/inc`; diperbaiki + harness 7 tar identik GNU tar |
| v4.0.2 | Pipe stdout 64KB penuh (Java) — bukan sandbox | spinner ∞; fix: thread baca terus |
| v4.0.3 | **proot/loader**: rootfs musl tak punya loader glibc | exit 255 senyap; repack lib64 |
| v4.0.4 | **proot** vanilla diganti proot Termux — **BELUM PERNAH DITES DI DEVICE** | user berhenti sebelum uji |

**Kejujuran revisi 2:** klaim revisi-1 "proot Termux ✓" TIDAK berdasar (belum diuji
device). Seluruh kegagalan device menumpuk di lapisan proot/loader; **engine sendiri
belum pernah sempat gagal** (2× E2E x64 lulus; kini juga lulus tanpa proot — bagian B).
Kesimpulan: lapisan paling berisiko justru komponen yang paling bisa dibuang.

---

## D. TEMUAN BARU dari diskusi ini: DNS glibc (bug laten yang belum pernah ketemu)

glibc membaca `/etc/resolv.conf` untuk DNS. **File itu TIDAK ADA di Android** — dan
`proot -R` pun tidak akan menemukannya di host Android. Artinya: meski proot jalan,
**login/API pasti gagal** saat DNS. Ini penjelasan mengapa area ini butuh diskusi matang.

**Mitigasi (murni JS, di bridge, verifikasi di desktop):**
`dns-shim.cjs` dimuat via `node --require`; aktif otomatis hanya bila
`/etc/resolv.conf` tidak ada; mem-patch `dns.lookup`/`dns.promises.lookup` → DoH ke
`1.1.1.1` (IP literal — tidak butuh DNS untuk bootstrap) + cache. Desktop diuji dengan
shim dipaksa ON.

---

## E. Arsitektur boot v1.0 — TANGGA GAYA (satu instalasi menguji semua)

Probe pertama-boot ~30 detik, coba berurutan, pilih yang lulus, simpan pilihan,
SALIN LOG menampilkan gaya terpilih + hasil tiap gaya:

| Gaya | Perintah exec | Catatan |
|---|---|---|
| **D1 (utama)** | `node` langsung — `PT_INTERP` dipatchelf ke `/data/user/0/id.zcode.mobile/files/sandbox/glibc/lib/ld-linux-aarch64.so.1` | `process.execPath` benar → spawn CLI agent (zcode.cjs) beres |
| **D2** | `<ROOT>/glibc/lib/ld-linux-aarch64.so.1 --library-path … node …` | 1 baris di bridge: override `process.execPath` → `ZCODE_NODE` |
| **T (fallback diam)** | proot Termux + busybox rootfs (file tetap dibawa: 350KB + ~1MB) | hanya bila D1+D2 gagal probe |

**Syarat sistem (fakta, bukan pilihan):** `targetSdk` WAJIB 28 — Android memblok
exec/dlopen file di penyimpanan aplikasi untuk targetSdk ≥ 29 (alasan yang sama Termux
menarget 28). Manifest kita SUDAH 28 ✓ (terbukti: log device v4.0.3 `probe proot
exit=0` — exec memang diizinkan di perangkat Anda).

**Alt yang DITOLAK (dengan alasan):** node bionic Termux — mustahil karena native
module engine (`pty.node`, `sharp`/libvips, `koffi`, `sshcrypto`) adalah ELF **glibc**
(hasil bedah .deb arm64, GLIBC ≤ 2.28); proses bionic tidak bisa memuat .so glibc.
Kompilasi ulang sharp/libvips/koffi/pty utk bionic = porting C/C++ besar. Jadi
**glibc runtime + node = satu-satunya tambahan yang tidak tergantikan.**

---

## F. Payload v1.0 final

```
<filesDir>/sandbox/
├── glibc/lib/                    (jammy: libc, libstdc++, libgcc_s, libm, libpthread,
│    │                             librt, libutil, ld-linux-aarch64.so.1)
│    └── bin/node                 (v22 arm64 resmi; PT_INTERP dipatchelf → gaya D1)
├── zcode/                        (1:1 DARI .deb arm64: out/{main,preload,renderer,host,
│    │                             scheduler}, node_modules, res/{app.asar*,glm,tools},
│    │                             apps/zcode-cli)
├── core/                         (main.mjs + ws-bridge + electron-stub + host-worker +
│    │                             dns-shim.cjs)        ← kode kita, murni JS
├── data/                         (userdata)
└── fb/                           (fallback: proot Termux + loader + libs + busybox)
```

Dibuang dari .deb: 8 ELF electron/GPU (~220MB). Dibuang dari v4.x: rootfs Alpine penuh,
proot dari jalur utama. Semua ELF arm64 — dijaga gerbang verify.

## G. Pipeline build (revisi 1 tetap + tambahan)

- Fase A assemble 1:1 dari .deb arm64 (checksum tercatat).
- Fase B **verify-payload.py (GERBANG — build GAGAL bila pelanggaran)**:
  arsitektur semua ELF = arm64 (pelajaran E3) · interpreter/NEEDED resoluble **dengan
  LD_LIBRARY_PATH saja (simulasi tanpa proot)** · GLIBC ≤ 2.35 · path kritikal · hash.
- Fase C patchelf PT_INTERP node (D1) + tanam dns-shim + build APK (versionName 1.0.0,
  versionCode 100).
- Fase D E2E desktop **gaya-D** (bukan proot): boot → /health 200 → renderer 200 →
  load pty → dns-shim ON dipaksa → fetch DoH OK.
- Fase E rilis TUNGGAL v1.0.0. Tidak ada lagi v4.0.x.

## H. Kriteria selesai

1. verify-payload.py: 0 pelanggaran.
2. E2E desktop gaya-D lulus semua (termasuk dns-shim & pty).
3. Device: boot otomatis pilih D1/D2/T; renderer Desktop tampil; kalau gagal, SALIN
   LOG menunjukkan lapisan + hasil probe tiap gaya (satu laporan = diagnosis penuh).
4. Dokumen ini + hasil verify ter-commit (jejak audit).

## I. Keputusan yang saya minta dari Anda

- **K1** — Setuju proot + rootfs keluar dari jalur utama (jadi fallback otomatis di
  `fb/`), boot utama = node glibc langsung (D1/D2)? `ya / tidak`
- **K2** — Setuju aturan iterasi baru: rilis baru HANYA setelah ada log SALIN LOG dari
  device (satu log = satu perbaikan berbasis bukti, bukan tebakan)? `ya / tidak`

Setelah K1 & K2 disetujui → eksekusi Fase A–E tanpa menunggu lagi.
