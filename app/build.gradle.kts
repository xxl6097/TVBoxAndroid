// Android TV 项目构建配置
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hong.tvbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hong.tvbox"
        minSdk = 21
        targetSdk = 34
        // versionCode 用 git commit 数量自动递增（CI 环境没有 git 时 fallback 80）
        versionCode = try {
            val proc = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
            proc.waitFor()
            proc.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 80
        } catch (e: Exception) { 80 }
        versionName = "1.3.3"
    }

    /**
     * Release 签名。
     *
     * 密钥不进仓库：从环境变量或 local.properties 读，读不到就不配置签名
     * （此时 assembleRelease 只出未签名包，CI 的 debug 构建完全不受影响）。
     * 生成密钥：scripts/gen-keystore.sh
     */
    val ksPath = (project.findProperty("KEYSTORE_PATH") as String?)
        ?: System.getenv("KEYSTORE_PATH")
    val ksPass = (project.findProperty("KEYSTORE_PASSWORD") as String?)
        ?: System.getenv("KEYSTORE_PASSWORD")
    val ksAlias = (project.findProperty("KEY_ALIAS") as String?)
        ?: System.getenv("KEY_ALIAS")
    val ksKeyPass = (project.findProperty("KEY_PASSWORD") as String?)
        ?: System.getenv("KEY_PASSWORD")

    signingConfigs {
        /**
         * 固定 debug 签名。
         *
         * AGP 自带一个名为 debug 的签名配置（指向 ~/.android/debug.keystore），
         * 但 CI 每次构建都是全新的 runner，那个密钥每次都不一样，导致每次出的包
         * 签名不同、无法覆盖安装。这里把它指向仓库里固定的密钥（口令都是 android，
         * 公开无风险），保证连续构建同一签名。
         */
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (ksPath != null && File(ksPath).exists() && ksPass != null && ksAlias != null) {
            create("release") {
                storeFile = File(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass ?: ksPass
            }
        }
    }

    buildTypes {
        release {
            // 有签名配置就签，没有就保持未签名（不阻断构建）
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    // ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.0")
    
    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // ViewModel & LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    
    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Glide 图片加载
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // 二维码（扫码录入订阅，电视上免手输）
    implementation("com.google.zxing:core:3.5.3")

    // Lifecycle scope for Fragment/Activity
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // 单元测试（WebDAV 解析等纯逻辑）
    testImplementation("junit:junit:4.13.2")
}
