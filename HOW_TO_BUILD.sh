#!/bin/bash
# TVBox Android - 云端构建脚本（无需本地 SDK）

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  TVBox Android - 项目打包方案"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "检测到本机无 Android SDK。提供以下方案："
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "方案 1: 安装 Android Studio（推荐）"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "1. 下载 Android Studio:"
echo "   https://developer.android.com/studio"
echo
echo "2. 安装后打开项目:"
echo "   open -a \"Android Studio\" ."
echo
echo "3. 等待 Gradle 同步完成（首次 5-10 分钟）"
echo
echo "4. 点击菜单: Build → Build APK(s)"
echo
echo "5. 产物位置:"
echo "   app/build/outputs/apk/debug/app-debug.apk"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "方案 2: 使用 GitHub Actions 云端构建"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "1. 将项目推送到 GitHub"
echo
echo "2. 在仓库中创建 .github/workflows/build.yml:"
cat << 'WORKFLOW'

name: Build APK
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '17'
      - name: Build
        run: |
          cd TVBoxAndroid
          chmod +x gradlew
          ./gradlew assembleDebug
      - uses: actions/upload-artifact@v3
        with:
          name: app-debug
          path: TVBoxAndroid/app/build/outputs/apk/debug/*.apk

WORKFLOW
echo
echo "3. 推送代码后，GitHub 自动构建"
echo
echo "4. 在 Actions 页面下载 APK"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "方案 3: 只安装命令行工具（极简）"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "1. 下载命令行工具:"
echo "   https://developer.android.com/studio#command-tools"
echo
echo "2. 解压到 ~/android-sdk"
echo
echo "3. 设置环境变量:"
echo "   export ANDROID_HOME=~/android-sdk"
echo "   export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin"
echo
echo "4. 安装必要组件:"
echo "   sdkmanager --install \"platforms;android-34\" \"build-tools;34.0.0\""
echo
echo "5. 构建:"
echo "   ./gradlew assembleDebug"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "方案 4: 传输到其他机器打包"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "1. 打包项目:"
echo "   tar czf TVBoxAndroid.tar.gz TVBoxAndroid/"
echo
echo "2. 传输到有 Android Studio 的机器"
echo
echo "3. 解压并打包:"
echo "   tar xzf TVBoxAndroid.tar.gz"
echo "   cd TVBoxAndroid"
echo "   ./gradlew assembleDebug"
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "推荐方案: 方案 1（Android Studio）最简单"
echo "下载地址: https://developer.android.com/studio"
echo
