plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // 版本已在根 build.gradle.kts 通过 alias(libs.plugins.ksp) apply false 声明
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.mangareader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mangareader"
        minSdk = 26
        targetSdk = 35

        // 版本号约定（用户要求）：每有一次功能/修复改动，versionName 就 +0.1，
        // 即 1.0 → 1.1 → 1.2 → …。改完必须重新打包，不能沿用旧 APK。
        //
        // versionCode 由 versionName 直接推导（1.1 → 11），保证两者永远同步递增。
        // 不能只改 versionName 而漏掉 versionCode：系统要求覆盖安装时 versionCode
        // 必须大于已装版本，否则报 INSTALL_FAILED_VERSION_DOWNGRADE 装不上。
        // 1.0 是历史版本（当时 versionCode 手写成 1），从这里开始按「×10」推算，
        // 1.1 → 11 仍然大于 1，覆盖安装不受影响。
        val appVersionName = "1.0"
        versionCode = appVersionName.substringBefore(".").toInt() * 10 +
            appVersionName.substringAfter(".").toInt()
        versionName = appVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8 起 BuildConfig 默认关闭；MangaReaderNav 里的调试入口要用 BuildConfig.DEBUG
        buildConfig = true
    }

    // 发布签名：从 local.properties 读取（该文件已被 .gitignore 排除，不提交版本库）。
    // 需要在 local.properties 里提供：
    //   RELEASE_STORE_FILE=release-keystore.jks
    //   RELEASE_STORE_PASSWORD=你的密码
    //   RELEASE_KEY_ALIAS=mangareader
    //   RELEASE_KEY_PASSWORD=你的密码
    // 未提供时（例如克隆仓库后尚未配置），release 回退到 debug 签名，仍可构建，
    // 但产物不能用于正式发布。
    val localProps = java.util.Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasReleaseKey = localProps.getProperty("RELEASE_STORE_FILE") != null
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = rootProject.file(localProps.getProperty("RELEASE_STORE_FILE")!!)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // 暂不开混淆：Compose / Coil / Room 的 keep 规则还没配齐，
            // 贸然开启容易把反射用到的类删掉，导致运行时崩溃。
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 解决 Apache Commons Compress 与 OkHttp 的依赖元信息冲突
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

// KSP 顶层扩展，不能放进 android {}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ---------------- Compose 基础与架构 ----------------
    implementation(libs.androidx.core.ktx)
    // Android 12+ 官方启动开屏（向后兼容到低版本）：需在 Activity 里 installSplashScreen()
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // by viewModels() 委托（MainActivity 里创建 MainViewModel）
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    // 图标：Icons.AutoMirrored / Icons.Filled.* 需要这个库，material3 不自带
    implementation(libs.androidx.compose.material.icons.extended)

    // ---------------- 图像加载与大图手势缩放 ----------------
    // Coil：异步图像加载引擎
    implementation(libs.coil.compose)
    // Telephoto：专为 Compose 设计的分块渲染与手势缩放组件（防大图 OOM）
    implementation(libs.telephoto.zoomable.image.coil)

    // ---------------- WebDAV 与网络通信 ----------------
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    // Sardine-Android：封装了 PROPFIND 等 WebDAV 标准动词的客户端
    implementation(libs.sardine.android)

    // ---------------- 本地压缩包与文件处理 ----------------
    // 支持流式读取 ZIP、CBZ、TAR、7Z 等档案文件
    implementation(libs.commons.compress)
    // 7z 的 LZMA/LZMA2 解码需要 xz；若不需要 7z 可去掉
    implementation(libs.tukaani.xz)
    // RAR：commons-compress 不支持，换 junrar（提供 ArchiveInputStream 式取流）
    implementation(libs.junrar)
    // 注：PDF 优先使用 Android 系统内置的 android.graphics.pdf.PdfRenderer，无需额外增加依赖

    // ---------------- 本地持久化与凭证加密 ----------------
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // DataStore：存储用户阅读偏好（翻页方向、双页裁切设置等）
    implementation(libs.androidx.datastore.preferences)
    // Security Crypto：硬件级加密存储 WebDAV 服务器登录凭据与 Token
    implementation(libs.androidx.security.crypto)

    // ---------------- 折叠屏 / 大屏适配 ----------------
    // 监听折叠屏开合状态、铰链位置与展开宽度
    implementation(libs.androidx.window)

    // ---------------- 协程并发支持 ----------------
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
