#!/bin/bash
# ============================================================
#  ZCode Mobile — Build APK Native Tanpa Gradle
#  Pipeline: aapt2 (resources) -> ECJ (javac) -> d8 (dex)
#            -> pack -> zipalign -> apksigner
# ============================================================
set -e

ROOT="/home/z/my-project/zcode-android"
BT="$HOME/android-toolchain/bt/android-14"
PLATFORM="$HOME/android-toolchain/platforms/android-34/android.jar"
ECJ="$HOME/android-toolchain/ecj.jar"
OUT="$ROOT/build-out"
# runtime library untuk biner build-tools (zipalign dst)
export LD_LIBRARY_PATH="$BT/lib64:$LD_LIBRARY_PATH"

cd "$ROOT"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
echo "▶ [1/7] aapt2 compile resources..."
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "▶ [2/7] aapt2 link (manifest + resources + R.java)..."
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "▶ [3/7] ECJ compile Java (mode pra-modul, bootclasspath = android.jar)..."
java -jar "$ECJ" -nowarn -encoding UTF-8 \
  -source 8 -target 8 \
  -bootclasspath "$HOME/android-toolchain/stubs.jar:$PLATFORM" \
  -d "$OUT/classes" \
  $(find java "$OUT/gen" -name "*.java" 2>/dev/null)

echo "▶ [4/7] d8: classes -> classes.dex..."
"$BT/d8" --release \
  --lib "$PLATFORM" \
  --min-api 26 \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

echo "▶ [5/7] Pack classes.dex ke APK..."
python3 - << 'EOF'
import zipfile, shutil, os
src = "/home/z/my-project/zcode-android/build-out/base.apk"
dst = "/home/z/my-project/zcode-android/build-out/unsigned.apk"
dex = "/home/z/my-project/zcode-android/build-out/dex/classes.dex"
shutil.copy(src, dst)
with zipfile.ZipFile(dst, "a") as z:
    if os.path.exists(dex):
        z.write(dex, "classes.dex", compress_type=zipfile.ZIP_STORED)
print("packed:", dst, os.path.getsize(dst), "bytes")
EOF

echo "▶ [6/7] zipalign..."
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "▶ [7/7] apksigner sign..."
if [ ! -f "$ROOT/zcode.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/zcode.keystore" -alias zcode \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass zcodemobile -keypass zcodemobile \
    -dname "CN=ZCode Mobile, OU=Dev, O=ZCodeMobile, C=ID"
fi
"$BT/apksigner" sign --ks "$ROOT/zcode.keystore" \
  --ks-pass pass:zcodemobile --key-pass pass:zcodemobile \
  --out "$OUT/ZCodeMobile.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify "$OUT/ZCodeMobile.apk" && echo "✅ TERVERIFIKASI"
ls -lh "$OUT/ZCodeMobile.apk"
echo "🎉 BUILD SUKSES: $OUT/ZCodeMobile.apk"
