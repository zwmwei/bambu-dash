#!/usr/bin/env bash
# 不依赖 Gradle 的 APK 构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -e

SDK="${ANDROID_SDK:-/d/Program Files/Android/SDK}"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-33/android.jar"
JDK="${JAVA_HOME_BIN:-/d/Program Files/Android/OpenJDK/bin}"

cd "$(dirname "$0")"
OUT="build"
APP="bambu-dash.apk"

echo "[1/6] 准备资源与 assets"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" assets
cp ../web/index.html assets/index.html

"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip" >/dev/null

echo "[2/6] aapt2 link"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  -A assets \
  --min-sdk-version 24 \
  --target-sdk-version 33 \
  --no-version-vectors \
  "$OUT/res.zip"

echo "[3/6] 编译 Java"
find src -name "*.java" > "$OUT/sources.txt"
"$JDK/javac.exe" -source 8 -target 8 -nowarn \
  -bootclasspath "$AJ" -classpath "$AJ" \
  -encoding UTF-8 -d "$OUT/classes" \
  @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1 || { cat "$OUT/javac.log"; exit 1; }

echo "[4/6] dex 转换 (d8)"
find "$OUT/classes" -name "*.class" > "$OUT/classlist.txt"
"$JDK/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJ" --min-api 24 --release \
  --output "$OUT/dex" \
  @"$OUT/classlist.txt"

echo "[5/6] 打包 dex + 对齐"
( cd "$OUT/dex" && "$JDK/jar.exe" uf "../base.apk" classes.dex )
"$BT/zipalign.exe" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
if [ ! -f release.jks ]; then
  "$JDK/keytool.exe" -genkeypair -keystore release.jks -alias bambu \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass bambudash -keypass bambudash \
    -dname "CN=Bambu Dash, O=Xingxing, C=CN" >/dev/null 2>&1
fi

"$JDK/java.exe" -jar "$BT/lib/apksigner.jar" sign \
  --ks release.jks --ks-pass pass:bambudash --key-pass pass:bambudash \
  --ks-key-alias bambu --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APP" "$OUT/aligned.apk"

"$JDK/java.exe" -jar "$BT/lib/apksigner.jar" verify --print-certs "$APP" | head -4

SIZE=$(du -h "$APP" | cut -f1)
echo ""
echo "✓ 构建完成: $(pwd)/$APP  ($SIZE)"
