package com.rabi.envoymcp.ui.avatar.orb

/**
 * 光る球の 3D 描画のシェーダー (OpenGL ES 3.0)。
 * 1. 中 (球の奥の面と目) を別の画像に描く
 * 2. 手前の面: 中を屈折させて透かし、まわりの照明の映り込みを重ねる (ガラスの性質: 縁ほど強く映す)
 * 3. 明るい所だけを取り出してぼかし、光のにじみとして足す
 * 色は仕様の通り: 中 #101C30〜#263B58、縁 #A8DFFF、下側 #71D9FF、目 #DBF7FF〜#FFFFFF。口は描かない。
 */
internal object OrbShaders {
    private const val HEAD = "#version 300 es\nprecision highp float;\n"

    /** 明るい所を白く飛ばさないための、ゆるいトーンカーブ */
    private const val TONE = """
vec3 tone(vec3 c) { return vec3(1.0) - exp(-c * 1.25); }
"""

    const val ORB_VS = HEAD + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aRest;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vPos;
out vec3 vN;
out vec3 vRest;
void main() {
    vPos = (uModel * vec4(aPos, 1.0)).xyz;
    vN = mat3(uModel) * aNormal;
    vRest = aRest;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    /** 球の奥の面 (内側から見る): ほぼ黒の紺、下から入って中で散る青い光、内側の壁の映り込み、内側の殻の線、下端の光点 */
    const val INTERIOR_FS = HEAD + """
in vec3 vPos;
in vec3 vN;
in vec3 vRest;
uniform vec3 uCam;
uniform vec3 uCenter;
uniform float uScale;
uniform float uK;
uniform float uOut;
out vec4 o;
void main() {
    vec3 V = normalize(uCam - vPos);
    vec3 N = normalize(vN);
    float ndv = abs(dot(N, V));
    // 面の向き (画面の上で)。縁の線・光点は、面の向きと見る角度で決める (伸ばしても輪郭に沿う。中に線が残って口に見えることがない)
    vec2 nd = N.xy / max(length(N.xy), 1e-4);
    // 画面から見た位置 (全体の色の濃淡)
    vec2 qv = (vPos.xy - uCenter.xy) / uScale;
    float dv = length(qv);
    // 体に固定した位置 (中の光。球が回ると一緒に動く)
    vec2 q = vRest.xy;
    float d = length(q);
    float z = sqrt(max(1.0 - d * d, 0.0));
    vec3 col = mix(vec3(0.006, 0.011, 0.022), vec3(0.055, 0.098, 0.170), smoothstep(0.35, 1.0, dv));
    col *= 1.0 - 0.55 * smoothstep(-0.2, 0.9, qv.y);
    // 下の内側にある光から、視線に沿って中で散る光 (距離の 2 乗で弱まる光を、球の厚みの分だけ足し合わせた式)
    vec2 lp = vec2(max(abs(q.x) - 0.42, 0.0), q.y + 0.90);
    float a = sqrt(dot(lp, lp) + 0.03);
    float ins = 2.0 / a * atan(z / a);
    col += vec3(0.06, 0.27, 0.60) * ins * 0.040 * uK;
    // 内側の壁の映り込み (縁の近く)
    col += vec3(0.40, 0.68, 1.0) * pow(1.0 - ndv, 5.0) * 0.28 * uK;
    // 内側の殻の線 (ガラスの厚み)。球のときは半径 0.93 と 0.96 の所。右上と下で少し強く
    float ring = exp(-pow((ndv - 0.367) / 0.018, 2.0)) + 0.55 * exp(-pow((ndv - 0.273) / 0.018, 2.0));
    float ringW = 0.14 + 0.32 * smoothstep(0.3, 0.95, (nd.x + nd.y) * 0.7071) + 0.30 * smoothstep(-0.2, -0.95, nd.y) + 0.12 * smoothstep(-0.4, -0.95, nd.x);
    col += vec3(0.62, 0.86, 1.0) * ring * ringW * uK;
    // 下端の光点 (ガラスを通った光が集まる): 下を向いた縁のごく近く
    float bottom = pow(max(-nd.y, 0.0), 40.0) * smoothstep(0.45, 0.2, ndv) * smoothstep(0.0, 0.12, ndv);
    col += vec3(0.80, 0.96, 1.0) * bottom * 0.85 * uK;
    o = vec4(col * uOut, 1.0);
}
"""

    const val EYE_VS = HEAD + """
layout(location = 0) in vec3 aPos;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat4 uEye;
out vec3 vLocal;
out vec3 vPos;
out vec3 vN;
void main() {
    vec4 mp = uEye * vec4(aPos, 1.0);
    vPos = (uModel * mp).xyz;
    vN = mat3(uModel) * (transpose(inverse(mat3(uEye))) * aPos);
    vLocal = aPos;
    gl_Position = uMVP * mp;
}
"""

    /**
     * 目: 青白く光るガラス玉。中央は淡い水色、縁と下側ほど白い。右上に白いつや、左下に小さな映り込み。
     * 感情の表情: まぶた (uClose・uCurve・uSmile)、uDroop で悲しい目 (外側 = uSide の向きが下がった上まぶたで上を削る)、
     * uSqueeze で笑う目 (＞＜: 鼻の側を向いた太い「く」の字だけ残す)。
     * まぶた: 上まぶたと下まぶたが、閉じた線 (uCurve で反る) へ寄っていく。閉じきると、両端の細い薄い線になる。
     * uSmile では下まぶたが先に上がり、上の丸みが残る (頬に押された自然な笑い目)。
     * 閉じる途中は、上まぶたを上に反った弧にし (目尻から閉じる。平らな線だとにらんだ目に見える)、下まぶたは目の丸みに沿った弧
     * (にっこりのときは上に押し上がる弧) にする
     * uMode = 1 のときは、ほほの光 (中心ほど濃い桃色を足し算で重ねる。同じ玉の形を使う)
     */
    const val EYE_FS = HEAD + """
in vec3 vLocal;
in vec3 vPos;
in vec3 vN;
uniform vec3 uCam;
uniform float uK;
uniform float uHl;
uniform float uOut;
uniform float uDroop;
uniform float uSide;
uniform float uMode;
uniform float uBlush;
uniform float uSqueeze;
uniform float uClose;
uniform float uCurve;
uniform float uSmile;
out vec4 o;
float seg(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a; vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}
void main() {
    if (uMode > 0.5) {
        float rr = clamp(dot(vLocal.xy, vLocal.xy), 0.0, 1.0);
        float soft = pow(1.0 - rr, 1.6);
        o = vec4(vec3(1.0, 0.42, 0.62) * (soft * uBlush * 0.55 * uOut), 0.0);
        return;
    }
    vec3 N = normalize(vN);
    vec3 V = normalize(uCam - vPos);
    float ndv = clamp(dot(N, V), 0.0, 1.0);
    vec3 col = mix(vec3(0.80, 0.94, 1.0), vec3(0.30, 0.55, 0.88), smoothstep(0.15, 0.9, ndv) * 0.9);
    col += vec3(0.22, 0.26, 0.24) * smoothstep(0.0, -0.95, vLocal.y) * smoothstep(0.2, 0.8, ndv);
    vec3 L = normalize(vec3(0.42, 0.62, 0.66));
    float sp = smoothstep(0.955, 0.982, dot(N, normalize(L + V)));
    vec3 L2 = normalize(vec3(-0.55, -0.60, 0.55));
    float sp2 = pow(max(dot(N, normalize(L2 + V)), 0.0), 140.0);
    col = col * uK + vec3(1.0) * sp * uHl * 1.1 + vec3(0.85, 0.96, 1.0) * sp2 * 0.30 * uHl;
    // 輪郭の 1.5 ピクセルほどを薄くする (ギザギザを出さない。MSAA を使わずに済むので軽い)
    float a = clamp(ndv / max(fwidth(ndv) * 1.5, 1e-4), 0.0, 1.0);
    float m = 1.0;
    if (uClose > 0.001) {
        // まぶた: 閉じた線 yc (反り uCurve) と、その太さの半分 th (両端ほど細い)。上まぶたは上から、下まぶたは下から、そこへ寄る
        vec2 p = vLocal.xy;
        float x2 = clamp(p.x * p.x, 0.0, 1.0);
        float yc = -0.05 + uCurve * 0.42 * (1.0 - x2);
        float th = 0.16 * sqrt(1.0 - x2) + 0.012;
        float ku = uClose * (1.0 - 0.45 * uSmile);
        float kl = min(1.0, uClose * (1.0 + uSmile));
        float yu = mix(1.25, yc + th, ku) + 0.32 * 4.0 * ku * (1.0 - ku) * (1.0 - x2);
        float yl = mix(-1.25, yc - th, kl) + (0.35 * uSmile - 0.25 * (1.0 - uSmile)) * 4.0 * kl * (1.0 - kl) * (1.0 - x2);
        float ad = max(fwidth(p.y) * 1.5, 1e-4);
        m *= (1.0 - smoothstep(yu - ad, yu + ad, p.y)) * smoothstep(yl - ad, yl + ad, p.y);
    }
    if (uSqueeze > 0.001) {
        // 笑う目: 左目は ＞、右目は ＜ (とがった先が鼻の側)。太い線の「く」の字だけを残し、普段の目から移る
        vec2 p = vLocal.xy;
        vec2 a = vec2(uSide * 0.62, 0.52);
        vec2 b = vec2(-uSide * 0.60, 0.0);
        vec2 c = vec2(uSide * 0.62, -0.52);
        float d = min(seg(p, a, b), seg(p, b, c)) - 0.18;
        float ad = max(fwidth(p.y) * 1.5, 1e-4);
        m *= mix(1.0, 1.0 - smoothstep(-ad, ad, d), uSqueeze);
    }
    if (uDroop > 0.001) {
        // 上まぶた: 外側ほど下がる線より上を削る
        float lid = mix(1.6, 0.45, uDroop) - uSide * uDroop * 0.55 * vLocal.x;
        float ay = max(fwidth(vLocal.y) * 1.5, 1e-4);
        m *= 1.0 - smoothstep(lid - ay, lid + ay, vLocal.y);
    }
    if (m < 0.004) discard;
    o = vec4(col * uOut * a * m, a * m);
}
"""

    /** 手前の面: 屈折して見える中 + 照明の映り込み (フレネル) + 縁の光 + 鋭いつや */
    const val GLASS_FS = HEAD + TONE + """
in vec3 vPos;
in vec3 vN;
in vec3 vRest;
uniform sampler2D uInterior;
uniform vec2 uViewport;
uniform vec3 uCam;
uniform float uK;
uniform float uWarm;
uniform vec3 uTint;
uniform float uTintAmt;
uniform float uPhase;
uniform float uRefrPx;
uniform float uIn;
out vec4 o;

// スタジオの照明 (映り込み用)。奥の輪の光・右上の大きな柔らかい光・左の細い光・下からのシアンの照り返し。
// 四角い主照明は置かない (照明は時間とともに球のまわりを回るので、四角い映り込みが顔の上を横切るため)
vec3 env(vec3 r) {
    float c = cos(uPhase * 0.15);
    float s = sin(uPhase * 0.15);
    r.xz = mat2(c, -s, s, c) * r.xz;
    vec3 col = vec3(0.006, 0.010, 0.020);
    col += vec3(0.30, 0.55, 0.90) * smoothstep(-0.35, -0.95, r.z) * 0.9;
    col +=vec3(0.75, 0.88, 1.0) * smoothstep(0.82, 0.97, dot(r, normalize(vec3(0.66, 0.66, -0.12)))) * 1.3;
    col += vec3(0.55, 0.72, 0.95) * smoothstep(0.90, 0.99, dot(r, normalize(vec3(-0.85, 0.25, -0.45)))) * 1.4;
    col += vec3(0.27, 0.78, 1.0) * smoothstep(-0.30, -0.85, r.y) * 1.4;
    return col;
}

void main() {
    vec3 N = normalize(vN);
    vec3 V = normalize(uCam - vPos);
    float ndv = clamp(dot(N, V), 0.0, 1.0);
    float F = 0.04 + 0.96 * pow(1.0 - ndv, 5.0);
    vec3 refl = env(reflect(-V, N));
    // 屈折: 中は縁に近いほど内側から引き伸ばされて見える (ガラス玉のレンズの効果)
    vec3 T = refract(-V, N, 1.0 / 1.35);
    vec2 uv = (gl_FragCoord.xy + (T.xy + V.xy) * uRefrPx) / uViewport;
    vec3 inner = texture(uInterior, uv).rgb * uIn;
    vec3 col = inner * (1.0 - F) + refl * F;
    // 縁の光 (球そのものが縁でほのかに光る)。下はシアン寄り。困ったときは左下に少し暖色
    vec2 dir = N.xy / max(length(N.xy), 1e-4);
    vec3 rimC = mix(vec3(0.659, 0.875, 1.0), vec3(0.443, 0.851, 1.0), smoothstep(0.2, -0.9, dir.y) * 0.6);
    rimC = mix(rimC, vec3(1.0, 0.79, 0.54), uWarm * 0.35 * smoothstep(0.2, -0.6, dir.x + dir.y * 0.5));
    // 表情の色: 縁をその色に寄せ、縁の内側をほんのり光らせる (うれしい・笑うは金色)
    rimC = mix(rimC, uTint, uTintAmt * 0.5);
    col += uTint * vec3(1.0, 0.93, 0.92) * pow(1.0 - ndv, 3.0) * uTintAmt * 0.28 * uK;
    float tr = max(dot(dir, vec2(0.62, 0.78)), 0.0);
    float rimW = 0.55 + 1.2 * pow(tr, 8.0) + 0.70 * pow(max(-dir.y, 0.0), 4.0);
    col += rimC * (pow(1.0 - ndv, 2.4) * 0.62 + pow(1.0 - ndv, 8.0) * 0.9) * rimW * uK;
    float a = clamp(ndv / max(fwidth(ndv) * 1.5, 1e-4), 0.0, 1.0);
    o = vec4(tone(col) * a, a);
}
"""

    /** 画面全体を覆う三角形 (頂点データなし) */
    const val QUAD_VS = HEAD + """
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** 球のまわりの淡い光と、遠くの淡い輪 (足し算の光) */
    const val HALO_FS = HEAD + """
in vec2 vUv;
uniform vec2 uC;
uniform float uR;
uniform float uK;
uniform vec3 uTintHalo;
uniform float uTintAmt;
uniform float uTintLift;
out vec4 o;
void main() {
    vec2 p = (gl_FragCoord.xy - uC) / uR;
    float d = length(p);
    float e = max(d - 1.0, 0.0);
    float g = (0.36 * exp(-e / 0.07) + 0.15 * exp(-e / 0.40)) * uK;
    g += 0.05 * exp(-pow((d - 1.22) / 0.03, 2.0)) * uK;
    float lower = clamp(-p.y / max(d, 1e-3), 0.0, 1.0);
    vec3 c = mix(vec3(0.16, 0.40, 0.78), vec3(0.30, 0.75, 1.0), lower * 0.5);
    // 表情の色: まわりの光をその色に寄せ、明るさを増減する (うれしい・笑うは金色で明るく、悲しいは青で暗く)
    c = mix(c, uTintHalo, uTintAmt * 0.55) * g * max(1.0 + uTintAmt * uTintLift, 0.0);
    o = vec4(c, 0.0);
}
"""

    /** 明るい所だけを取り出して 1/4 に縮める */
    const val BRIGHT_FS = HEAD + """
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTexel;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv + uTexel * vec2(-1.0, -1.0)).rgb + texture(uTex, vUv + uTexel * vec2(1.0, -1.0)).rgb
           + texture(uTex, vUv + uTexel * vec2(-1.0, 1.0)).rgb + texture(uTex, vUv + uTexel * vec2(1.0, 1.0)).rgb;
    c *= 0.25;
    float l = max(c.r, max(c.g, c.b));
    o = vec4(c * smoothstep(0.30, 0.85, l), 1.0);
}
"""

    /** 一方向のぼかし (9 点のガウスを 5 回の読み取りで) */
    const val BLUR_FS = HEAD + """
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv).rgb * 0.2270270270;
    c += (texture(uTex, vUv + uDir * 1.3846153846).rgb + texture(uTex, vUv - uDir * 1.3846153846).rgb) * 0.3162162162;
    c += (texture(uTex, vUv + uDir * 3.2307692308).rgb + texture(uTex, vUv - uDir * 3.2307692308).rgb) * 0.0702702703;
    o = vec4(c, 1.0);
}
"""

    /** 仕上げ: 本体 + にじむ光。わずかなノイズで暗い所の階調の段を目立たなくする。透明度は乗算済みで出す */
    const val COMPOSITE_FS = HEAD + """
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uB1;
uniform sampler2D uB2;
uniform float uBloom;
out vec4 o;
void main() {
    vec4 s = texture(uScene, vUv);
    vec3 b = texture(uB1, vUv).rgb * 0.30 + texture(uB2, vUv).rgb * 0.80;
    vec3 c = s.rgb + b * uBloom;
    float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    c = max(c + (n - 0.5) / 255.0, 0.0);
    float a = clamp(max(s.a, max(c.r, max(c.g, c.b))), 0.0, 1.0);
    o = vec4(min(c, vec3(a)), a);
}
"""
}
