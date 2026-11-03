package com.rabi.launcher

/**
 * ENVOY MCP 用の小さな代わり。描画 (ui/orb/OrbRenderer) が、デバッグ版だけの計測に BuildConfig.DEBUG を見るため。
 * このアプリでは計測しない。
 */
object BuildConfig {
    const val DEBUG = false
}
