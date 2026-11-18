#!/usr/bin/env bash
# 检查本机是否具备构建 / 打包条件。换电脑或新人接手时先跑这个。
#
#   bash tools/doctor.sh
#
# 只做检查，不改任何文件。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
KEYTOOL_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
ok=0
bad=0

pass() { echo "  [ok]   $*"; ok=$((ok + 1)); }
fail() { echo "  [缺失] $*"; bad=$((bad + 1)); }
warn() { echo "  [注意] $*"; }

echo "Java"
if command -v "$JAVA_BIN" >/dev/null 2>&1; then
  JV="$("$JAVA_BIN" -version 2>&1 | head -1)"
  case "$JV" in
    *'"21'*) pass "JDK 21 — $JV" ;;
    *)       fail "需要 JDK 21，当前是 $JV" ;;
  esac
else
  fail "未找到 java（JAVA_HOME=${JAVA_HOME:-未设置}）"
fi

echo "Android SDK"
SDK=""
if [ -f "$ROOT/android/local.properties" ]; then
  SDK="$(grep -E '^sdk\.dir=' "$ROOT/android/local.properties" | cut -d= -f2-)"
fi
if [ -z "$SDK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then
  pass "SDK 目录 $SDK"
  if [ -d "$SDK/platforms/android-35" ]; then
    pass "platforms;android-35"
  else
    fail "缺 platforms;android-35（compileSdk 35）"
  fi
  if [ -d "$SDK/build-tools/35.0.0" ]; then
    pass "build-tools;35.0.0"
  else
    fail "缺 build-tools;35.0.0"
  fi
else
  fail "未找到 SDK：在 android/local.properties 写 sdk.dir=...，或设置 ANDROID_HOME"
fi

echo "Gradle"
if [ -x "$ROOT/android/gradlew" ]; then
  WL="$(grep distributionUrl "$ROOT/android/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null \
        | sed 's/.*gradle-\(.*\)-bin\.zip/\1/')"
  pass "gradlew 可执行（wrapper ${WL:-未知}，首次构建会自动下载）"
else
  fail "android/gradlew 不存在或不可执行"
fi

echo "签名"
KP="$ROOT/android/keystore.properties"
if [ -f "$KP" ]; then
  SF="$(grep -E '^storeFile=' "$KP" | cut -d= -f2-)"
  SP="$(grep -E '^storePassword=' "$KP" | cut -d= -f2-)"
  KA="$(grep -E '^keyAlias=' "$KP" | cut -d= -f2-)"
  if [ ! -f "$SF" ]; then
    fail "keystore.properties 指向的文件不存在：$SF"
  elif "$KEYTOOL_BIN" -list -keystore "$SF" -storepass "$SP" -alias "$KA" >/dev/null 2>&1; then
    pass "keystore 可用（别名 $KA）"
  else
    fail "keystore 存在，但密码或别名不对"
  fi
else
  warn "没有 android/keystore.properties：release 包会未签名；需要时跑 tools/make-keystore.sh"
fi

echo "可选（只有重建数据 / 音频时才需要）"
command -v node >/dev/null 2>&1 && pass "node $(node -v)" || warn "未装 node —— 仅影响 tools/build-data.js"
command -v python3 >/dev/null 2>&1 && pass "python3 $(python3 -V 2>&1 | cut -d' ' -f2)" || warn "未装 python3 —— 仅影响音频脚本与 OCR 校验"

echo
if [ "$bad" -eq 0 ]; then
  echo "全部就绪（$ok 项通过），可以 ./gradlew assembleDebug / assembleRelease"
else
  echo "$ok 项通过，$bad 项缺失 —— 按上面的提示补齐"
  exit 1
fi
