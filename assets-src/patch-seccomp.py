#!/usr/bin/env python3
# ============================================================
# patch-seccomp.py — jembatan glibc ↔ policy seccomp app Android 12.
#
# POST-MORTEM v1.0.0/v1.0.1 (itel S665L, Android 12 / SDK 31):
#   exit=159 = 128+31 → dibunuh SIGSYS oleh seccomp aplikasi Android.
#   Whitelist resmi app Android 12 (bionic android12-d1-release:
#   SYSCALLS + ALLOWLIST − BLOCKLIST) TIDAK memuat:
#     set_robust_list(99)  ← DIPANGGIL glibc saat init tiap thread
#                            (baris ke-125 startup; sebelum main!)
#     rseq(293), epoll_pwait2(441), close_range(436), memfd_create(279)
#   bionic tidak memakai syscall-syscall ini; glibc memakai → program
#   glibc di app Android = SIGSYS instan tanpa output.
#   (v1.0.1 salah sasaran: mem-patch clone/220 — justru syscall
#   fallback yang DI-WHITELIST; clone3(435) bahkan tidak dipakai
#   glibc 2.35 arm64. Kesalahan diakui & diperbaiki di v1.0.2.)
# METODE: setiap situs `movz x8,#nr; [opsional 1-2 insn]; svc #0`
#   diganti svc-nya menjadi `mov w0, #-38` (ENOSYS) → pemanggil
#   melihat "kernel lama tidak punya syscall ini" dan memakai
#   jalur fallback (persis perilaku yang terbukti di qemu -strace).
#   clone(220) TIDAK disentuh — itu jalur pthread_create/fork utama.
# ============================================================
import struct
import sys

SVC = bytes.fromhex("010000d4")              # svc #0
ENOSYS = bytes.fromhex("a0048012")           # mov w0, #-38
MAX_GAP = 10                                 # insn antara movz dan svc (ld.so bootstrap: gap 7)

# nr → nama (untuk laporan)
TARGETS = {
    99:  "set_robust_list",
    293: "rseq",
    441: "epoll_pwait2",
    436: "close_range",
    279: "memfd_create",
}


def movz_imm(imm, reg="x"):
    """MOVZ Xd/Wd(X8), #imm — base 0xD2800000 (x) / 0x52800000 (w)."""
    base = 0xD2800000 if reg == "x" else 0x52800000
    return struct.pack("<I", base | (imm << 5) | 8)


def patch_file(path, do_patch=True):
    """Kembalikan dict {nama: [offset_svc]} untuk file; None bila bukan ELF arm64."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        return None
    if data[:4] != b"\x7fELF" or len(data) < 20:
        return None
    em, = struct.unpack_from("<H", data, 18)
    if em != 183:  # hanya arm64
        return None
    found = {}
    for nr, name in TARGETS.items():
        sites = []
        for reg in ("x", "w"):
            movz = movz_imm(nr, reg)
            i = 0
            while True:
                j = data.find(movz, i)
                if j < 0:
                    break
                i = j + 4
                # svc harus dalam MAX_GAP*4 byte setelah movz; lewati slot yang
                # sudah dipatch (mov w0,#-38) agar check tidak false-positive
                k = data.find(SVC, j + 4, j + 4 + MAX_GAP * 4)
                while k > 0:
                    if data[k:k+4] == ENOSYS:
                        k = data.find(SVC, k + 4, j + 4 + MAX_GAP * 4)
                        continue
                    break
                if k < 0:
                    continue
                sites.append(k)
        if sites:
            found[name] = (nr, sites)
    if not do_patch or not found:
        return found
    buf = bytearray(data)
    for name, (nr, sites) in found.items():
        for off in sites:
            buf[off:off + 4] = ENOSYS
        print(f"  dipatch {path}: {name}({nr}) × {len(sites)} svc → mov w0,#-38")
    with open(path, "wb") as f:
        f.write(bytes(buf))
    return found


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("patch", "check"):
        print("pakai: patch-seccomp.py patch|check <file|dir> [...]")
        sys.exit(2)
    mode = sys.argv[1]
    targets = sys.argv[2:]
    total_active = 0
    for t in targets:
        import os
        paths = []
        if os.path.isdir(t):
            for root, _d, files in os.walk(t):
                for fn in sorted(files):
                    paths.append(os.path.join(root, fn))
        else:
            paths.append(t)
        for p in paths:
            if os.path.islink(p):
                continue
            r = patch_file(p, do_patch=(mode == "patch"))
            if r:
                for name, (nr, sites) in r.items():
                    total_active += len(sites)
                    if mode == "check":
                        print(f"  [AKTIF] {p}: {name}({nr}) × {len(sites)} — akan SIGSYS di Android 12")
    if mode == "check":
        if total_active:
            print(f"❌ masih ada {total_active} situs syscall terlarang aktif")
            sys.exit(1)
        print("✅ bersih: 0 situs syscall-terlarang aktif (semua → ENOSYS)")
    else:
        print("patch selesai")


if __name__ == "__main__":
    main()
