"""書き出した動画を確かめる（読むだけ。動画や台本は書き換えない）。
- 長さ（既定で 3 分以内）・映像と音の形式・映像と音の長さの差
- 音の大きさ（-16 LUFS ±1、ピーク -1 dBTP 以下）と、2 秒以上の無音
- コマ: 10 秒ごとの一覧（sheet.png）と、--at で指定した秒の原寸のコマ（at_<秒>.png）
- --src: 動画のフォルダの台本・画面のファイル（*.html・timeline.json・voices.py）に、ボードの名前が無いか（あれば NG）。
  製品・会社の名前は一覧にする（本人が望んだ所だけか、目で確かめる）。timeline.json の長さとも比べる
使い方: python3 check.py <動画> [--out フォルダ] [--at 秒,秒,...] [--src 動画のフォルダ] [--limit 180] [--every 10]
NG が 1 つでもあれば、終わりの番号は 1"""
import argparse, glob, json, os, re, subprocess, sys, tempfile

ap = argparse.ArgumentParser()
ap.add_argument("video")
ap.add_argument("--out", default=os.path.join(tempfile.gettempdir(), "promo-video-check"))
ap.add_argument("--at", default="")
ap.add_argument("--src", default="")
ap.add_argument("--limit", type=float, default=180.0)
ap.add_argument("--every", type=float, default=10.0)
a = ap.parse_args()
os.makedirs(a.out, exist_ok=True)
report, ng = [], [0]


def say(mark, text):
    if mark == "NG": ng[0] += 1
    line = f"[{mark}] {text}"; report.append(line); print(line, flush=True)


def probe(sel, entries):
    r = subprocess.run(["ffprobe", "-v", "error"] + sel + ["-show_entries", entries, "-of", "json", a.video], capture_output=True, text=True)
    return json.loads(r.stdout or "{}")


def mmss(t):
    return f"{int(t // 60)}:{t % 60:05.2f}"


# 1. 長さと形式
dur = float(probe([], "format=duration")["format"]["duration"])
say("OK" if dur <= a.limit else "NG", f"長さ {mmss(dur)}（上限 {mmss(a.limit)}）")
v = (probe(["-select_streams", "v:0"], "stream=codec_name,width,height,r_frame_rate,pix_fmt,duration").get("streams") or [{}])[0]
au = (probe(["-select_streams", "a:0"], "stream=codec_name,sample_rate,channels,duration").get("streams") or [{}])[0]
say("OK" if v else "NG", f"映像 {v.get('codec_name')} {v.get('width')}×{v.get('height')} {v.get('r_frame_rate')} {v.get('pix_fmt')}")
say("OK" if au else "NG", f"音 {au.get('codec_name')} {au.get('sample_rate')} Hz {au.get('channels')} ch" if au else "音がない")
if v.get("duration") and au.get("duration"):
    gap = abs(float(v["duration"]) - float(au["duration"]))
    say("OK" if gap <= 0.1 else "確認", f"映像と音の長さの差 {gap:.2f} 秒")

# 2. 音の大きさと無音
if au:
    err = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", a.video, "-map", "0:a:0", "-af", "ebur128=peak=true", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    s = err[err.rfind("Summary:"):]
    mi, mp = re.search(r"I:\s+(-?[\d.]+) LUFS", s), re.search(r"Peak:\s+(-?[\d.]+|-inf) dBFS", s)
    if mi:
        i = float(mi.group(1))
        say("OK" if abs(i + 16) <= 1 else "NG", f"音の大きさ {i:.1f} LUFS（-16 にそろえる）")
    if mp:
        p = float(mp.group(1)) if mp.group(1) != "-inf" else -99.0
        say("OK" if p <= -1.0 else "NG", f"ピーク {p:.1f} dBTP（-1.0 以下）")
    err = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", a.video, "-map", "0:a:0", "-af", "silencedetect=noise=-50dB:d=2", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    sil = re.findall(r"silence_end: ([\d.]+) \| silence_duration: ([\d.]+)", err)
    if sil:
        say("確認", "2 秒以上の無音: " + "、".join(f"{mmss(float(e) - float(d))}〜{mmss(float(e))}" for e, d in sil) + "（わざとの間か、音が抜けたか）")
    else:
        say("OK", "2 秒以上の無音はない")

# 3. コマ
tmp = tempfile.mkdtemp()
times = [t + .5 for t in range(0, int(dur), max(1, int(a.every)))]
for k, t in enumerate(times):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{t:.2f}", "-i", a.video, "-frames:v", "1", "-vf", "scale=480:270", os.path.join(tmp, f"{k:03d}.png")], check=True)
sheet = os.path.join(a.out, "sheet.png")
try:
    from PIL import Image, ImageDraw, ImageFont
    W, H, C = 480, 270, 4
    im = Image.new("RGB", (C * W, ((len(times) + C - 1) // C) * (H + 30)), "#111"); d = ImageDraw.Draw(im)
    font = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 20)
    for k, t in enumerate(times):
        x, y = (k % C) * W, (k // C) * (H + 30)
        im.paste(Image.open(os.path.join(tmp, f"{k:03d}.png")), (x, y + 30)); d.text((x + 8, y + 4), mmss(t), fill="#FFD86B", font=font)
    im.save(sheet)
except ImportError:   # Pillow が無いときは、時刻の文字なしで並べる
    rows = (len(times) + 3) // 4
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", os.path.join(tmp, "%03d.png"), "-vf", f"tile=4x{rows}", "-frames:v", "1", sheet], check=True)
say("確認", f"{a.every:g} 秒ごとのコマの一覧 → {sheet}（Read で見る）")
for s in [x for x in a.at.split(",") if x.strip()]:
    p = os.path.join(a.out, f"at_{s.strip()}.png")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", s.strip(), "-i", a.video, "-frames:v", "1", p], check=True)
    say("確認", f"{s.strip()} 秒のコマ → {p}")

# 4. 台本・画面のファイル
if a.src:
    files = sorted(set(glob.glob(os.path.join(a.src, "*.html")) + [f for f in (os.path.join(a.src, "timeline.json"), os.path.join(a.src, "voices.py")) if os.path.exists(f)]))
    board = re.compile(r"UNO[ _-]?Q|\bunoq\b|Arduino", re.I)
    brand = re.compile(r"\b(ChatGPT|OpenAI|GPT-\d\w*|Gemini|Google|Amazon|YouTube|LINE|iPhone|Pixel|Claude|Anthropic)\b")
    hits, names = [], {}
    for f in files:
        for n, line in enumerate(open(f, encoding="utf-8", errors="replace"), 1):
            if board.search(line): hits.append(f"{os.path.basename(f)}:{n}")
            for m in brand.finditer(line): names.setdefault(m.group(1), []).append(f"{os.path.basename(f)}:{n}")
    say("NG" if hits else "OK", "ボードの名前: " + ("、".join(hits[:12]) if hits else "なし") + f"（{len(files)} ファイル）")
    if names:
        say("確認", "製品・会社の名前（本人が望んだ所だけか）: " + "、".join(f"{k} {len(v)} か所（{v[0]}…）" for k, v in sorted(names.items())))
    tl = os.path.join(a.src, "timeline.json")
    if os.path.exists(tl):
        td = float(json.load(open(tl))["duration"])
        say("OK" if abs(td - dur) <= 0.2 else "確認", f"台本の長さ {mmss(td)} と動画の長さの差 {abs(td - dur):.2f} 秒")

say("確認", "音は Claude Code には聞けない。本人に、通して耳で確かめてもらう（読み間違い・音割れ・声の重なり・大きさのむら）")
open(os.path.join(a.out, "report.txt"), "w").write("\n".join(report) + "\n")
print(f"\n{'NG が ' + str(ng[0]) + ' 個' if ng[0] else 'NG はなし'}。記録 → {os.path.join(a.out, 'report.txt')}")
sys.exit(1 if ng[0] else 0)
