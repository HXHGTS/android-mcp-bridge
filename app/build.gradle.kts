plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePath = System.getenv("ANDROID_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS") ?: "android-mcp-release"
val releaseKeyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD") ?: releaseStorePassword
val releaseSigningConfigured = !releaseStorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.ikun.androidmcp"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    defaultConfig {
        applicationId = "com.ikun.androidmcp"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("appVersionName").getOrElse("0.1.0")
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
