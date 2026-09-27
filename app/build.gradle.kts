plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ikun.androidmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ikun.androidmcp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
