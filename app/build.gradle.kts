plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
