#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_NAME="$(grep -E 'versionName "' "$ROOT_DIR/app/build.gradle" | sed -E 's/.*versionName "([^"]+)".*/\1/')"
APK_NAME="family-tv-v${VERSION_NAME}-debug.apk"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-user}"

cd "$ROOT_DIR"
./gradlew assembleDebug
mkdir -p "$ROOT_DIR/apks"
cp "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$ROOT_DIR/apks/$APK_NAME"
echo "Wrote $ROOT_DIR/apks/$APK_NAME"
