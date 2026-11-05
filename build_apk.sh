#!/bin/bash
set -e

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  TVBox Android - 构建脚本"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

# 检查 Android SDK
if [ ! -d "$HOME/Library/Android/sdk" ]; then
    echo "❌ 未找到 Android SDK"
    echo "   请安装 Android Studio 或设置 ANDROID_HOME"
    exit 1
fi

echo "✓ Android SDK: $HOME/Library/Android/sdk"

# 检查 Gradle
if ! command -v gradle &> /dev/null; then
    echo "⚠️  系统未安装 Gradle，将使用 Gradle Wrapper"
    GRADLE_CMD="./gradlew"
else
    GRADLE_VERSION=$(gradle --version | grep "Gradle" | awk '{print $2}')
    echo "✓ Gradle: $GRADLE_VERSION"
    GRADLE_CMD="gradle"
fi

echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  开始构建"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

# 清理旧构建
echo "1. 清理旧构建..."
rm -rf app/build/outputs/apk/

# 构建 Debug APK
echo
echo "2. 构建 Debug APK..."
$GRADLE_CMD assembleDebug

# 检查产物
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$DEBUG_APK" ]; then
    APK_SIZE=$(du -h "$DEBUG_APK" | awk '{print $1}')
    echo
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  ✅ 构建成功"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo
    echo "产物位置: $DEBUG_APK"
    echo "文件大小: $APK_SIZE"
    echo
    echo "下一步："
    echo "  adb install $DEBUG_APK"
    echo
else
    echo
    echo "❌ 构建失败：未找到 APK 文件"
    exit 1
fi
