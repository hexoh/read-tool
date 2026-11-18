#!/usr/bin/env bash
# 为 release 签名准备 keystore，并写入 android/keystore.properties（该文件已被 .gitignore 排除）。
# 构建脚本只认 keystore.properties，所以密钥是谁生成的都可以：
#   脚本新建 / 你自己用 keytool 或 Android Studio 生成 / 复用以前发过版的密钥。
set -euo pipefail

ALIAS="readtool"
OUT="$HOME/.keystores/readtool.jks"
PASS="${KEYSTORE_PASSWORD:-}"
REUSE=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

usage() {
  cat <<'USAGE'
用法：
  bash tools/make-keystore.sh                        新建密钥，密码随机生成
  bash tools/make-keystore.sh -p 'MyPass123'         新建密钥，自己指定密码
  bash tools/make-keystore.sh -e -o ~/my/key.jks     使用已有密钥（只写配置，不生成）

选项：
  -o, --out PATH        keystore 路径（默认 ~/.keystores/readtool.jks）
  -p, --password PASS   密码（新建时不填则随机生成；复用时必填或交互输入）
  -a, --alias NAME      key 别名（默认 readtool）
  -e, --existing        复用 --out 指定的已有密钥，不生成新文件
  -h, --help            显示本帮助

示例：
  # 用 Android Studio「Generate Signed APK」生成的密钥
  bash tools/make-keystore.sh -e -o ~/keys/release.jks
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    -o|--out)      OUT="$2"; shift 2 ;;
    -p|--password) PASS="$2"; shift 2 ;;
    -a|--alias)    ALIAS="$2"; shift 2 ;;
    -e|--existing) REUSE=1; shift ;;
    -h|--help)     usage; exit 0 ;;
    -*)            echo "未知选项：$1"; usage; exit 1 ;;
    *)             OUT="$1"; shift ;;
  esac
done

if ! command -v "$KEYTOOL" >/dev/null 2>&1; then
  echo "✗ 找不到 keytool。请确认 JDK 已安装且 JAVA_HOME 指向它。"
  exit 1
fi

if [ "$REUSE" -eq 1 ]; then
  # ---------- 复用已有密钥 ----------
  if [ ! -f "$OUT" ]; then
    echo "✗ 文件不存在：$OUT"
    exit 1
  fi
  if [ -z "$PASS" ]; then
    if [ -t 0 ]; then
      read -rsp "请输入该 keystore 的密码: " PASS; echo
    else
      echo "✗ 复用模式需要密码：加 -p '密码' 或设置 KEYSTORE_PASSWORD"
      exit 1
    fi
  fi
  if ! "$KEYTOOL" -list -keystore "$OUT" -storepass "$PASS" -alias "$ALIAS" >/dev/null 2>&1; then
    echo "✗ 校验失败：密码错误，或别名 '$ALIAS' 不存在。"
    echo "  查看已有别名：$KEYTOOL -list -keystore \"$OUT\" -storepass '***'"
    echo "  别名不对时用 -a 指定。"
    exit 1
  fi
else
  # ---------- 新建密钥 ----------
  if [ -e "$OUT" ]; then
    echo "✗ 已存在：$OUT"
    echo "  想复用它请加 -e；想覆盖请先自行备份删除。"
    exit 1
  fi
  mkdir -p "$(dirname "$OUT")"
  if [ -z "$PASS" ]; then
    PASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)"
    echo "（密码随机生成）"
  fi
  "$KEYTOOL" -genkeypair -v \
    -keystore "$OUT" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=readtool, OU=dev, O=readtool, C=CN"
fi

cat > "$ROOT/android/keystore.properties" <<PROPS
storeFile=$OUT
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
PROPS

# macOS 的 base64 用 -i；Linux 用 -w0
if base64 -i "$OUT" >/dev/null 2>&1; then
  base64 -i "$OUT" | tr -d '\n' > "$OUT.base64"
else
  base64 -w0 "$OUT" > "$OUT.base64"
fi

echo
echo "✓ keystore  : $OUT"
echo "✓ 别名      : $ALIAS"
echo "✓ base64    : $OUT.base64"
echo "✓ 本地签名  : android/keystore.properties（已被 git 忽略）"
echo
echo "—— 填进 GitHub → Settings → Secrets and variables → Actions ——"
echo "  KEYSTORE_BASE64 = \$(cat \"$OUT.base64\")"
echo "  STORE_PASSWORD  = $PASS"
echo "  KEY_ALIAS       = $ALIAS"
echo "  KEY_PASSWORD    = $PASS"
echo
echo "⚠ 务必备份 keystore 文件与密码；丢失后无法再更新已发布的应用。"
echo "⚠ 已对外发布过的应用，必须一直用同一把密钥，不能重新生成。"
