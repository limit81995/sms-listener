plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.smssentinel"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.smssentinel"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    lint {
        abortOnError = true
        // First version is intentionally Chinese-only; native views keep runtime dependencies small.
        disable += setOf("HardcodedText", "SetTextI18n")
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("org.robolectric:robolectric:4.16")
}
