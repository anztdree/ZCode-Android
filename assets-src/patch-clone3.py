#!/usr/bin/env python3
# ============================================================
# patch-clone3.py — nonaktifkan syscall clone3 di glibc bundel.
#
# POST-MORTEM v1.0.0 (itel S665L, Android 12 / SDK 31):
#   probe D1/D2 → exit=159 = 128+31 → proses dibunuh SIGSYS oleh
#   filter seccomp aplikasi Android (syscall tidak di-whitelist
#   dibunuh langsung, BUKAN diberi ENOSYS — lihat docs Android
#   "native crashes": SIGSYS/SYS_SECCOMP).
#   glibc >= 2.34 memakai clone3 untuk pthread_create/posix_spawn;
#   fallback ke clone legacy HANYA terjadi bila clone3 mengembalikan
#   -ENOSYS. Di Android 12 clone3 tidak di-whitelist → node mati
#   sebelum sempat mencetak apa pun (persis log v1.0.0).
# SOLUSI: ganti stub syscall `mov x8,#220; svc #0` (arm64) /
#   `mov eax,220; syscall` (x64) menjadi `return -ENOSYS` sehingga
#   glibc jatuh ke clone legacy yang di-whitelist Android.
# ============================================================
import os
import struct
import sys

# e_machine → (pola lama, pola baru, arsitektur)
PATTERNS = {
    183: (bytes.fromhex("881b80d2010000d4"),  # mov x8, #220 ; svc #0
          bytes.fromhex("a0048012c0035fd6"),  # mov w0, #-38 ; ret  (-38 = ENOSYS)
          "arm64"),
    62:  (bytes.fromhex("b8dc0000000f05"),    # mov eax, 220 ; syscall
          bytes.fromhex("b8daffffffc3"),      # mov eax, -38 ; ret
          "x64"),
}


def patch_one(path):
    """Kembalikan (situs_dipatch, situs_sudah_dipatch) atau None bila bukan ELF target."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        return None
    if data[:4] != b"\x7fELF":
        return None
    em, = struct.unpack_from("<H", data, 18)
    if em not in PATTERNS:
        return None
    old, new, arch = PATTERNS[em]
    n_old, n_new = data.count(old), data.count(new)
    if n_old == 0:
        return (0, n_new)
    with open(path, "r+b") as f:
        pos = 0
        while True:
            i = data.find(old, pos)
            if i < 0:
                break
            f.seek(i)
            f.write(new)
            pos = i + len(old)
    print(f"  dipatch [{arch}]: {path} → {n_old} situs clone3 → return -ENOSYS")
    return (n_old, n_new)


def walk(targets, do_patch):
    patched = remaining = already = 0
    for t in targets:
        if os.path.isdir(t):
            for dirpath, _dirs, files in os.walk(t):
                for fn in sorted(files):
                    p = os.path.join(dirpath, fn)
                    if os.path.islink(p):
                        continue
                    r = patch_one(p)
                    if r is None:
                        continue
                    n, a = r
                    already += a
                    if do_patch:
                        patched += n
                    else:
                        if n:
                            print(f"  [SISA-AKTIF] {p}: {n} situs clone3 belum dipatch")
                        remaining += n
        else:
            r = patch_one(t)
            if r is not None:
                n, a = r
                already += a
                if do_patch:
                    patched += n
                else:
                    if n:
                        print(f"  [SISA-AKTIF] {t}: {n} situs clone3 belum dipatch")
                    remaining += n
    return patched, remaining, already


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("patch", "check"):
        print("pakai: patch-clone3.py patch|check <file|dir> [file|dir...]")
        sys.exit(2)
    mode = sys.argv[1]
    targets = sys.argv[2:]
    patched, remaining, already = walk(targets, do_patch=(mode == "patch"))
    if mode == "patch":
        print(f"selesai: {patched} situs baru dipatch, {already} sudah sebelumnya")
        sys.exit(0)
    if remaining:
        print(f"❌ masih ada {remaining} situs clone3 AKTIF (akan SIGSYS di Android 12+)")
        sys.exit(1)
    print(f"✅ bersih: 0 situs clone3 aktif ({already} sudah return -ENOSYS)")


if __name__ == "__main__":
    main()
