// 撮影用の架空のゲーム「ぷちぷちパズル」(全画面の広告が出る。どこにもつながらない)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rabi.demo.puzzle"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.rabi.demo.puzzle"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
