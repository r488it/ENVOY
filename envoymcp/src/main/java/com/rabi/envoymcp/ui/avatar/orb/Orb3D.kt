package com.rabi.envoymcp.ui.avatar.orb

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

object Orb3D {
    /** OpenGL ES 3.0 が使えるか (Android 12 以降のほぼすべての端末で使える) */
    fun supported(ctx: Context): Boolean =
        (ctx.getSystemService(ActivityManager::class.java)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30000
}

/**
 * 3D の球を描く面 (TextureView を使うので、ふつうの部品と同じように重ねられる。背景は透明)。
 * 画面が見えていない間は描かない。準備に失敗したら onFail (呼び出し側で 2D に戻す)。
 */
@Composable
fun Orb3DSurface(ctl: OrbControls, modifier: Modifier, onFail: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, _ ->
            val v = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ctl.visible = v
            if (v) ctl.wake?.invoke()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val fail by rememberUpdatedState(onFail)
    val main = remember { Handler(Looper.getMainLooper()) }
    AndroidEmbeddedExternalSurface(modifier, isOpaque = false) {
        onSurface { surface, width, height ->
            val gl = OrbGlThread(surface, ctl) { main.post { fail() } }
            ctl.wake = { gl.wake() }
            gl.start(width, height)
            surface.onChanged { w, h -> gl.resize(w, h) }
            surface.onDestroyed { ctl.wake = null; gl.stop() }
        }
    }
}

/**
 * 球をつまんで伸ばす操作。球の上 (少し外側まで) で指を動かすと、その所をつかんで引く。離すと揺れてから戻る。
 * 動かさずに離したとき (タップ) は軽く押した手ごたえだけ返し、聞き取りの開始は呼び出し側のタップの処理に任せる。
 * 座標は描画面 (上端 top) からのピクセルで渡す。
 */
suspend fun PointerInputScope.orbTouch(ctl: OrbControls, center: Offset, radius: Float, top: Float) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if ((down.position - center).getDistance() > radius * 1.1f) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var dragging = false
        try {
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    if (!dragging) ctl.touch(OrbControls.TouchKind.TAP, down.position.x, down.position.y - top)
                    break
                }
                if (!dragging && (ch.position - down.position).getDistance() > slop) {
                    dragging = true
                    ctl.touch(OrbControls.TouchKind.GRAB, down.position.x, down.position.y - top)
                }
                if (dragging) {
                    ctl.touch(OrbControls.TouchKind.MOVE, ch.position.x, ch.position.y - top)
                    ch.consume()
                }
            }
        } finally {
            if (dragging) ctl.touch(OrbControls.TouchKind.RELEASE)
        }
    }
}
