plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.simely.adskip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simely.adskip"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // 加密存储 Token / 密码哈希（Android Keystore 支撑）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // 协程：网络请求放到 IO 线程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
