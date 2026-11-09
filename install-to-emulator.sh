#!/bin/bash

###############################################################################
# TVBoxAndroid 一键安装脚本
# 
# 功能: 自动等待模拟器启动，安装 APK，启动应用
# 用法: ./install-to-emulator.sh [device_id]
###############################################################################

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE="${1:-}"
PACKAGE="com.hong.tvbox"
APK="$PROJECT_DIR/dist/app-release.apk"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║        TVBoxAndroid v1.2.0 自动安装脚本                        ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# ============================================================================
# 检查 APK 是否存在
# ============================================================================

if [ ! -f "$APK" ]; then
    echo "❌ APK 文件不存在: $APK"
    echo ""
    echo "请先构建 APK:"
    echo "  ./gradlew assembleRelease"
    exit 1
fi

APK_SIZE=$(du -h "$APK" | cut -f1)
echo "✅ APK 已就绪: app-release.apk ($APK_SIZE)"
echo ""

# ============================================================================
# 等待设备/模拟器上线
# ============================================================================

echo "⏳ 等待 Android 设备上线..."
echo "   提示: 请启动模拟器或连接设备"
echo ""

# 如果指定了设备 ID
if [ -n "$DEVICE" ]; then
    echo "   目标设备: $DEVICE"
    # 等待特定设备
    while ! adb -s "$DEVICE" get-state 2>/dev/null | grep -q "device"; do
        echo "   等待设备 $DEVICE..."
        sleep 3
    done
else
    # 等待任意设备
    adb wait-for-device
    
    # 获取第一个在线设备
    DEVICE=$(adb devices | grep -v "List" | grep "device$" | head -1 | cut -f1)
    
    if [ -z "$DEVICE" ]; then
        echo "❌ 未检测到设备"
        exit 1
    fi
fi

echo "✅ 设备已连接: $DEVICE"
echo ""

# ============================================================================
# 检查设备信息
# ============================================================================

echo "📱 设备信息:"
ANDROID_VERSION=$(adb -s "$DEVICE" shell getprop ro.build.version.release 2>/dev/null || echo "未知")
MODEL=$(adb -s "$DEVICE" shell getprop ro.product.model 2>/dev/null || echo "未知")
echo "   型号: $MODEL"
echo "   Android 版本: $ANDROID_VERSION"
echo ""

# ============================================================================
# 卸载旧版本（可选）
# ============================================================================

if adb -s "$DEVICE" shell pm list package | grep -q "$PACKAGE"; then
    echo "🗑️  检测到旧版本，正在卸载..."
    adb -s "$DEVICE" uninstall "$PACKAGE" 2>/dev/null || true
    echo "✅ 旧版本已卸载"
    echo ""
fi

# ============================================================================
# 安装 APK
# ============================================================================

echo "📦 安装 APK..."
if adb -s "$DEVICE" install -r "$APK" 2>&1 | tee /tmp/install.log | grep -q "Success"; then
    echo "✅ APK 安装成功"
else
    echo "❌ 安装失败"
    cat /tmp/install.log
    exit 1
fi
echo ""

# ============================================================================
# 验证安装
# ============================================================================

echo "🔍 验证安装..."
if adb -s "$DEVICE" shell pm list package | grep -q "$PACKAGE"; then
    echo "✅ 应用已安装"
    
    # 获取版本信息
    VERSION=$(adb -s "$DEVICE" shell dumpsys package "$PACKAGE" 2>/dev/null | grep "versionName" | head -1 | cut -d'=' -f2)
    echo "   版本: $VERSION"
else
    echo "❌ 验证失败：应用未安装"
    exit 1
fi
echo ""

# ============================================================================
# 启动应用
# ============================================================================

echo "🚀 启动应用..."
adb -s "$DEVICE" shell am start -n "$PACKAGE/.ui.MainActivity" > /dev/null 2>&1

sleep 2

# 验证进程
if adb -s "$DEVICE" shell ps | grep -q "$PACKAGE"; then
    echo "✅ 应用已启动并运行"
    
    # 获取进程信息
    PID=$(adb -s "$DEVICE" shell ps | grep "$PACKAGE" | head -1 | awk '{print $2}')
    echo "   进程 ID: $PID"
else
    echo "⚠️  应用启动失败或已崩溃"
    echo ""
    echo "查看日志:"
    echo "  adb -s $DEVICE logcat -d | grep -i tvbox | tail -20"
    exit 1
fi

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                     ✅ 安装完成！                             ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "📱 设备: $DEVICE"
echo "📦 应用: $PACKAGE v$VERSION"
echo "🎯 状态: 运行中"
echo ""
echo "📋 常用命令:"
echo "  查看日志:  adb -s $DEVICE logcat -s TVBox"
echo "  重启应用:  adb -s $DEVICE shell am force-stop $PACKAGE && \\"
echo "             adb -s $DEVICE shell am start -n $PACKAGE/.ui.MainActivity"
echo "  卸载应用:  adb -s $DEVICE uninstall $PACKAGE"
echo ""
