# 打包指南

## 方式一：Android Studio（推荐）

```bash
# 1. 打开项目
open -a "Android Studio" /Users/uuxia/WorkBuddy/2026-11-01-12-57-02/TVBoxAndroid

# 2. 等待 Gradle 同步完成

# 3. Build → Build Bundle(s) / APK(s) → Build APK(s)

# 4. 产物位置
app/build/outputs/apk/debug/app-debug.apk
```

## 方式二：命令行（快速）

```bash
cd /Users/uuxia/WorkBuddy/2026-11-01-12-57-02/TVBoxAndroid

# 使用内置脚本
./build_apk.sh

# 或直接用 Gradle
./gradlew assembleDebug
```

## 方式三：手动构建

```bash
# 1. 确保 Android SDK 已安装
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools

# 2. 下载 Gradle Wrapper（首次需要）
gradle wrapper --gradle-version 8.2

# 3. 构建
./gradlew clean assembleDebug

# 4. 产物
ls -lh app/build/outputs/apk/debug/
```

## 安装到设备

```bash
# USB 连接
adb install app/build/outputs/apk/debug/app-debug.apk

# 无线连接（需先配对）
adb connect 192.168.1.x:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Release 版本（需签名）

```bash
# 1. 生成签名文件
keytool -genkey -v -keystore tvbox.keystore -alias tvbox \
  -keyalg RSA -keysize 2048 -validity 10000

# 2. 配置 app/build.gradle.kts
signingConfigs {
    create("release") {
        storeFile = file("../tvbox.keystore")
        storePassword = "your_password"
        keyAlias = "tvbox"
        keyPassword = "your_password"
    }
}

# 3. 构建
./gradlew assembleRelease
```

## 常见问题

### Q: Gradle 同步失败
```bash
# 清理缓存
rm -rf ~/.gradle/caches/
./gradlew clean
```

### Q: SDK 版本不匹配
```bash
# 安装所需 SDK
$ANDROID_HOME/tools/bin/sdkmanager "platforms;android-34"
$ANDROID_HOME/tools/bin/sdkmanager "build-tools;34.0.0"
```

### Q: 构建太慢
```bash
# 启用 Gradle 守护进程
echo "org.gradle.daemon=true" >> gradle.properties
echo "org.gradle.parallel=true" >> gradle.properties
```

## 当前状态

✅ 项目可编译  
✅ 核心层完整  
⚠️  UI 层待实现（当前只有占位界面）

构建后可安装运行，但功能有限，需要继续开发 UI 层。
