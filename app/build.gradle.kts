plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val abis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

// подпись релиза: ключ передаётся автосборке через секреты GitHub (ZAPRET_KEYSTORE и пароль);
// без них собирается APK с отладочной подписью
val ksFile = System.getenv("ZAPRET_KEYSTORE_FILE")

android {
    namespace = "com.coffiok.zapret"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coffiok.zapret"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters.addAll(abis) }
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("ZAPRET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZAPRET_KEY_ALIAS") ?: "zapret"
                keyPassword = System.getenv("ZAPRET_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
}

// hev-socks5-tunnel собирается ndk-build'ом в src/main/jniLibs (как в ByeByeDPI)
tasks.register<Exec>("runNdkBuild") {
    group = "build"
    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "$ndkDir\\ndk-build.cmd" else "$ndkDir/ndk-build"
    setArgs(listOf(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        "NDK_LIBS_OUT=src/main/jniLibs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk",
        "-j8"
    ))
}
tasks.named("preBuild") { dependsOn("runNdkBuild") }
