#!/bin/bash
# ============================================================
# ZCode Mobile v1.0 — assemble-v10.sh <arm64|amd64>
# Payload 1:1 dari .deb resmi ZCode 3.15.1 + glibc jammy resmi
# + node v22 resmi + bridge kita. TANPA proot, TANPA rootfs.
# ============================================================
set -euo pipefail
ARCH="${1:?pakai: assemble-v10.sh arm64|amd64}"
DL=/home/z/dl
DSK=/home/z/dsk
SRC=/home/z/ZCode-Android/assets-src
OUT=/home/z/assemble/$ARCH
DEBDIR=$DL/deb-arm64
[ "$ARCH" = amd64 ] && DEBDIR=$DL/deb-x64
APP=$DSK/app
[ "$ARCH" = amd64 ] && APP=$DSK/app-x64
NODE_VER=22.20.0
NODEARCH=$ARCH
[ "$ARCH" = amd64 ] && NODEARCH=x64

echo "▶ [1/8] bersihkan target"
rm -rf "$OUT"; mkdir -p "$OUT/zhost" "$OUT/glibc/bin" "$OUT/glibc/lib"

echo "▶ [2/8] pohon app dari app.asar (out/ + node_modules + package.json)"
cp -a "$APP/package.json" "$OUT/zhost/"
mkdir -p "$OUT/zhost/out"
cp -a "$APP/out/." "$OUT/zhost/out/"
cp -a "$APP/node_modules" "$OUT/zhost/node_modules"

echo "▶ [3/8] stub electron + electron-updater (timp paket asli bila ada)"
mkdir -p "$OUT/zhost/node_modules/electron"
cat > "$OUT/zhost/node_modules/electron/package.json" << 'EOF'
{"name":"electron","version":"33.4.11","description":"stub for ZCode Mobile","type":"module","main":"index.mjs","exports":{".":"./index.mjs","./package.json":"./package.json"}}
EOF
cat > "$OUT/zhost/node_modules/electron/index.mjs" << 'EOF'
export * from '../../core/src/electron-stub.mjs'
import e from '../../core/src/electron-stub.mjs'
export default e
EOF
rm -rf "$OUT/zhost/node_modules/electron-updater"
mkdir -p "$OUT/zhost/node_modules/electron-updater"
cat > "$OUT/zhost/node_modules/electron-updater/package.json" << 'EOF'
{"name":"electron-updater","version":"6.8.3","description":"stub for ZCode Mobile","type":"module","main":"main.js","exports":{".":"./main.js"}}
EOF
cat > "$OUT/zhost/node_modules/electron-updater/main.js" << 'EOF'
import { EventEmitter } from 'node:events'
class Base extends EventEmitter {
  constructor() { super(); this.autoDownload = true; this.autoInstallOnAppQuit = false; this.allowPrerelease = false; this.logger = console }
  setFeedURL() { }
  get feedURL() { return '' }
  set feedURL(v) { }
  checkForUpdates() { return Promise.resolve(null) }
  checkForUpdatesAndNotify() { return Promise.resolve(null) }
  downloadUpdate() { return Promise.resolve(null) }
  quitAndInstall() { }
  isUpdaterActive() { return false }
}
class AppImageUpdater extends Base { }
class NsisUpdater extends Base { }
class MacUpdater extends Base { }
class DebUpdater extends Base { }
class RpmUpdater extends Base { }
class PacmanUpdater extends Base { }
class AppUpdater extends Base { }
class BaseUpdater extends Base { }
class Provider extends class { } { }
class CancellationToken { constructor() { this.cancelled = false } cancel() { this.cancelled = true } }
const autoUpdater = new AppImageUpdater()
export { autoUpdater, AppImageUpdater, NsisUpdater, MacUpdater, DebUpdater, RpmUpdater, PacmanUpdater, AppUpdater, BaseUpdater, Provider, CancellationToken }
export default { autoUpdater }
EOF

echo "▶ [4/8] bridge core (main.mjs + ws-bridge + electron-stub + host-worker + dns-shim)"
mkdir -p "$OUT/zhost/core"
cp -a "$SRC/core/src" "$OUT/zhost/core/src"

echo "▶ [5/8] res 1:1 dari .deb (app.asar.unpacked + config + glm + tools) + apps/zcode-cli"
mkdir -p "$OUT/zhost/res"
cp -a "$DEBDIR/opt/ZCode/resources/app.asar.unpacked" "$OUT/zhost/res/"
cp -a "$DEBDIR/opt/ZCode/resources/config" "$OUT/zhost/res/"
cp -a "$DEBDIR/opt/ZCode/resources/glm" "$OUT/zhost/res/"
cp -a "$DEBDIR/opt/ZCode/resources/tools" "$OUT/zhost/res/"
mkdir -p "$OUT/zhost/apps/zcode-cli/packages/cli/dist"
cp -a "$DEBDIR/opt/ZCode/resources/glm/zcode.cjs" "$OUT/zhost/apps/zcode-cli/packages/cli/dist/zcode.cjs"

echo "▶ [6/8] glibc runtime dari .deb Ubuntu jammy resmi"
TMP="$OUT/.debx"; mkdir -p "$TMP"
for d in "$DL"/debs-$ARCH/*.deb; do dpkg-deb -x "$d" "$TMP"; done
TRIPLET=aarch64-linux-gnu
[ "$ARCH" = amd64 ] && TRIPLET=x86_64-linux-gnu
FOUND=0
while IFS= read -r d; do
  cp -a "$d"/ld-*.so* "$OUT/glibc/lib/" 2>/dev/null || true
  cp -a "$d"/lib*.so* "$OUT/glibc/lib/" 2>/dev/null || true
  FOUND=1
done < <(find "$TMP" -type d -name "$TRIPLET")
[ "$FOUND" = 1 ] || { echo "FATAL: dir triplet $TRIPLET tidak ditemukan"; exit 1; }
rm -rf "$TMP"
echo "   libs: $(ls "$OUT/glibc/lib" | wc -l) file"

echo "▶ [7/8] node v$NODE_VER resmi (nodejs.org) + strip debug info"
tar xJf "$DL/node22-$ARCH.tar.xz" -C "$OUT/glibc/bin" --strip-components=2 "node-v$NODE_VER-linux-$NODEARCH/bin/node"
chmod +x "$OUT/glibc/bin/node"
LLVM_STRIP=$(find /home/z/toolchain/bt -name llvm-strip 2>/dev/null | head -1)
A64_STRIP=$(find /home/z/toolchain/binutils-a64 -name "aarch64-linux-gnu-strip" 2>/dev/null | head -1)
if [ -n "$LLVM_STRIP" ]; then
  "$LLVM_STRIP" "$OUT/glibc/bin/node"
elif [ -n "$A64_STRIP" ] && [ "$ARCH" = arm64 ]; then
  BFD=$(find /home/z/toolchain/binutils-a64 -name "libbfd*" | head -1)
  [ -n "$BFD" ] && export LD_LIBRARY_PATH="$(dirname "$BFD"):${LD_LIBRARY_PATH:-}"
  "$A64_STRIP" "$OUT/glibc/bin/node"
elif command -v strip > /dev/null && [ "$ARCH" = amd64 ]; then
  strip "$OUT/glibc/bin/node"
fi
echo "   node: $(du -h "$OUT/glibc/bin/node" | cut -f1)"

echo "▶ [8/8] patchelf PT_INTERP node (mekanisme D1 — loader absolut milik app)"
LOADER=$(ls "$OUT/glibc/lib"/ld-linux-*.so.* | head -1)
if [ "$ARCH" = arm64 ]; then
  INTERP="/data/user/0/id.zcode.mobile/files/sandbox/glibc/lib/$(basename "$LOADER")"
else
  INTERP="$OUT/glibc/lib/$(basename "$LOADER")"
fi
uvx patchelf --set-interpreter "$INTERP" "$OUT/glibc/bin/node"
echo "   PT_INTERP → $INTERP"

rm -rf "$OUT/.debx"
echo "✅ ASSEMBLE $ARCH SELESAI: $(du -sh "$OUT" | cut -f1)"
