plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.paddisplay.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paddisplay.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.3.3"

        // Shizuku UserService 需要一个稳定的 AIDL 接口名
        buildConfigField("String", "AIDL_INTERFACE", "\"com.paddisplay.app.IPadDisplayService\"")
    }

    signingConfigs {
        create("release") {
            // 密钥库随仓库一起提供，便于直接构建可安装的 release APK。
            // 正式对外分发前请替换为你自己的密钥库（见 README）。
            val ksPath = (rootProject.findProperty("paddisplay.keystore") as String?)
                ?: "${rootProject.projectDir}/keystore/paddisplay-release.jks"
            storeFile = file(ksPath)
            storePassword = (rootProject.findProperty("paddisplay.keystore.password") as String?)
                ?: "paddisplay2024"
            keyAlias = (rootProject.findProperty("paddisplay.key.alias") as String?)
                ?: "paddisplay"
            keyPassword = (rootProject.findProperty("paddisplay.key.password") as String?)
                ?: "paddisplay2024"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku：无 Root 的系统服务调用
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
