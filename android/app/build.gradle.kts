import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名配置从 android/keystore.properties 读取（该文件被 .gitignore 排除）。
// 本地由 tools/make-keystore.sh 生成；CI 由 workflow 从 Secrets 还原。
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasKeystore) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.readtool.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.readtool.app"
        minSdk = 26
        targetSdk = 35
        // CI 传 -PversionName=1.2.0 -PversionCode=42；本地缺省时用下面的默认值
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // 开发期要跑模拟器（x86_64），真机发布只打 arm64 —— 可省掉 43MB 的 x86_64 推理库
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // 有 keystore 才签名；没有时产出未签名包（CI 缺 Secrets 会失败，属预期）
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    androidResources {
        // 音频包按需读取，不压缩，保证 SoundPool / MediaPlayer 能拿文件描述符
        noCompress += listOf("m4a", "onnx")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 端侧 OCR 推理：ONNX Runtime（Apache-2.0），加载 PP-OCRv4 ONNX 模型
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
}
