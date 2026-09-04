plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.biu.nsz"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.biu.nsz"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // zstd-jni Android AAR: bundles libzstd.so for armeabi-v7a/arm64-v8a/x86/x86_64
    implementation("com.github.luben:zstd-jni:1.5.7-3@aar")
}
