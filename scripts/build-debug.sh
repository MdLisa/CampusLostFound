#!/bin/bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
if [ -d "$ROOT_DIR/.local-tools/jdk/Contents/Home" ]; then
  export JAVA_HOME="$ROOT_DIR/.local-tools/jdk/Contents/Home"
fi
if [ -d "$ROOT_DIR/.local-tools/android-sdk" ]; then
  export ANDROID_SDK_ROOT="$ROOT_DIR/.local-tools/android-sdk"
  if [ ! -f local.properties ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
  fi
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.local-tools/gradle-home}"
# Opt in only when this shell inherited an unavailable proxy.
if [ "${CAMPUS_DIRECT_NETWORK:-0}" = 1 ]; then
  unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
fi
sh ./gradlew assembleDebug lintDebug testDebugUnitTest --console=plain
mkdir -p 安装包
cp app/build/outputs/apk/debug/app-debug.apk 安装包/CampusLostFound-前端验收.apk
shasum -a 256 安装包/CampusLostFound-前端验收.apk > 安装包/SHA256.txt
printf '\nAPK: %s/安装包/CampusLostFound-前端验收.apk\n' "$ROOT_DIR"
