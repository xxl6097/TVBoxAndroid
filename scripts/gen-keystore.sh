#!/usr/bin/env bash
# 生成 release 签名密钥，并把参数写进 local.properties（不入库）。
#
# 用法：
#   ./scripts/gen-keystore.sh            # 默认口令 het002402，可自行修改
#   ./scripts/gen-keystore.sh 你的口令
#
# 生成后：
#   ./gradlew assembleRelease           # 出已签名 release 包
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KS="$ROOT/tvbox-release.jks"
PASS="${1:-het002402}"
ALIAS="tvbox"

if [ -f "$KS" ]; then
    echo "密钥已存在：$KS（如需重建请先删除）"
else
    keytool -genkeypair -v \
        -keystore "$KS" \
        -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$PASS" -keypass "$PASS" \
        -dname "CN=TVBox, OU=Personal, O=Personal, L=Yinchuan, ST=Ningxia, C=CN" \
        >/dev/null
    echo "✓ 已生成 $KS"
fi

PROPS="$ROOT/local.properties"
touch "$PROPS"

# 清掉旧值再追加，避免重复
grep -v -E '^(KEYSTORE_PATH|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)=' "$PROPS" > "$PROPS.tmp" || true
{
    echo "KEYSTORE_PATH=$KS"
    echo "KEYSTORE_PASSWORD=$PASS"
    echo "KEY_ALIAS=$ALIAS"
    echo "KEY_PASSWORD=$PASS"
} >> "$PROPS.tmp"
mv "$PROPS.tmp" "$PROPS"

echo "✓ 已写入 $PROPS"
echo
echo "下一步："
echo "  ./gradlew assembleRelease"
echo "  产物：app/build/outputs/apk/release/app-release.apk"
echo
echo "注意：$KS 与 local.properties 都已在 .gitignore 中，不要提交。"
