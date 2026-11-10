plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Подпись релиза берётся из переменных окружения или ~/.gradle/gradle.properties.
// Сами ключи и пароли НЕ хранятся в репозитории:
//   FORGE_KEYSTORE_FILE   — путь к keystore
//   FORGE_STORE_PASSWORD  — пароль keystore
//   FORGE_KEY_ALIAS       — алиас ключа
//   FORGE_KEY_PASSWORD    — пароль ключа
// Если параметры не заданы, release-APK подписывается debug-ключом,
// чтобы сборка всегда оставалась устанавливаемой.
fun signingValue(name: String): String? =
    (System.getenv(name) ?: (project.findProperty(name) as String?))
        ?.takeIf { it.isNotBlank() }

android {
    namespace = "com.bluequark.mtzforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bluequark.mtzforge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        val keystorePath = signingValue("FORGE_KEYSTORE_FILE")
        if (keystorePath != null) {
            create("forge") {
                storeFile = file(keystorePath)
                storePassword = signingValue("FORGE_STORE_PASSWORD")
                keyAlias = signingValue("FORGE_KEY_ALIAS")
                keyPassword = signingValue("FORGE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("forge")
                ?: signingConfigs.getByName("debug")
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
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
}
