plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartquiz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartquiz"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.6.4"
    }

    signingConfigs {
        create("release") {
            // 使用debug keystore作为默认签名（便于快速安装测试）
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // NanoHTTPD - 轻量级HTTP服务器
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // OkHttp - 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson - JSON解析
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Google ML Kit - 本地文字识别（OCR）
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ZXing core - QR码生成（无UI依赖，仅核心算法）
    implementation("com.google.zxing:core:3.5.3")

    // Glide - 图片加载（启屏页网络广告图）
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // ViewPager2
    implementation("androidx.viewpager2:viewpager2:1.0.0")
}
