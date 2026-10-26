import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.gradle)
}

android {
    namespace = "com.swagdroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.swagdroid"
        minSdk = 28
        targetSdk = 37
        versionCode = 100
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val keystorePath = System.getenv("SWAG_KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank() && File(keystorePath).exists()) {
            create("releaseCustom") {
                storeFile = File(keystorePath)
                storePassword = System.getenv("SWAG_KEYSTORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("SWAG_KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("SWAG_KEY_PASSWORD").orEmpty()
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use custom release keystore in CI/Release if configured, otherwise fallback to debug signing
            // so `./gradlew assembleRelease` always outputs an immediately installable signed APK!
            signingConfig = signingConfigs.findByName("releaseCustom")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:privilege"))
    implementation(project(":core:vault"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:fixer"))
    implementation(project(":feature:optimizer"))
    implementation(project(":feature:cleaner"))
    implementation(project(":feature:adblock"))
    implementation(project(":feature:security"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.libsu.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
