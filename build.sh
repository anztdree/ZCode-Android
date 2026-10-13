#!/bin/bash
# ============================================================
#  ZCode Mobile v4 — Build APK Tanpa Gradle
#  aapt2 → ECJ → d8 → pack → zipalign → apksigner
# ============================================================
set -e
ROOT="/home/z/ZCode-Android"
BT="$HOME/toolchain/bt/android-14"
PLATFORM="$HOME/toolchain/platforms/android-34/android.jar"
ECJ="$HOME/toolchain/ecj.jar"
OUT="$ROOT/build-out"
export LD_LIBRARY_PATH="$BT/lib64:$LD_LIBRARY_PATH"

cd "$ROOT"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
echo "▶ [1/7] aapt2 compile resources..."
"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"

echo "▶ [2/7] aapt2 link (manifest + resources + assets)..."
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest app/AndroidManifest.xml \
  --java "$OUT/gen" \
  --auto-add-overlay \
  -A assets \
  "$OUT/res.zip"

echo "▶ [3/7] ECJ compile Java..."
java -jar "$ECJ" -nowarn -encoding UTF-8 \
  -source 8 -target 8 \
  -bootclasspath "$HOME/toolchain/bt/android-14/core-lambda-stubs.jar:$PLATFORM" \
  -d "$OUT/classes" \
  $(find app/java "$OUT/gen" -name "*.java" 2>/dev/null)

echo "▶ [4/7] d8: classes → classes.dex..."
"$BT/d8" --release \
  --lib "$PLATFORM" \
  --min-api 26 \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

echo "▶ [5/7] Pack classes.dex..."
python3 - << 'PYEOF'
import zipfile, shutil, os
src = "/home/z/ZCode-Android/build-out/base.apk"
dst = "/home/z/ZCode-Android/build-out/unsigned.apk"
dex = "/home/z/ZCode-Android/build-out/dex/classes.dex"
shutil.copy(src, dst)
with zipfile.ZipFile(dst, "a") as z:
    if os.path.exists(dex):
        z.write(dex, "classes.dex", compress_type=zipfile.ZIP_STORED)
print("packed:", dst, os.path.getsize(dst), "bytes")
PYEOF

echo "▶ [6/7] zipalign..."
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "▶ [7/7] apksigner sign..."
"$BT/apksigner" sign --ks "$ROOT/zcode.keystore" \
  --ks-pass pass:zcodemobile --key-pass pass:zcodemobile \
  --out "$OUT/ZCodeMobile.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/ZCodeMobile.apk" && echo "✅ TERVERIFIKASI"
ls -lh "$OUT/ZCodeMobile.apk"
echo "🎉 BUILD SUKSES: $OUT/ZCodeMobile.apk"
