// ENVOY MCP。主に次のことをする:
// - ランチャー (ホーム画面) としてリゲルを出す。タップすると ChatGPT のアプリの音声モードを開く
// - スマホの中 (127.0.0.1) だけで受け付ける MCP サーバーとして、画面の読み取り・操作・部品を道具として出す
// - 聞き取り・読み上げ・端末内の LLM は持たない (考えるのは ChatGPT のアプリ側)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rabi.envoymcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rabi.envoymcp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    testOptions { unitTests.isReturnDefaultValues = true }

    packaging {
        resources { excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
        // スマホの中の tunnel-client (libtunnelclient.so) を nativeLibraryDir に展開する (アプリが実行できるのはそこだけ)
        jniLibs {
            useLegacyPackaging = true
            // 写真を言葉で探す (MediaPipe tasks-retrieval と LiteRT-LM が同じ JNI を持つ。Edge Gallery と同じ)
            pickFirsts.add("**/liblitertlm_jni.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")   // 設定の画面のアイコン
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // 自分の端末の adbd への接続
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    // AgentARM の顔の追跡: 前面カメラ (CameraX) と顔の検出 (ML Kit。モデルを中に持つ版なので、通信なしで動く)
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")   // 撮りながら顔を追う (同じ前面カメラで録画と顔の検出)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // 写真を言葉で探す: EmbeddingGemma 2 (TPU 版) を MediaPipe の UniversalEmbedder で動かす。
    // Google AI Edge Gallery と同じ版 (ModelInfo は LiteRT-LM)
    implementation("com.google.mediapipe:tasks-retrieval:1.2.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // Pixel の TPU (Google Tensor) で動かす仲立ち (Play ストアでは追加の部品で配るもの。このアプリは直接入れるので、ふつうの依存にする)
    implementation("com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
