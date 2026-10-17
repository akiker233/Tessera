import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 从 local.properties（不入库）读取敏感/个性化配置，注入 BuildConfig。
// 未配置时留空串：OneDriveAuth.isConfigured() 会据此判定「未配置」并禁用登录入口。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val onedriveClientId = localProperties.getProperty("ONEDRIVE_CLIENT_ID", "")

// 签名信息从 keystore.properties（不入库）读取；文件不存在时 release 保持未签名，
// 便于 CI/新克隆环境照常构建。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.tessera.otp"
    // AndroidX 1.19+ 的 AAR 元数据要求 compileSdk >= 37；
    // targetSdk 仍保持 36，行为面向 Android 16/17 设备。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tessera.otp"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // OneDrive OAuth 客户端 ID：从 local.properties 注入，不硬编码进源码/仓库
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"$onedriveClientId\"")
        // AppAuth 要求在 manifest 占位符里提供重定向 scheme（Azure 里登记的 redirect URI 是 tessera://auth）
        manifestPlaceholders["appAuthRedirectScheme"] = "tessera"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // 按 ABI 拆分：SQLCipher 与 ML Kit 的原生库只打进对应架构的包，省掉其余架构的体积；
    // 仍保留 universal 包，方便不确定架构时直接安装（也便于模拟器/真机共用一份）
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Google API 客户端（Drive 用）会把 google-auth-library 的元数据打进 jar，需去重
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // 云同步：OkHttp（WebDAV / GitHub / Gist / Drive / Graph REST）
    // 注：OneDrive 的 OAuth 令牌由外部注入（MSAL 8.5.0 依赖的 display-mask:0.3.0 已从 Maven Central 下架，
    // 故不在此处硬引入；接入方式见 OneDriveProvider 的 KDoc）
    implementation(libs.okhttp)
    // OneDrive/OneDrive 类 OIDC 登录：授权码 + PKCE（Android 公共客户端不能用 client secret）
    implementation(libs.appauth)
    implementation(libs.play.services.auth)
    // Google Drive：与 2FAS 同款 google-api-services-drive + GoogleAccountCredential(DRIVE_APPDATA)
    implementation(libs.google.api.services.drive)
    implementation(libs.google.api.client.android)
    implementation(libs.google.http.client.gson)

    // 图标加载（assets 内的 PNG 也能通过 file:///android_asset 走 Coil 缓存）
    implementation(libs.coil.compose)

    // 加密数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

    // 设置与后台同步
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // 扫码导入：CameraX 实时预览 + 离线捆绑版 ML Kit 二维码识别（静态图与相机帧共用同一解码器）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // 生物识别锁
    implementation(libs.androidx.biometric)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
