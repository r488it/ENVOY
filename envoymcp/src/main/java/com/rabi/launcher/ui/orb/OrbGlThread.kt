package com.rabi.launcher.ui.orb

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import com.rabi.launcher.core.RabiLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 3D 描画の専用スレッド (EGL / OpenGL ES 3)。画面の更新の合図 (Choreographer) に合わせて描く。
 * - 動いているときは毎秒 60 回、待機中で体が止まっていれば毎秒 30 回 (120Hz の画面でも 60 回まで)
 * - 画面が見えていない間 (ctl.visible = false) は描かない
 * - 準備に失敗したら onFail を呼ぶ (2D の描画に戻す)
 */
internal class OrbGlThread(private val surface: Surface, private val ctl: OrbControls, private val onFail: () -> Unit) {
    private val thread = HandlerThread("rabi-orb", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var renderer: OrbRenderer? = null
    @Volatile private var alive = true
    private var posted = false
    private var lastDraw = 0L

    private val callback = Choreographer.FrameCallback { t ->
        posted = false
        if (!alive || !ctl.visible) return@FrameCallback
        frame(t)
        schedule()
    }

    fun start(width: Int, height: Int) {
        handler.post {
            if (!initEgl()) { releaseEgl(); onFail(); return@post }
            val r = OrbRenderer(ctl)
            if (!r.init()) { releaseEgl(); onFail(); return@post }
            r.resize(width, height)
            renderer = r
            schedule()
        }
    }

    fun resize(width: Int, height: Int) { handler.post { renderer?.resize(width, height) } }

    /** 止まっていた描画を再開する (見えるようになった・触られた) */
    fun wake() { handler.post { schedule() } }

    /** 描画面が消える前に呼ぶ。後片付けが終わるまで少しだけ待つ */
    fun stop() {
        alive = false
        val done = CountDownLatch(1)
        handler.post {
            renderer?.release(); renderer = null
            releaseEgl()
            done.countDown()
        }
        done.await(500, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    private fun schedule() {
        if (posted || !alive || renderer == null) return
        posted = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun frame(t: Long) {
        val r = renderer ?: return
        val interval = if (r.wantsFast(t)) 15_500_000L else 31_000_000L
        if (t - lastDraw < interval) return
        lastDraw = t
        try {
            r.draw(t)
        } catch (e: RuntimeException) {
            RabiLog.w("orb3d", "draw failed: ${e.message?.take(200)}")
            alive = false; onFail(); return
        }
        if (!EGL14.eglSwapBuffers(dpy, surf)) {
            val err = EGL14.eglGetError()
            RabiLog.w("orb3d", "swap failed 0x${Integer.toHexString(err)}")
            if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) alive = false
        }
    }

    private fun initEgl(): Boolean {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { RabiLog.w("orb3d", "egl init failed"); return false }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, num, 0) || num[0] == 0) { RabiLog.w("orb3d", "no ES3 config"); return false }
        val cfg = configs[0]
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) { RabiLog.w("orb3d", "no ES3 context"); return false }
        surf = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (surf == EGL14.EGL_NO_SURFACE) { RabiLog.w("orb3d", "no window surface 0x${Integer.toHexString(EGL14.eglGetError())}"); return false }
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { RabiLog.w("orb3d", "makeCurrent failed"); return false }
        return true
    }

    /** 後片付け。既定の EGL ディスプレイはアプリの他の描画 (HWUI) と共有なので eglTerminate はしない */
    private fun releaseEgl() {
        if (dpy == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
        EGL14.eglReleaseThread()
        surf = EGL14.EGL_NO_SURFACE; ctx = EGL14.EGL_NO_CONTEXT; dpy = EGL14.EGL_NO_DISPLAY
    }
}
