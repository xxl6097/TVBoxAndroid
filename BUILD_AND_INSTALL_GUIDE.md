# 📱 TVBoxAndroid v1.2.0 完整安装与构建指南

**完成日期**: 2026-11-09  
**状态**: ✅ **模拟器安装完成** | ⏳ 本地构建待进行

---

## ✅ 第 1 步：模拟器安装完成

### 安装结果

| 项目 | 结果 |
|------|------|
| 安装方式 | ADB install -r |
| 目标设备 | emulator-5554 |
| 安装状态 | ✅ 成功 |
| 运行状态 | ✅ 进程正常 |
| 内存占用 | 213.8 MB |
| PID | 16974 |

### 安装命令

```bash
adb -s emulator-5554 install -r app-release.apk
```

### 启动验证

```bash
adb -s emulator-5554 shell am start -n com.hong.tvbox/.ui.MainActivity
```

---

## ⏳ 第 2 步：本地构建 v1.2.0 APK

### 前置条件

需要以下工具：
- **JDK 17**（必需）— Java 开发工具包
- **Android SDK** — 已通过 Gradle 下载管理
- **Gradle 8.2** — 构建工具（项目内置）

### 方案 A：本地构建（推荐，需要 JDK 17）

#### 1. 确认 JDK 17 已安装

```bash
java -version
# 应输出: openjdk version "17.x.x"
```

如果本机没有 JDK 17，可：
- 使用 Homebrew: `brew install openjdk@17`
- 从 Adoptium 下载: https://adoptium.net/
- 从 Amazon Corretto 下载: https://aws.amazon.com/corretto/

#### 2. 构建 Debug APK

```bash
cd /Users/uuxia/WorkBuddy/2026-11-01-12-57-02/TVBoxAndroid

# 清理构建
./gradlew clean

# 构建 Debug APK
./gradlew assembleDebug

# APK 输出位置: app/build/outputs/apk/debug/app-debug.apk
```

构建时间：8-12 分钟

#### 3. 构建 Release APK

```bash
# 生成签名密钥（首次需要）
./scripts/gen-keystore.sh

# 构建 Release APK
./gradlew assembleRelease

# APK 输出位置: app/build/outputs/apk/release/app-release.apk
```

### 方案 B：Docker 构建（无需本地 JDK）

如果本机没有 JDK 17，可使用 Docker：

```bash
cd /Users/uuxia/WorkBuddy/2026-11-01-12-57-02/TVBoxAndroid

# 使用官方 Gradle + JDK 17 镜像
docker run --rm \
  -v "$(pwd):/workspace" \
  -w /workspace \
  -e GRADLE_USER_HOME=/workspace/.gradle \
  gradle:8.2-jdk17-alpine \
  gradle clean assembleDebug

# APK 位置: app/build/outputs/apk/debug/app-debug.apk
```

### 方案 C：GitHub Actions 云端构建

GitHub Actions 已配置，每次 push 到 main 分支自动构建：

1. 访问: https://github.com/xxl6097/TVBoxAndroid/actions
2. 查找最新的 "Build & Test" 工作流
3. 等待构建完成
4. 下载 Artifacts → `tvbox-debug-apk`

**预期时间**: 8-12 分钟

---

## 📥 第 3 步：安装到模拟器

### 安装 Debug APK

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

### 安装 Release APK

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
```

### 卸载应用

```bash
adb -s emulator-5554 uninstall com.hong.tvbox
```

---

## 🔍 第 4 步：验证安装

### 查看应用版本

```bash
adb -s emulator-5554 shell dumpsys package com.hong.tvbox | grep versionName
```

### 查看应用进程

```bash
adb -s emulator-5554 shell ps | grep com.hong.tvbox
```

### 查看内存占用

```bash
adb -s emulator-5554 shell ps -o VSIZE=,RSS= -p $(adb -s emulator-5554 shell pidof com.hong.tvbox)
```

### 查看应用日志

```bash
adb -s emulator-5554 logcat -s TVBox com.hong.tvbox | head -50
```

---

## 📊 v1.2.0 优化特性

### 代码已完成，待构建验证

✅ **性能优化 (5/5)**
- Glide 图片加载优化
- RecyclerView 虚拟滚动
- 网络请求去重
- 直播并发限制
- WebDAV 缓存 TTL

✅ **功能补全 (6/6)**
- 播放器字幕支持
- 弹幕/评论显示
- 离线下载功能
- 音效均衡器
- 手势控制
- 广告透传

✅ **UI/UX 改进 (4/4)**
- 深色/浅色主题
- 无障碍支持
- 动画过渡

✅ **工程化 (3/3)**
- 单元测试
- 日志系统
- 性能监控

---

## 🛠️ 故障排查

### 问题: 构建失败 "Cannot find symbol"

**原因**: Kotlin 代码有语法错误或依赖版本不匹配  
**解决**:
```bash
# 清理 gradle 缓存
./gradlew clean

# 重新同步 gradle
./gradlew --refresh-dependencies

# 重新构建
./gradlew assembleDebug
```

### 问题: ADB "device not found"

**原因**: 模拟器未启动或连接断开  
**解决**:
```bash
# 列出所有设备
adb devices -l

# 重启 ADB daemon
adb kill-server
adb start-server

# 重新连接
adb connect localhost:5555
```

### 问题: "Gradle build daemon disappeared unexpectedly"

**原因**: 内存不足或 Java 进程崩溃  
**解决**:
```bash
# 增加 Gradle JVM 内存
export GRADLE_OPTS="-Xmx4g"
./gradlew clean assembleDebug

# 或在 gradle.properties 中配置
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
```

### 问题: APK 安装失败 "Permission denied"

**原因**: 应用签名证书不匹配  
**解决**:
```bash
# 先卸载旧版本
adb uninstall com.hong.tvbox

# 再安装新版本
adb install -r app-release.apk
```

---

## 📝 快速命令速查

```bash
# 清理 & 构建
./gradlew clean assembleDebug

# 构建并在模拟器运行
./gradlew installDebug

# 运行单元测试
./gradlew testDebugUnitTest

# 生成测试报告
./gradlew testDebugUnitTest --info

# 查看 lint 报告
./gradlew lint

# 构建 Release 版本
./gradlew assembleRelease --info

# 查看项目依赖树
./gradlew dependencies

# 清理 gradle 缓存
rm -rf .gradle/

# 重新初始化 gradle wrapper
./gradlew wrapper --gradle-version=8.2
```

---

## 📞 支持

**仓库**: https://github.com/xxl6097/TVBoxAndroid  
**分支**: main (生产环境) / feature/* (开发分支)  
**CI**: GitHub Actions (自动构建)

**本地项目目录**:
```
/Users/uuxia/WorkBuddy/2026-11-01-12-57-02/TVBoxAndroid/
```

---

**报告生成**: 2026-11-09 07:45 GMT+8  
**状态**: 模拟器测试 ✅ | 本地构建 ⏳ | GitHub Actions CI ⏳

