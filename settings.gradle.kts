pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android と spake2-android は JitPack のみ
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") } }
    }
}
rootProject.name = "ENVOY"
include(":envoymcp")
// 撮影・試験用の架空のアプリ (広告・通販・音楽。どこにも通信しない)
include(":demoapps:puzzle", ":demoapps:mart", ":demoapps:music")
