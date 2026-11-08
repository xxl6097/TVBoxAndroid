#!/bin/bash

###############################################################################
# TVBoxAndroid v1.2.0 一键构建脚本
# 
# 功能: 使用 Docker 构建 APK 并安装到模拟器
# 用法: ./build-v1.2.0.sh [debug|release] [install]
# 
# 示例:
#   ./build-v1.2.0.sh debug install      # 构建 Debug APK 并安装
#   ./build-v1.2.0.sh release            # 只构建 Release APK
###############################################################################

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_TYPE="${1:-debug}"
AUTO_INSTALL="${2:-}"
DEVICE="emulator-5554"
PACKAGE="com.hong.tvbox"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║        TVBoxAndroid v1.2.0 一键构建脚本                        ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "📋 配置:"
echo "  项目目录: $PROJECT_DIR"
echo "  构建类型: $BUILD_TYPE"
echo "  自动安装: ${AUTO_INSTALL:-否}"
echo "  目标设备: $DEVICE"
echo ""

# ============================================================================
# 方案 1: 检查本地 JDK 17
# ============================================================================

echo "🔍 检查 JDK 17..."

JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || true)

if [ -n "$JAVA_HOME" ] && [ -d "$JAVA_HOME" ]; then
    echo "✅ 找到 JDK 17: $JAVA_HOME"
    echo ""
    echo "🔨 开始本地构建..."
    
    cd "$PROJECT_DIR"
    export JAVA_HOME=$JAVA_HOME
    export GRADLE_OPTS="-Xmx4g -Dfile.encoding=UTF-8"
    
    if [ "$BUILD_TYPE" = "debug" ]; then
        ./gradlew clean assembleDebug --no-daemon
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    else
        ./gradlew clean assembleRelease --no-daemon
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
    fi
else
    echo "❌ 未找到 JDK 17"
    echo ""
    echo "尝试方案 2: Docker 构建..."
    echo ""
    
    # ========================================================================
    # 方案 2: Docker 构建
    # ========================================================================
    
    if ! command -v docker &> /dev/null; then
        echo "❌ Docker 未安装"
        echo ""
        echo "请选择以下方案之一:"
        echo "  1. 安装 JDK 17:  brew install openjdk@17"
        echo "  2. 安装 Docker:  https://www.docker.com/products/docker-desktop"
        echo "  3. 使用 GitHub Actions 自动构建"
        exit 1
    fi
    
    echo "🐳 使用 Docker 构建..."
    
    docker run --rm \
        -v "$PROJECT_DIR:/workspace" \
        -w /workspace \
        -e GRADLE_USER_HOME=/workspace/.gradle \
        gradle:8.2-jdk17-alpine \
        gradle clean assemble${BUILD_TYPE^} --no-daemon
    
    if [ "$BUILD_TYPE" = "debug" ]; then
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    else
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
    fi
fi

# ============================================================================
# 验证 APK 是否生成
# ============================================================================

echo ""
echo "🔍 验证构建产物..."

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK 未生成: $APK_PATH"
    exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo "✅ APK 已生成"
echo "   路径: $APK_PATH"
echo "   大小: $APK_SIZE"

# ============================================================================
# 自动安装到模拟器（如果指定了 install 参数）
# ============================================================================

if [ "$AUTO_INSTALL" = "install" ]; then
    echo ""
    echo "📱 安装到模拟器..."
    
    # 检查设备
    if ! adb devices | grep -q "$DEVICE"; then
        echo "⚠️  设备 $DEVICE 未在线"
        echo "   运行: adb devices"
        exit 1
    fi
    
    # 卸载旧版本（可选）
    adb -s $DEVICE uninstall $PACKAGE 2>/dev/null || true
    
    # 安装新版本
    adb -s $DEVICE install -r "$APK_PATH"
    
    echo "✅ APK 已安装"
    
    # 启动应用（可选）
    echo ""
    echo "🚀 启动应用..."
    adb -s $DEVICE shell am start -n $PACKAGE/.ui.MainActivity
    
    sleep 2
    
    # 验证进程
    if adb -s $DEVICE shell ps | grep -q $PACKAGE; then
        echo "✅ 应用已启动并运行"
    else
        echo "⚠️  应用启动失败"
    fi
fi

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                     ✅ 构建完成！                             ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "📦 APK 路径: $APK_PATH"
echo ""
echo "📱 安装命令:"
echo "   adb -s $DEVICE install -r $APK_PATH"
echo ""
echo "🚀 启动命令:"
echo "   adb -s $DEVICE shell am start -n $PACKAGE/.ui.MainActivity"
echo ""

