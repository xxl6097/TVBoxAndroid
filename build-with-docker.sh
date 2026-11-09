#!/bin/bash

# 使用 Docker 构建 APK (确保本机装了 Docker)
# 这样可以在容器内使用官方 Android SDK 镜像，无需担心本地 JDK 版本

echo "检查 Docker 可用性..."
if ! command -v docker &> /dev/null; then
    echo "❌ Docker 未安装，改用本地构建降级方案"
    exit 1
fi

docker run --rm \
    -v "$(pwd):/workspace" \
    -w /workspace \
    -e GRADLE_USER_HOME=/workspace/.gradle \
    runmymind/docker-android-sdk:ubuntu-standalone \
    bash -c "
        echo '构建 APK...'
        ./gradlew clean assembleDebug --no-daemon -x lint
        echo '✅ 构建完成'
        ls -lh app/build/outputs/apk/debug/
    "
