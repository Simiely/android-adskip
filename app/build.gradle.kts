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

    // 签名：仅当 CI 传入 KEYSTORE_FILE 属性时启用
    val ks = project.findProperty("KEYSTORE_FILE") as? String
    if (!ks.isNullOrEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(ks)
                storePassword = project.findProperty("KEYSTORE_PASSWORD") as? String
                keyAlias = project.findProperty("KEY_ALIAS") as? String
                keyPassword = project.findProperty("KEYSTORE_PASSWORD") as? String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!ks.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
