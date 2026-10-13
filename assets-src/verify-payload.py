#!/usr/bin/env python3
# ============================================================
# ZCode Mobile v1.0 — verify-payload.py (GERBANG WAJIB)
# Build GAGAL bila ada pelanggaran:
#  1. Semua ELF harus arsitektur target (pelajaran v4.x: payload campur arsitektur)
#  2. Semua DT_NEEDED harus resoluble (dir sendiri / RUNPATH $ORIGIN / glibc/lib)
#     — simulasi Android TANPA proot: hanya LD_LIBRARY_PATH=glibc/lib
#  3. Simbol GLIBC maksimum ≤ 2.35 (versi bundel)
#  4. PT_INTERP node = path absolut app (mekanisme D1)
#  5. Path kritikal ada
# ============================================================
import os, re, struct, sys

TARGET = sys.argv[1] if len(sys.argv) > 1 else "arm64"          # arm64 | amd64
PAYLOAD = sys.argv[2] if len(sys.argv) > 2 else "/home/z/assemble/" + ("arm64" if TARGET == "arm64" else "amd64")
GLIBC = os.path.join(PAYLOAD, "glibc", "lib")

EM = {"arm64": 183, "amd64": 62}                                # e_machine
MAGIC_INTERP = "/data/user/0/id.zcode.mobile/files/sandbox/glibc/lib/" if TARGET == "arm64" else None

violations = []
checked = {"elf": 0, "needed": 0}

def elf_machine(path):
    with open(path, "rb") as f:
        h = f.read(20)
    if h[:4] != b"\x7fELF":
        return None
    return struct.unpack_from("<H", h, 18)[0]

def is_elf(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False

def parse_dynamic(path):
    """return (needed_list, runpath_list, interp, glibc_versions)"""
    with open(path, "rb") as f:
        data = f.read()
    if data[4:5] != b"\x02":
        return [], [], None, set()                               # bukan ELF64 LE
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        sh_type, = struct.unpack_from("<I", data, off + 4)
        sh_offset, = struct.unpack_from("<Q", data, off + 0x18)
        sh_size, = struct.unpack_from("<Q", data, off + 0x20)
        sh_link, = struct.unpack_from("<I", data, off + 0x28)
        sections.append((sh_type, sh_offset, sh_size, sh_link))
    needed, runpath, vers = [], [], set()
    dyn_off = dyn_size = None
    strtab_off = None
    for t, off, size, link in sections:
        if t == 6 and dyn_off is None:                           # SHT_DYNAMIC
            dyn_off, dyn_size = off, size
        elif t == 11 and strtab_off is None:                     # SHT_DYNSYM → sh_link = .dynstr
            if link < len(sections):
                strtab_off = sections[link][1]
    if dyn_off is None:
        return needed, runpath, None, vers
    def getstr(v):
        if strtab_off is None:
            return ""
        end = data.index(b"\x00", strtab_off + v)
        return data[strtab_off + v:end].decode("utf-8", "replace")
    for i in range(dyn_size // 16):
        tag, val = struct.unpack_from("<qQ", data, dyn_off + i * 16)
        if tag == 0:
            break
        if tag == 1:                                             # DT_NEEDED
            needed.append(getstr(val)); checked["needed"] += 1
        elif tag in (15, 29):                                    # DT_RPATH / DT_RUNPATH
            runpath.append(getstr(val))
    # PT_INTERP
    interp = None
    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    for i in range(e_phnum):
        off2 = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from("<I", data, off2)
        if p_type == 3:                                          # PT_INTERP
            p_offset, = struct.unpack_from("<Q", data, off2 + 8)
            end = data.index(b"\x00", p_offset)
            interp = data[p_offset:end].decode()
    vers = set(re.findall(rb"GLIBC_2\.\d+", data))
    return needed, runpath, interp, vers

def resolve(name, elf_dir, runpaths):
    cands = [os.path.join(elf_dir, name)]
    for rp in runpaths:
        rp = rp.replace("$ORIGIN", elf_dir).replace("${ORIGIN}", elf_dir)
        for p in rp.split(":"):
            if p:
                cands.append(os.path.join(p, name))
    cands.append(os.path.join(GLIBC, name))
    return any(os.path.exists(c) for c in cands)

print(f"=== verify-payload: target={TARGET} payload={PAYLOAD} ===")

# --- 1&2&3: scan semua ELF di payload ---
libcache = {}
for root, _dirs, files in os.walk(PAYLOAD):
    if "/.debx" in root:
        continue
    for fn in files:
        p = os.path.join(root, fn)
        if not is_elf(p):
            continue
        checked["elf"] += 1
        em = elf_machine(p)
        if em is not None and em != EM[TARGET]:
            # KNOWN-BUG terdokumentasi: .deb resmi x64 ZCode membawa sshcrypto.node
            # ber-arsitektur arm64 di dalam app.asar (bug pipeline ZCode sendiri).
            # ssh2 punya fallback JS — engine tetap boot (terbukti E2E). Hanya relevan x64.
            if TARGET == "amd64" and fn == "sshcrypto.node":
                print(f"  [catatan] known-bug .deb x64: {p} (arm64, fallback JS ssh2)")
                continue
            violations.append(f"ARSITEKTUR SALAH: {p} (e_machine={em})")
            continue
        needed, runpaths, _interp, vers = parse_dynamic(p)
        for v in vers:
            n = int(v.decode().split(".")[1])
            if n > 35:
                violations.append(f"GLIBC terlalu baru: {p} minta {v.decode()} (> 2.35)")
        for n in needed:
            if n.startswith("ld-linux"):
                continue                                          # loader = PT_INTERP
            if not resolve(n, os.path.dirname(p), runpaths):
                violations.append(f"NEEDED tidak resoluble: {p} → {n}")

# --- 4: PT_INTERP node ---
node = os.path.join(PAYLOAD, "glibc", "bin", "node")
_n, _r, interp, _v = parse_dynamic(node)
if TARGET == "arm64":
    if interp != MAGIC_INTERP + "ld-linux-aarch64.so.1":
        violations.append(f"PT_INTERP node salah: {interp}")

# --- 5: path kritikal ---
critical = [
    "glibc/bin/node",
    "zhost/core/src/main.mjs",
    "zhost/core/src/ws-bridge.mjs",
    "zhost/core/src/electron-stub.mjs",
    "zhost/core/src/host-worker.mjs",
    "zhost/core/src/dns-shim.cjs",
    "zhost/out/main/index.js",
    "zhost/out/preload/index.cjs",
    "zhost/out/renderer/index.html",
    "zhost/out/host/index.js",
    "zhost/out/scheduler/index.js",
    "zhost/res/glm/zcode.cjs",
    "zhost/res/app.asar.unpacked/node_modules/node-pty/prebuilds/linux-arm64/pty.node" if TARGET == "arm64" else "zhost/res/app.asar.unpacked/node_modules/node-pty/prebuilds/linux-x64/pty.node",
    "zhost/res/tools/ripgrep/rg",
    "zhost/res/tools/ugrep/ugrep",
    "zhost/res/tools/bfs/bfs",
    "zhost/apps/zcode-cli/packages/cli/dist/zcode.cjs",
    "zhost/node_modules/electron/index.mjs",
]
for c in critical:
    if not os.path.exists(os.path.join(PAYLOAD, c)):
        violations.append(f"PATH KRITIKAL HILANG: {c}")

# --- 6: clone3 wajib sudah dipatch (Android 12+ membunuh clone3 dgn SIGSYS —
#        post-mortem v1.0.0: exit=159 = 128+31 di itel S665L) ---
CLONE3_OLD = {183: bytes.fromhex("881b80d2010000d4"),   # mov x8,#220; svc #0 (arm64)
              62:  bytes.fromhex("b8dc0000000f05")}      # mov eax,220; syscall (x64)
clone3_sites = 0
for root, _dirs, files in os.walk(GLIBC):
    for fn in sorted(files):
        p = os.path.join(root, fn)
        if os.path.islink(p) or not is_elf(p):
            continue
        old = CLONE3_OLD.get(elf_machine(p))
        if old:
            with open(p, "rb") as f:
                n = f.read().count(old)
            if n:
                clone3_sites += n
                violations.append(f"CLONE3 AKTIF ({n} situs): {p} — jalankan patch-clone3.py patch")

print(f"ELF diperiksa: {checked['elf']}, NEEDED diperiksa: {checked['needed']}")
if violations:
    print(f"❌ GAGAL — {len(violations)} pelanggaran:")
    for v in violations[:40]:
        print("  -", v)
    sys.exit(1)
print("✅ verify-payload LULUS — 0 pelanggaran")
