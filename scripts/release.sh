#!/usr/bin/env bash
# Uso: scripts/release.sh 1.0.1 "notas del release"
# Sube versionName/versionCode, compila el APK release firmado, hace commit+tag y publica en GitHub Releases.
set -euo pipefail

VERSION="${1:?uso: scripts/release.sh <version> [notas]}"
NOTES="${2:-MangaBona $VERSION}"
cd "$(dirname "$0")/.."

export JAVA_HOME="$HOME/toolchain/jdk21"
export ANDROID_HOME="$HOME/toolchain/android-sdk"
export PATH="$JAVA_HOME/bin:$HOME/toolchain/gh/bin:$PATH"

CODE=$(grep -oP 'versionCode = \K\d+' app/build.gradle.kts)
sed -i "s/versionCode = $CODE/versionCode = $((CODE + 1))/; s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" app/build.gradle.kts

./gradlew :app:assembleRelease --console=plain

OUT=app/build/outputs/apk/release
STAGE=$(mktemp -d)
cp "$OUT/app-arm64-v8a-release.apk" "$STAGE/MangaBona-arm64-v8a.apk"
cp "$OUT/app-armeabi-v7a-release.apk" "$STAGE/MangaBona-armeabi-v7a.apk"
cp "$OUT/app-universal-release.apk" "$STAGE/MangaBona-universal.apk"

git add -A
git commit -m "MangaBona $VERSION"
git tag "v$VERSION"
git push origin main "v$VERSION"

gh release create "v$VERSION" "$STAGE"/*.apk --repo guardvalker/mangabona --title "MangaBona $VERSION" --notes "$NOTES"
