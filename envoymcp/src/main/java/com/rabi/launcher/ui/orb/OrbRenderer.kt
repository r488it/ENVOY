package com.rabi.launcher.ui.orb

import android.opengl.GLES30 as GL
import android.opengl.Matrix
import com.rabi.launcher.core.RabiLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 光る球の 3D 描画 (描画スレッドの中だけで使う)。
 * - 体は SoftOrb (四面体の柔らかい体) を固定の刻み (1/60 秒を 8 分割) で進める
 * - 見た目の球は細かい球 (5120 枚の三角形) を体の表面に埋め込み、体と一緒に変形させる。目も同じく体に埋め込む
 * - 描く順: 中 (奥の面と目) → 手前のガラス (中を屈折させて透かす) → 明るい所のにじみ → 仕上げ
 */
internal class OrbRenderer(private val ctl: OrbControls) {
    private val body = SoftOrb()
    private val mesh = IcoSphere.create(4)
    private val emb = body.embed(mesh.v)
    private val deformed = FloatArray(mesh.v.size)
    private val normals = FloatArray(mesh.v.size)
    private val inter = FloatArray(mesh.count * 6)
    private val vbuf: FloatBuffer = floatBuffer(inter.size)

    private val eyeRings = 14
    private val eyeSegs = 22
    private var eyeIndexCount = 0

    private val programs = HashMap<Int, HashMap<String, Int>>()
    private var pInterior = 0; private var pEye = 0; private var pGlass = 0
    private var pHalo = 0; private var pBright = 0; private var pBlur = 0; private var pComposite = 0
    private var vaoOrb = 0; private var vboOrb = 0; private var vboRest = 0; private var iboOrb = 0
    private var vaoEye = 0; private var vboEye = 0; private var iboEye = 0
    private var vaoEmpty = 0

    private var w = 0; private var h = 0
    private var halfFloat = false
    private var fboInterior = 0; private var texInterior = 0; private var rbInteriorDepth = 0
    private var fboScene = 0; private var texScene = 0; private var rbSceneDepth = 0
    private val fboB = IntArray(4); private val texB = IntArray(4)
    private var bw1 = 0; private var bh1 = 0; private var bw2 = 0; private var bh2 = 0

    private val proj = FloatArray(16); private val view = FloatArray(16); private val model = FloatArray(16)
    private val vm = FloatArray(16); private val mvp = FloatArray(16)
    private val squashM = FloatArray(16); private val tmpM = FloatArray(16)
    private val eyeM = Array(2) { FloatArray(16) }
    private var focal = 1f

    private var accum = 0f
    private var lastNanos = 0L
    private var lastTouchNanos = 0L
    private val cen = FloatArray(3)

    // つかんでいる間の値 (体の座標)
    private val grabHit = FloatArray(3)
    private var dragYaw = 0f; private var dragPitch = 0f
    // 引いている間、体全体も少し指の方へ寄せる (目標と、なめらかにした今の値)
    private val grabN = FloatArray(3)

    // 球の向き (度)。目標 (状態・視線・ゆっくりした揺らぎ・指) へ、少しだけ行き過ぎるばねで向かう
    private var yaw = 0f; private var yawV = 0f; private var pitch = 0f; private var pitchV = 0f
    private val bodyC = FloatArray(3)

    /** GL の準備。失敗したら false (呼び出し側は 2D の描画に戻す) */
    fun init(): Boolean = runCatching {
        val ext = GL.glGetString(GL.GL_EXTENSIONS) ?: ""
        halfFloat = "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext
        pInterior = program(OrbShaders.ORB_VS, OrbShaders.INTERIOR_FS)
        pEye = program(OrbShaders.EYE_VS, OrbShaders.EYE_FS)
        pGlass = program(OrbShaders.ORB_VS, OrbShaders.GLASS_FS)
        pHalo = program(OrbShaders.QUAD_VS, OrbShaders.HALO_FS)
        pBright = program(OrbShaders.QUAD_VS, OrbShaders.BRIGHT_FS)
        pBlur = program(OrbShaders.QUAD_VS, OrbShaders.BLUR_FS)
        pComposite = program(OrbShaders.QUAD_VS, OrbShaders.COMPOSITE_FS)
        buildOrbBuffers()
        buildEyeBuffers()
        vaoEmpty = gen { GL.glGenVertexArrays(1, it, 0) }
        RabiLog.i("orb3d", "gl ready halfFloat=$halfFloat renderer=${GL.glGetString(GL.GL_RENDERER)}")
        true
    }.getOrElse { RabiLog.w("orb3d", "gl init failed: ${it.message?.take(300)}"); false }

    fun resize(width: Int, height: Int) {
        if (width == w && height == h) return
        w = width; h = height
        deleteTargets()
        createTargets()
    }

    /** 速く描く必要があるか (状態が動いている・触っている・体が揺れている) */
    fun wantsFast(now: Long) = ctl.active || body.grabbing || now - lastTouchNanos < 1_500_000_000L || !body.asleep

    private var statFrames = 0; private var statNanos = 0L; private var statSince = 0L; private var statPhys = 0L; private var statMesh = 0L

    fun draw(now: Long) {
        if (w == 0 || h == 0) return
        val t0 = System.nanoTime()
        stepBody(now)
        val t1 = System.nanoTime()
        if (body.asleep) {
            // 休んでいる間は元の球のまま (置き場所と大きさだけ)。法線も球のまま
            for (i in 0 until mesh.count) {
                val o = i * 3
                deformed[o] = body.anchorX + mesh.v[o] * body.scale; deformed[o + 1] = body.anchorY + mesh.v[o + 1] * body.scale
                deformed[o + 2] = body.anchorZ + mesh.v[o + 2] * body.scale
            }
            System.arraycopy(mesh.v, 0, normals, 0, mesh.v.size)
        } else {
            body.deform(emb, mesh.v, deformed)
            computeNormals()
        }
        for (i in 0 until mesh.count) {
            for (k in 0..2) { inter[i * 6 + k] = deformed[i * 3 + k]; inter[i * 6 + 3 + k] = normals[i * 3 + k] }
        }
        vbuf.position(0); vbuf.put(inter).position(0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vboOrb)
        GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, inter.size * 4, vbuf)
        computeEyes(now)
        val t2 = System.nanoTime()
        setupMatrices()
        passInterior()
        passScene()
        passBloom()
        passComposite()
        if (com.rabi.launcher.BuildConfig.DEBUG) {
            // 計測 (デバッグ版だけ): 10 秒ごとに、1 回の描画にかかった CPU 時間 (平均) と毎秒の回数
            statFrames++; statNanos += System.nanoTime() - t0; statPhys += t1 - t0; statMesh += t2 - t1
            if (statSince == 0L) statSince = now
            if (now - statSince > 10_000_000_000L) {
                val ms = statNanos / 1e6 / statFrames
                val ph = statPhys / 1e6 / statFrames; val me = statMesh / 1e6 / statFrames
                RabiLog.i("orb3d", "frames=${statFrames / 10}/s cpu=${(ms * 100).toInt() / 100f}ms (body ${(ph * 100).toInt() / 100f} mesh ${(me * 100).toInt() / 100f}) active=${ctl.active}")
                statFrames = 0; statNanos = 0L; statSince = now; statPhys = 0L; statMesh = 0L
            }
        }
    }

    // ==================================================================== 体

    private fun stepBody(now: Long) {
        val dt = if (lastNanos == 0L) STEP else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.25f)
        lastNanos = now
        body.scale = ctl.scale
        body.damping = if (ctl.reduceMotion) 6f else 2.4f
        body.maxPull = if (ctl.reduceMotion) 0.25f else STRETCH_OUT
        handleTouches(now)
        // 引っ張っても体の置き場所は動かさない (伸びるだけ)
        body.anchorX = 0f; body.anchorY = ctl.offsetY; body.anchorZ = 0f
        accum += dt
        var steps = 0
        while (accum >= STEP && steps < 4) { body.step(STEP, 8); turn(STEP); accum -= STEP; steps++ }
        if (steps == 4) accum = 0f
        // 触られていなければ、揺れが収まったところで休ませる (計算を止めて電池を節約)
        if (!body.grabbing && now - lastTouchNanos > 500_000_000L) body.trySleep()
    }

    /** 向きのばね (約 0.9 Hz。動きを少なくする設定では行き過ぎない) */
    private fun turn(dt: Float) {
        val wn = 2f * PI.toFloat() * 0.9f
        val zeta = if (ctl.reduceMotion) 1f else 0.6f
        val ty = ctl.yawDeg + dragYaw; val tp = ctl.pitchDeg + dragPitch
        yawV += (wn * wn * (ty - yaw) - 2f * zeta * wn * yawV) * dt; yaw += yawV * dt
        pitchV += (wn * wn * (tp - pitch) - 2f * zeta * wn * pitchV) * dt; pitch += pitchV * dt
    }

    private val ro = FloatArray(3); private val rd = FloatArray(3)

    /** 描画面のピクセル (px, py) から、体の座標での視線 (ro + t rd) を作る */
    private fun ray(px: Float, py: Float) {
        var x = (px - ctl.cx) / focal; var y = -(py - ctl.cy) / focal; var z = -1f
        val l = sqrt(x * x + y * y + z * z); x /= l; y /= l; z /= l
        // 体の向き (回転) の分を戻す: 回転の逆 = 転置
        toBody(0f, 0f, CAM_D, ro)
        toBody(x, y, z, rd)
    }

    /** 画面の座標 (回転後) → 体の座標 (回転前) */
    private fun toBody(x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = model[0] * x + model[1] * y + model[2] * z
        out[1] = model[4] * x + model[5] * y + model[6] * z
        out[2] = model[8] * x + model[9] * y + model[10] * z
    }

    /** 視線と今の体 (中心・半径の球とみなす) の手前の交点。外れたときは一番近い所を球の表面に寄せる */
    private fun hit(out: FloatArray) {
        body.centroid(cen)
        val ox = ro[0] - cen[0]; val oy = ro[1] - cen[1]; val oz = ro[2] - cen[2]
        val b = ox * rd[0] + oy * rd[1] + oz * rd[2]
        val c = ox * ox + oy * oy + oz * oz - body.scale * body.scale
        val disc = b * b - c
        val t = if (disc >= 0) -b - sqrt(disc) else -b
        var hx = ro[0] + rd[0] * t - cen[0]; var hy = ro[1] + rd[1] * t - cen[1]; var hz = ro[2] + rd[2] * t - cen[2]
        if (disc < 0) { val l = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-4f); hx *= body.scale / l; hy *= body.scale / l; hz *= body.scale / l }
        out[0] = cen[0] + hx; out[1] = cen[1] + hy; out[2] = cen[2] + hz
    }

    private val vd = FloatArray(3); private var vdx = 0f; private var vdy = 0f; private var vdz = 1f

    private fun handleTouches(now: Long) {
        while (true) {
            val e = ctl.touches.poll() ?: break
            lastTouchNanos = now
            when (e.kind) {
                OrbControls.TouchKind.GRAB -> {
                    ray(e.x, e.y); hit(grabHit); body.grab(grabHit[0], grabHit[1], grabHit[2], radius = 0.8f)
                    // つかんだ所の外向き (体の中へ押し込む向きに引いたかを見分ける)
                    val gl = sqrt((grabHit[0] - cen[0]).let { it * it } + (grabHit[1] - cen[1]).let { it * it } + (grabHit[2] - cen[2]).let { it * it }).coerceAtLeast(1e-4f)
                    for (i in 0..2) grabN[i] = (grabHit[i] - cen[i]) / gl
                    toBody(0f, 0f, 1f, vd); vdx = vd[0]; vdy = vd[1]; vdz = vd[2]
                }
                OrbControls.TouchKind.MOVE -> if (body.grabbing) {
                    ray(e.x, e.y)
                    // つかんだ所を通り、視線に垂直な面の上で指を追う
                    val t = ((grabHit[0] - ro[0]) * vdx + (grabHit[1] - ro[1]) * vdy + (grabHit[2] - ro[2]) * vdz) /
                        (rd[0] * vdx + rd[1] * vdy + rd[2] * vdz)
                    val px = ro[0] + rd[0] * t - grabHit[0]; val py = ro[1] + rd[1] * t - grabHit[1]; val pz = ro[2] + rd[2] * t - grabHit[2]
                    // 引っ張れる長さ。表面を伸ばせるのは STRETCH_OUT まで
                    // (それより伸ばすと中の四面体が裏返る。体の中へ押し込む向きは STRETCH_IN まで)。体ごとは寄せない
                    val pl = sqrt(px * px + py * py + pz * pz)
                    val inward = px * grabN[0] + py * grabN[1] + pz * grabN[2] < -0.3f * pl
                    body.maxPull = if (ctl.reduceMotion) 0.25f else if (inward) STRETCH_IN else STRETCH_OUT
                    body.pull(px, py, pz)
                    // 引いた方へ少し転がるように向きを変える (画面の向きで)
                    val m = if (ctl.reduceMotion) 8f else 22f
                    val sx = model[0] * px + model[4] * py + model[8] * pz; val sy = model[1] * px + model[5] * py + model[9] * pz
                    dragYaw = (sx * m).coerceIn(-14f, 14f); dragPitch = (sy * m).coerceIn(-14f, 14f)
                }
                OrbControls.TouchKind.RELEASE -> { body.release(); dragYaw = 0f; dragPitch = 0f }
                OrbControls.TouchKind.TAP -> {
                    ray(e.x, e.y); val p = FloatArray(3); hit(p)
                    val nx = p[0] - cen[0]; val ny = p[1] - cen[1]; val nz = p[2] - cen[2]
                    val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
                    val sp = if (ctl.reduceMotion) 0.4f else 0.9f
                    body.poke(p[0], p[1], p[2], -nx / l, -ny / l, -nz / l, sp)
                }
            }
        }
    }

    private fun computeNormals() {
        java.util.Arrays.fill(normals, 0f)
        val t = mesh.tris; val p = deformed
        for (f in 0 until t.size / 3) {
            val a = t[f * 3] * 3; val b = t[f * 3 + 1] * 3; val c = t[f * 3 + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) { normals[i] += nx; normals[i + 1] += ny; normals[i + 2] += nz }
        }
        for (i in 0 until mesh.count) {
            val o = i * 3
            val l = sqrt(normals[o] * normals[o] + normals[o + 1] * normals[o + 1] + normals[o + 2] * normals[o + 2]).coerceAtLeast(1e-8f)
            normals[o] /= l; normals[o + 1] /= l; normals[o + 2] /= l
        }
    }

    // ==================================================================== 目

    private val ep = FloatArray(9)
    /** 目ごとのつやの強さと、うれしい目 (∩) の度合い */
    private val eyeHl = FloatArray(2)
    /** 目ごとのまぶた: 閉じる度合い・閉じた線の反り・にっこり */
    private val eyeClose = FloatArray(2)
    private val eyeCurve = FloatArray(2)
    private val eyeSmile = FloatArray(2)
    /** ほほの光の置き場所 (目と同じ玉の形を、目の下の外側に平たく置く) */
    private val cheekM = Array(2) { FloatArray(16) }

    /**
     * 目の位置と形。目は球の内側 (半径 EYE_DEPTH) の層にあり、体の変形に乗って動く・傾く・伸びる。
     * 中心は画面で見て球の横 ±0.42・少し上、大きさは幅 0.14・高さ 0.20 (半径に対する割合)
     */
    private fun computeEyes(now: Long) {
        val since = (now - ctl.blinkAt) / 1e9f
        val blink = if (since in 0f..BLINK) 1f - 0.92f * sin(since / BLINK * PI.toFloat()) else 1f
        val open = ctl.eyeOpen.coerceIn(0.85f, 1.35f)
        val hl = (blink * (1f - ctl.squint)).let { ((it - 0.4f) / 0.4f).coerceIn(0f, 1f) }
        for (e in 0..1) {
            val side = if (e == 0) -1f else 1f
            val right = e == 1
            val asymS = if (right) 1f - 0.12f * ctl.asym else 1f
            val close = (if (right) ctl.eyeCloseR else ctl.eyeCloseL).coerceIn(0f, 1f)
            val smile = (if (right) ctl.eyeSmileR else ctl.eyeSmileL).coerceIn(0f, 1f)
            eyeClose[e] = close; eyeSmile[e] = smile
            eyeCurve[e] = (if (right) ctl.eyeCurveR else ctl.eyeCurveL).coerceIn(-1f, 1f)
            val sq = ctl.eyeSqueeze.coerceIn(0f, 1f)
            // つや: 閉じるほど消す (にっこりは上の丸みが残るので、少し残す)
            eyeHl[e] = hl * (1f - close * (1f - 0.4f * smile)) * (1f - sq)
            // ガラスのレンズの効果で約 1.2 倍に見えるので、その分だけ内側・小さめに置く (見た目で横 ±0.42・少し上・幅 0.14・高さ 0.20)
            val ex = side * 0.35f + ctl.gazeX
            // にっこりの目は、ほほに押されて少し上がる
            val ey = 0.025f - ctl.gazeY + (if (right) 0.02f * ctl.asym else 0f) + 0.025f * close * smile
            // うれしい目は少し横長に、驚きは大きく
            val sc = ctl.eyeScale.coerceIn(0.8f, 1.4f)
            // 笑う目 (＞＜) は縦横が同じ玉にして、線の太さをそろえる。笑う目・まぶたを閉じた目は、まばたき・細めをしない。
            // まぶたを閉じた目は少し横に長く (閉じた線が目の幅いっぱいに見えるように)
            val bx = 0.118f + (0.15f - 0.118f) * sq; val by = 0.168f + (0.15f - 0.168f) * sq
            val keep = maxOf(sq, close)
            val shut = blink * (1f - 0.45f * ctl.squint)
            place(ex, ey, bx * asymS * sc * (1f + 0.15f * close),
                by * asymS * sc * (open + (1f - open) * keep) * (shut + (1f - shut) * keep), 0.095f, eyeM[e])   // 少し丸みのある玉 (つやが内側に乗る)
        }
        // ほほ: 目の下の外側に、平たく置く (視線の向きに少しついていく)
        if (ctl.blush > 0.01f) for (e in 0..1) {
            val side = if (e == 0) -1f else 1f
            place(side * 0.42f + ctl.gazeX * 0.6f, -0.21f - ctl.gazeY * 0.6f, 0.15f, 0.085f, 0.04f, cheekM[e])
        }
    }

    /** 球の内側の層の (ex, ey) に、横 sx・縦 sy・厚み sz の玉を、体の変形に乗せて置く行列を m に書く */
    private fun place(ex: Float, ey: Float, sx: Float, sy: Float, sz: Float, m: FloatArray) {
        var dx = ex / EYE_DEPTH; var dy = ey / EYE_DEPTH
        val r2 = dx * dx + dy * dy
        if (r2 > 0.8f) { val f = sqrt(0.8f / r2); dx *= f; dy *= f }
        val dz = sqrt(1f - dx * dx - dy * dy)
        // 面に沿った向き: 横 u = 上 × n (水平)、縦 v = n × u。n = (dx, dy, dz) は球の外向き
        var ux = dz; var uz = -dx; val ul = sqrt(ux * ux + uz * uz); ux /= ul; uz /= ul
        val vx = dy * uz; val vy = dz * ux - dx * uz; val vz = -dy * ux
        val px = dx * EYE_DEPTH; val py = dy * EYE_DEPTH; val pz = dz * EYE_DEPTH
        // 中心と、横・縦に少し離れた 2 点を体の変形に乗せ、そこから目の向きと伸びを出す
        val s = 0.1f
        body.deformPoint(px, py, pz, ep, 0)
        body.deformPoint(px + ux * s, py, pz + uz * s, ep, 3)
        body.deformPoint(px + vx * s, py + vy * s, pz + vz * s, ep, 6)
        val aux = (ep[3] - ep[0]) / s; val auy = (ep[4] - ep[1]) / s; val auz = (ep[5] - ep[2]) / s
        val avx = (ep[6] - ep[0]) / s; val avy = (ep[7] - ep[1]) / s; val avz = (ep[8] - ep[2]) / s
        var anx = auy * avz - auz * avy; var any = auz * avx - aux * avz; var anz = aux * avy - auy * avx
        val nl = sqrt(anx * anx + any * any + anz * anz).coerceAtLeast(1e-6f); anx /= nl; any /= nl; anz /= nl
        m[0] = aux * sx; m[1] = auy * sx; m[2] = auz * sx; m[3] = 0f
        m[4] = avx * sy; m[5] = avy * sy; m[6] = avz * sy; m[7] = 0f
        m[8] = anx * sz; m[9] = any * sz; m[10] = anz * sz; m[11] = 0f
        m[12] = ep[0]; m[13] = ep[1]; m[14] = ep[2]; m[15] = 1f
    }

    // ==================================================================== 描画

    private fun setupMatrices() {
        // 球の半径が radiusPx に、中心が (cx, cy) に映るように (長めのレンズ: 物撮りのように歪みが少ない)
        focal = ctl.radiusPx * sqrt(CAM_D * CAM_D - 1f)
        val n = CAM_D - 2.6f; val f = CAM_D + 2.6f
        java.util.Arrays.fill(proj, 0f)
        proj[0] = 2f * focal / w
        proj[5] = 2f * focal / h
        proj[8] = 1f - 2f * ctl.cx / w
        proj[9] = 2f * ctl.cy / h - 1f
        proj[10] = -(f + n) / (f - n)
        proj[11] = -1f
        proj[14] = -2f * f * n / (f - n)
        Matrix.setIdentityM(view, 0); Matrix.translateM(view, 0, 0f, 0f, -CAM_D)
        Matrix.setRotateM(model, 0, -ctl.tiltDeg, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, -pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        // つぶれ・伸び (画面の縦横で。体積を保つよう、横と奥行きは逆に)
        val sq = ctl.squash.coerceIn(-0.15f, 0.15f)
        if (sq != 0f) {
            Matrix.setIdentityM(squashM, 0); Matrix.scaleM(squashM, 0, 1f + sq * 0.5f, 1f - sq, 1f + sq * 0.5f)
            Matrix.multiplyMM(tmpM, 0, squashM, 0, model, 0); System.arraycopy(tmpM, 0, model, 0, 16)
        }
        Matrix.multiplyMM(vm, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, vm, 0)
    }

    private val k get() = (0.62f + 0.38f * ctl.glow + 0.45f * ctl.flash + 0.30f * ctl.level).coerceIn(0f, 1.5f)

    /** 体の中心 (回転後の座標) */
    private fun bodyCenter() {
        body.centroid(cen)
        bodyC[0] = model[0] * cen[0] + model[4] * cen[1] + model[8] * cen[2]
        bodyC[1] = model[1] * cen[0] + model[5] * cen[1] + model[9] * cen[2]
        bodyC[2] = model[2] * cen[0] + model[6] * cen[1] + model[10] * cen[2]
    }

    private fun passInterior() {
        bodyCenter()
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fboInterior)
        GL.glViewport(0, 0, w, h)
        GL.glClearColor(0f, 0f, 0f, 0f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        GL.glEnable(GL.GL_DEPTH_TEST); GL.glDepthMask(true); GL.glDisable(GL.GL_BLEND)
        GL.glEnable(GL.GL_CULL_FACE)
        // 奥の面 (内側から見る)
        GL.glCullFace(GL.GL_FRONT)
        use(pInterior)
        mat(pInterior, "uMVP", mvp); mat(pInterior, "uModel", model)
        u3(pInterior, "uCam", 0f, 0f, CAM_D); u1(pInterior, "uK", k); u1(pInterior, "uOut", 1f)
        u3(pInterior, "uCenter", bodyC[0], bodyC[1], bodyC[2]); u1(pInterior, "uScale", body.scale)
        GL.glBindVertexArray(vaoOrb)
        GL.glDrawElements(GL.GL_TRIANGLES, mesh.tris.size, GL.GL_UNSIGNED_SHORT, 0)
        // 目 (縁の 1〜2 ピクセルを薄くして重ねる = ギザギザを出さない。乗算済みの透明度で)
        GL.glCullFace(GL.GL_BACK)
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE_MINUS_SRC_ALPHA)
        use(pEye)
        mat(pEye, "uMVP", mvp); mat(pEye, "uModel", model)
        u3(pEye, "uCam", 0f, 0f, CAM_D)
        u1(pEye, "uK", 0.86f + 0.22f * ctl.glow + 0.25f * ctl.flash); u1(pEye, "uOut", 1f)
        GL.glBindVertexArray(vaoEye)
        // ほほの光 (足し算で重ねるだけなので、奥行きは書かない)
        if (ctl.blush > 0.01f) {
            GL.glDepthMask(false)
            u1(pEye, "uMode", 1f); u1(pEye, "uBlush", ctl.blush.coerceIn(0f, 1f))
            for (e in 0..1) {
                mat(pEye, "uEye", cheekM[e])
                GL.glDrawElements(GL.GL_TRIANGLES, eyeIndexCount, GL.GL_UNSIGNED_SHORT, 0)
            }
            GL.glDepthMask(true)
        }
        u1(pEye, "uMode", 0f); u1(pEye, "uDroop", ctl.eyeDroop.coerceIn(0f, 1f)); u1(pEye, "uSqueeze", ctl.eyeSqueeze.coerceIn(0f, 1f))
        for (e in 0..1) {
            mat(pEye, "uEye", eyeM[e])
            u1(pEye, "uHl", eyeHl[e]); u1(pEye, "uSide", if (e == 0) -1f else 1f)
            u1(pEye, "uClose", eyeClose[e]); u1(pEye, "uCurve", eyeCurve[e]); u1(pEye, "uSmile", eyeSmile[e])
            GL.glDrawElements(GL.GL_TRIANGLES, eyeIndexCount, GL.GL_UNSIGNED_SHORT, 0)
        }
        GL.glDisable(GL.GL_BLEND); GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_CULL_FACE)
        GL.glInvalidateFramebuffer(GL.GL_FRAMEBUFFER, 1, intArrayOf(GL.GL_DEPTH_ATTACHMENT), 0)
    }

    private fun passScene() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fboScene)
        GL.glViewport(0, 0, w, h)
        GL.glClearColor(0f, 0f, 0f, 0f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        // まわりの光 (足し算)
        GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_CULL_FACE)
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE)
        use(pHalo)
        u2(pHalo, "uC", ctl.cx + focal * bodyC[0] / CAM_D, h - ctl.cy + focal * bodyC[1] / CAM_D)
        u1(pHalo, "uR", ctl.radiusPx * body.scale); u1(pHalo, "uK", k); u1(pHalo, "uTintAmt", ctl.tint.coerceIn(0f, 1f)); u1(pHalo, "uTintLift", ctl.tintLift)
        u3(pHalo, "uTintHalo", ctl.haloR, ctl.haloG, ctl.haloB)
        GL.glBindVertexArray(vaoEmpty)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        // 手前のガラス (輪郭の 1〜2 ピクセルは薄くして、まわりの光に重ねる)
        GL.glBlendFunc(GL.GL_ONE, GL.GL_ONE_MINUS_SRC_ALPHA)
        GL.glEnable(GL.GL_DEPTH_TEST); GL.glEnable(GL.GL_CULL_FACE); GL.glCullFace(GL.GL_BACK)
        use(pGlass)
        mat(pGlass, "uMVP", mvp); mat(pGlass, "uModel", model)
        u3(pGlass, "uCam", 0f, 0f, CAM_D)
        u2(pGlass, "uViewport", w.toFloat(), h.toFloat())
        u1(pGlass, "uK", k); u1(pGlass, "uWarm", ctl.warm); u1(pGlass, "uPhase", ctl.phase); u1(pGlass, "uTintAmt", ctl.tint.coerceIn(0f, 1f))
        u3(pGlass, "uTint", ctl.tintR, ctl.tintG, ctl.tintB)
        u1(pGlass, "uRefrPx", ctl.radiusPx * 0.25f); u1(pGlass, "uIn", 1f)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, texInterior)
        u1i(pGlass, "uInterior", 0)
        GL.glBindVertexArray(vaoOrb)
        GL.glDrawElements(GL.GL_TRIANGLES, mesh.tris.size, GL.GL_UNSIGNED_SHORT, 0)
        GL.glDisable(GL.GL_BLEND); GL.glDisable(GL.GL_DEPTH_TEST); GL.glDisable(GL.GL_CULL_FACE)
        GL.glInvalidateFramebuffer(GL.GL_FRAMEBUFFER, 1, intArrayOf(GL.GL_DEPTH_ATTACHMENT), 0)
    }

    private fun passBloom() {
        GL.glBindVertexArray(vaoEmpty)
        GL.glDisable(GL.GL_BLEND)
        // 明るい所 → 1/4
        quad(fboB[0], bw1, bh1, pBright, texScene) { u2(pBright, "uTexel", 1f / w, 1f / h) }
        quad(fboB[1], bw1, bh1, pBlur, texB[0]) { u2(pBlur, "uDir", 1f / bw1, 0f) }
        quad(fboB[0], bw1, bh1, pBlur, texB[1]) { u2(pBlur, "uDir", 0f, 1f / bh1) }
        // さらに 1/8 (広いにじみ)
        quad(fboB[2], bw2, bh2, pBlur, texB[0]) { u2(pBlur, "uDir", 0.5f / bw2, 0f) }
        quad(fboB[3], bw2, bh2, pBlur, texB[2]) { u2(pBlur, "uDir", 1f / bw2, 0f) }
        quad(fboB[2], bw2, bh2, pBlur, texB[3]) { u2(pBlur, "uDir", 0f, 1f / bh2) }
    }

    private fun passComposite() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, w, h)
        GL.glDisable(GL.GL_BLEND)
        use(pComposite)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, texScene)
        GL.glActiveTexture(GL.GL_TEXTURE1); GL.glBindTexture(GL.GL_TEXTURE_2D, texB[0])
        GL.glActiveTexture(GL.GL_TEXTURE2); GL.glBindTexture(GL.GL_TEXTURE_2D, texB[2])
        u1i(pComposite, "uScene", 0); u1i(pComposite, "uB1", 1); u1i(pComposite, "uB2", 2)
        u1(pComposite, "uBloom", 1.25f + 0.4f * ctl.flash + 0.3f * ctl.level)
        GL.glBindVertexArray(vaoEmpty)
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    private inline fun quad(fbo: Int, vw: Int, vh: Int, prog: Int, src: Int, setup: () -> Unit) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glViewport(0, 0, vw, vh)
        use(prog)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D, src)
        u1i(prog, "uTex", 0)
        setup()
        GL.glDrawArrays(GL.GL_TRIANGLES, 0, 3)
    }

    // ==================================================================== GL の道具

    private fun buildOrbBuffers() {
        vaoOrb = gen { GL.glGenVertexArrays(1, it, 0) }
        GL.glBindVertexArray(vaoOrb)
        vboOrb = gen { GL.glGenBuffers(1, it, 0) }
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vboOrb)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, inter.size * 4, null, GL.GL_DYNAMIC_DRAW)
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 24, 0)
        GL.glEnableVertexAttribArray(1); GL.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, 24, 12)
        vboRest = gen { GL.glGenBuffers(1, it, 0) }
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vboRest)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, mesh.v.size * 4, floatBuffer(mesh.v.size).put(mesh.v).position(0), GL.GL_STATIC_DRAW)
        GL.glEnableVertexAttribArray(2); GL.glVertexAttribPointer(2, 3, GL.GL_FLOAT, false, 12, 0)
        iboOrb = gen { GL.glGenBuffers(1, it, 0) }
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, iboOrb)
        val idx = ShortArray(mesh.tris.size) { mesh.tris[it].toShort() }
        GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, shortBuffer(idx), GL.GL_STATIC_DRAW)
        GL.glBindVertexArray(0)
    }

    /** 目の形の元 (半径 1 の球。行列で楕円体にする) */
    private fun buildEyeBuffers() {
        val pos = ArrayList<Float>()
        for (r in 0..eyeRings) {
            val th = PI * r / eyeRings
            for (s in 0..eyeSegs) {
                val ph = 2 * PI * s / eyeSegs
                pos.add((sin(th) * cos(ph)).toFloat()); pos.add(cos(th).toFloat()); pos.add((sin(th) * sin(ph)).toFloat())
            }
        }
        val idx = ArrayList<Short>()
        val row = eyeSegs + 1
        for (r in 0 until eyeRings) for (s in 0 until eyeSegs) {
            val a = r * row + s; val b = a + row
            // 外から見て反時計回り
            idx.add(a.toShort()); idx.add((a + 1).toShort()); idx.add(b.toShort())
            idx.add((a + 1).toShort()); idx.add((b + 1).toShort()); idx.add(b.toShort())
        }
        eyeIndexCount = idx.size
        vaoEye = gen { GL.glGenVertexArrays(1, it, 0) }
        GL.glBindVertexArray(vaoEye)
        vboEye = gen { GL.glGenBuffers(1, it, 0) }
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vboEye)
        val pa = pos.toFloatArray()
        GL.glBufferData(GL.GL_ARRAY_BUFFER, pa.size * 4, floatBuffer(pa.size).put(pa).position(0), GL.GL_STATIC_DRAW)
        GL.glEnableVertexAttribArray(0); GL.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 12, 0)
        iboEye = gen { GL.glGenBuffers(1, it, 0) }
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, iboEye)
        GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, shortBuffer(idx.toShortArray()), GL.GL_STATIC_DRAW)
        GL.glBindVertexArray(0)
    }

    private fun createTargets() {
        // 中: 暗い色の階調がなめらかになるよう、使えるなら 16bit の浮動小数で
        texInterior = tex(w, h, halfFloat)
        rbInteriorDepth = depthRb(w, h)
        fboInterior = fbo(texInterior, rbInteriorDepth)
        if (GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) != GL.GL_FRAMEBUFFER_COMPLETE && halfFloat) {
            RabiLog.w("orb3d", "half float target incomplete, using RGBA8")
            halfFloat = false
            deleteTargets(); createTargets(); return
        }
        texScene = tex(w, h, false)
        rbSceneDepth = depthRb(w, h)
        fboScene = fbo(texScene, rbSceneDepth)
        bw1 = maxOf(1, w / 4); bh1 = maxOf(1, h / 4); bw2 = maxOf(1, w / 8); bh2 = maxOf(1, h / 8)
        for (i in 0..3) {
            val (tw, th) = if (i < 2) bw1 to bh1 else bw2 to bh2
            texB[i] = tex(tw, th, false)
            fboB[i] = fbo(texB[i], 0)
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
    }

    private fun deleteTargets() {
        val fbos = intArrayOf(fboInterior, fboScene, *fboB).filter { it != 0 }.toIntArray()
        if (fbos.isNotEmpty()) GL.glDeleteFramebuffers(fbos.size, fbos, 0)
        val texs = intArrayOf(texInterior, texScene, *texB).filter { it != 0 }.toIntArray()
        if (texs.isNotEmpty()) GL.glDeleteTextures(texs.size, texs, 0)
        val rbs = intArrayOf(rbInteriorDepth, rbSceneDepth).filter { it != 0 }.toIntArray()
        if (rbs.isNotEmpty()) GL.glDeleteRenderbuffers(rbs.size, rbs, 0)
        fboInterior = 0; fboScene = 0; texInterior = 0; texScene = 0
        rbInteriorDepth = 0; rbSceneDepth = 0
        fboB.fill(0); texB.fill(0)
    }

    fun release() {
        runCatching {
            deleteTargets()
            for (p in intArrayOf(pInterior, pEye, pGlass, pHalo, pBright, pBlur, pComposite)) if (p != 0) GL.glDeleteProgram(p)
            GL.glDeleteBuffers(5, intArrayOf(vboOrb, vboRest, iboOrb, vboEye, iboEye), 0)
            GL.glDeleteVertexArrays(3, intArrayOf(vaoOrb, vaoEye, vaoEmpty), 0)
        }
    }

    private fun tex(tw: Int, th: Int, hf: Boolean): Int {
        val t = gen { GL.glGenTextures(1, it, 0) }
        GL.glBindTexture(GL.GL_TEXTURE_2D, t)
        if (hf) GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA16F, tw, th, 0, GL.GL_RGBA, GL.GL_HALF_FLOAT, null)
        else GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, tw, th, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return t
    }

    private fun depthRb(rw: Int, rh: Int): Int {
        val r = gen { GL.glGenRenderbuffers(1, it, 0) }
        GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, r)
        GL.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_DEPTH_COMPONENT24, rw, rh)
        return r
    }

    private fun fbo(colorTex: Int, depth: Int): Int {
        val f = gen { GL.glGenFramebuffers(1, it, 0) }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, f)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, colorTex, 0)
        if (depth != 0) GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, depth)
        return f
    }

    private fun program(vs: String, fs: String): Int {
        val v = shader(GL.GL_VERTEX_SHADER, vs); val f = shader(GL.GL_FRAGMENT_SHADER, fs)
        val p = GL.glCreateProgram()
        GL.glAttachShader(p, v); GL.glAttachShader(p, f); GL.glLinkProgram(p)
        val ok = IntArray(1).also { GL.glGetProgramiv(p, GL.GL_LINK_STATUS, it, 0) }[0]
        GL.glDeleteShader(v); GL.glDeleteShader(f)
        if (ok == 0) { val log = GL.glGetProgramInfoLog(p); GL.glDeleteProgram(p); error("link: $log") }
        programs[p] = HashMap()
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GL.glCreateShader(type)
        GL.glShaderSource(s, src); GL.glCompileShader(s)
        val ok = IntArray(1).also { GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, it, 0) }[0]
        if (ok == 0) { val log = GL.glGetShaderInfoLog(s); GL.glDeleteShader(s); error("compile: $log") }
        return s
    }

    private fun use(p: Int) = GL.glUseProgram(p)
    private fun loc(p: Int, name: String) = programs[p]!!.getOrPut(name) { GL.glGetUniformLocation(p, name) }
    private fun u1(p: Int, n: String, a: Float) = GL.glUniform1f(loc(p, n), a)
    private fun u1i(p: Int, n: String, a: Int) = GL.glUniform1i(loc(p, n), a)
    private fun u2(p: Int, n: String, a: Float, b: Float) = GL.glUniform2f(loc(p, n), a, b)
    private fun u3(p: Int, n: String, a: Float, b: Float, c: Float) = GL.glUniform3f(loc(p, n), a, b, c)
    private fun mat(p: Int, n: String, m: FloatArray) = GL.glUniformMatrix4fv(loc(p, n), 1, false, m, 0)

    private inline fun gen(f: (IntArray) -> Unit): Int { val a = IntArray(1); f(a); return a[0] }

    companion object {
        const val STEP = 1f / 60f
        /** カメラまでの距離 (球の半径 = 1)。長めのレンズで、物撮りのように歪みを少なく */
        const val CAM_D = 7f
        /** 目のある層の半径 (表面より少し内側) */
        const val EYE_DEPTH = 0.86f
        /** 表面を伸ばせる長さ (半径に対する割合): 外・横へ引くとき、体の中へ押し込む向きのとき */
        const val STRETCH_OUT = 0.55f
        const val STRETCH_IN = 0.42f
        const val BLINK = 0.18f

        private fun floatBuffer(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private fun shortBuffer(a: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(a).also { it.position(0) }
    }
}
