---
name: promo-video
description: プロモーション動画・デモ動画（AgentPhone・AgentARM・リゲルなど）を作る・直す・書き出すときに使う。台本と合成音声、HTML を 1 コマずつ撮る画面、腕の 3D、本人が撮った実写の差し込み（文字起こしして使う所を選んで切る）、-16 LUFS の音の仕上げ、書き出したあとの確認（長さ・音の大きさ・要所のコマ）、ChatGPT に人物・背景・アイコンの画像を頼む引き継ぎの文書づくりまで。「動画を作って」「動画を直して」「実写を入れて」「書き出し直して」「画像生成の依頼を書いて」と頼まれたとき。
---

> このリポジトリに入れた写し。動画の作業フォルダ（`~/Desktop/codex/AgentARM/videos/`）はリポジトリの外にあり、作者の手元で使う前提です。

# 動画づくり（台本 → 声 → 画面 → 実写 → 音 → つなぐ → 確かめる）

手本は `~/Desktop/codex/AgentARM/videos/agentphone/`（「AgentPhone — スマホに、からだを。」2:59）。同じ作りの動画が `../bwc`・`../pai2`・`../pai3` にもある。
まず手本の `SCENARIO.md`（台本と作り直す手順）と `REPLACE.md`（実写の使い方）を読んでから始める。

## 決め事（いちばん先に守る）

- **頼まれた物だけ作る**: 頼まれていない BGM・字幕・効果音・場面・ナレーションを勝手に足さない。足したほうがよいと思ったら、先に聞く
- **元の物を書き換えない**: 本人が撮った映像（`assets/real/`）、前の動画のフォルダ、読むだけのファイル（下の「前提」の表）は書き換えない。新しい動画は新しいフォルダで作る
- **消さない**: 中間ファイル（`part_*.mp4`・`silent.mp4`・`gen/frames/`・`audio*.wav` など）は自分で消さない。容量が気になるときは、消す候補を一覧のファイル（例 `videos/DELETE_CANDIDATES.txt`）にして、本人に消してもらう
- **API キーを勝手に使わない**: 声は edge-tts（キー不要）が既定。キーの要る声（Breeze など）や画像の生成は、本人に聞いてから
- **出さないもの**: ボードの名前（「腕のボード」と言う）、本物の電話番号・名前（ぼかす）、実在のアプリの画面やロゴ。AI の製品・会社の名前は、本人が望んだ所だけ（例「Amazon・YouTube Music などのアプリでも」）。人物・番号・店・会社は架空にし、右下に「再現映像・合成音声・人物は架空です」の注意を出す
- **音は聞けない**: Claude Code は音を聞けない。書き出したら、本人に通して耳で確かめてもらう（読み間違い・音割れ・声の重なり）
- **重い書き出しは裏で**: 書き出しは Bash の `run_in_background` で動かし、終わりの知らせを待つ（数を数えて待たない）
- 返事・台本・手順書は日本語・やさしい言葉で。コミットは頼まれてから

## 前提（道具とありか）

| もの | ありか・入れ方 | 使う所 |
| --- | --- | --- |
| Google Chrome・playwright・ffmpeg/ffprobe・Pillow・numpy・mujoco | システムの `python3` と Homebrew | 画面を撮る・音を作る・3D |
| edge-tts | `../pai/.venv`（動画のフォルダの `.venv` はそこへのリンク） | 声 |
| three.js | `../work/render/node_modules`（動画のフォルダの `node_modules` はそこへのリンク） | 腕の 3D の描画 |
| whisper.cpp | `brew install whisper-cpp`（`whisper-cli`）。モデルは `~/.cache/whisper-cpp/ggml-large-v3-turbo-q5_0.bin` | 実写の文字起こし |
| リゲルの球の描き方・BGM の音源 | `~/Desktop/codex/luncher_ai/promo/hackathon.html`（読むだけ。`render.py` が `gen/orb.js` に写し、`audio.py` が音源を読む） | 画面・音 |
| 腕の制御のプログラム | `~/Desktop/codex/luncher_ai/agentarm/linux`（agentarm_d。読むだけ） | 3D の腕 |
| 腕のまね | `../pai/sim_run.py`（`VideoSim`・`learn_roll`・`MOUNT`。読むだけ） | 3D の腕 |
| 腕の MuJoCo のモデル | `~/Desktop/codex/AgentARM/urdf_export/agentarm_description/mujoco/agentarm.xml`（agentarm_d がこの場所を決め打ちで読む。動かさない） | 3D の腕 |

新しい動画を作るときは、手本のフォルダから `render.py`・`index.html`・`timeline.py`・`voices.py`・`audio.py`・`mix.html`・`cut_real.py`・`sim_run.py`・`chk/` を新しいフォルダへ写し、`.venv` と `node_modules` のリンクを張る（`ln -s ../pai/.venv .venv`、`ln -s ../work/render/node_modules node_modules`）。

## 手順

ここから先のコマンドは、動画のフォルダ（例 `~/Desktop/codex/AgentARM/videos/agentphone`）で動かす。

### 1. 台本（`SCENARIO.md`・`voices.py`・`timeline.py`）

- 本人と、長さ（提出の決まり。例 3 分以内）・伝えたいこと・言わないこと・場面の並びを決め、`SCENARIO.md` に書く
- 台詞は `voices.py` の `LINES`（id: 話し手, 台詞）。話し手の声は `VOICE`（声・速さ・高さ）、読みの直しは `READ`（字幕はそのまま、声だけ読みを変える。英字・記号・数字は読みを足す）
- 時刻は `timeline.py` に書く: `say(id, 時刻)`（合成の声）・`rsay(...)`（実写の中の実際の声）・`scene(...)`・`screen(...)`（スマホの画面）・`fx(...)`（効果音）・`cmd(...)`（腕の命令）・`slot(...)`（実写の枠）・`Cut(...)`（実写の切り方）

### 2. 声

```sh
.venv/bin/python voices.py          # voice/<id>.mp3 と voice/durations.json。台詞・声の設定を変えたものだけ作り直す
```

### 3. 時刻と腕の 3D

```sh
python3 timeline.py                 # timeline.json。長さ・場面の時刻・声の重なりを表示し、上限を超えると知らせる
python3 sim_run.py                  # sim/meta.json・frames.bin・meshes.bin。timeline.py を変えたら必ず
```

### 4. 実写（本人が撮った映像を入れるとき）

元の映像は `assets/real/` に置いてもらう（書き換えない）。撮り直しを頼むときは、手本の `SHOOTING.md` の形で撮影の手順書を書く。

1. 文字起こしして、使う所を選ぶ:
   ```sh
   ffmpeg -v error -y -i "assets/real/<名前>.mp4" -ar 16000 -ac 1 <scratchpad>/<名前>.wav
   whisper-cli -m ~/.cache/whisper-cpp/ggml-large-v3-turbo-q5_0.bin -l ja -f <scratchpad>/<名前>.wav -osrt -ojf -of <scratchpad>/<名前> -np
   ```
2. つなぎ目は声のない所にする。音の大きさを 10 ms ごとにはかって確かめる:
   ```sh
   ffmpeg -v error -ss <秒> -t <長さ> -i "assets/real/<名前>.mp4" -vn -af "aresample=48000,asetnsamples=480,astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-" -f null - | paste - -
   ```
3. 出来事の時刻（画面が明るくなった・赤い帯が出た など）は、その所の明るさ（や色）を 1 コマずつはかって見つける:
   ```sh
   ffmpeg -v error -ss <秒> -t <長さ> -i "assets/real/<名前>.mp4" -vf "crop=<幅>:<高さ>:<x>:<y>,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-" -an -f null - | paste - -
   ```
   （赤さは `VAVG`、色の濃さは `SATAVG`）
4. `timeline.py` に書く: 元の映像の名前は `CLIP`、切り取りは `CROP`（カメラは同じ位置なので、**どの映像にも同じ四角**。まわりのモニター・窓・机の上の物・カメラの名前の透かしが入らないようにする）、切り方は `Cut(枠, 置く時刻, [(映像, 入り, 出), ...], [つなぎの秒, ...], hold=止める秒)`。ぼかす所（相手の名前・番号）は `cut_real.py` の `BLUR`
5. 作る:
   ```sh
   python3 timeline.py && python3 sim_run.py
   python3 cut_real.py                 # gen/real/<枠>.mp4（960×720・音なし）と .wav（枠の頭が 0 秒）
   ```
6. 使った所・つなぎ目・出来事の時刻を `REPLACE.md` に書く（あとで直せるように）

### 5. 画面（`index.html`）を確かめる

```sh
python3 render.py --test 6,30,48    # 指定の秒だけ chk/test_<秒>.png（Read で見る）。実写の枠を使うときは、1 回目で gen/frames/ ができる
python3 render.py --draft --test 60 # 実写の枠の点線と「仮の絵」の印を出す
python3 chk/grid.py 6,30,48         # 何枚かを 1 枚に並べる（chk/grid.png）
```

### 6. 書き出し（重い。裏で動かす）

映像（音なし）を 6 つに分けて同時に書き出し、つなぐ。1 つ分のコマ数は「コマ数 ÷ 6」を切り上げた数（手本は 5374 コマで 896。下のコマンドが数える）。

```sh
python3 render.py --test 62         # 先に 1 回（実写の 1 コマずつの絵を作る）
NF=$(python3 -c "import json;t=json.load(open('timeline.json'));print(round(t['duration']*t['fps']))"); P=$(( (NF + 5) / 6 ))
for k in 0 1 2 3 4 5; do python3 render.py --start $((k * P)) $( [ $k -lt 5 ] && echo --end $(((k + 1) * P)) ) --port $((8821 + k)) --out part_$((k + 1)).mp4 > part_$((k + 1)).log 2>&1 & done; wait
for k in 1 2 3 4 5 6; do echo "file 'part_$k.mp4'"; done > concat.txt
ffmpeg -v error -y -f concat -safe 0 -i concat.txt -c copy silent.mp4
```

### 7. 音を重ねる（-16 LUFS）

```sh
python3 audio.py silent.mp4 ../<出力>.mp4   # BGM・効果音・サーボの音・声・実写の音をまとめ、-16 LUFS にそろえて重ねる（映像は再エンコードしない）
python3 audio.py - x                        # 音だけ作り直す（audio_norm.wav）
python3 audio.py --song                     # 踊りの曲だけ（assets/dance_song.wav。実写で踊りを撮るときに流す）
```

BGM・効果音は自作の音だけ（`mix.html` と `hackathon.html` の音源）。本物の曲・効果音は使わない。

### 8. 確かめる

```sh
python3 ~/.claude/skills/promo-video/check.py ../<出力>.mp4 --src . --at <題名の秒>,<締めの秒> --out <scratchpad>/check
python3 chk/sync.py ../<出力>.mp4    # 台詞ごとのコマ（吹き出し・字幕が声と合うか）→ chk/sync_sheet.png
```

`check.py` は読むだけ。長さ・形式・音の大きさ（-16 LUFS ±1、ピーク -1 dBTP 以下）・2 秒以上の無音・10 秒ごとのコマの一覧（`sheet.png`）・指定の秒の原寸のコマ・台本と画面のファイルのボードの名前（あれば NG）と製品・会社の名前の一覧を出す。NG があれば終わりの番号は 1。

出てきた絵は Read で見て、次を確かめる:
- 題名・大きな文字の下（g・y・p の下）や端が切れていない。吹き出し・字幕が枠からはみ出していない
- 実写の枠に、余計な物（モニター・窓・ロゴ・カメラの透かし）が入っていない。相手の名前・番号がぼけている
- 右下の注意（再現映像・合成音声・人物は架空です、など）が場面に合っている
- 最後に、本人に通して聞いてもらう

## 直したときに、どこから作り直すか

| 直した所 | 作り直す順 |
| --- | --- |
| 台詞・読み・声 | `voices.py` → `timeline.py` → `sim_run.py` → （実写の時刻が動いたら `cut_real.py`）→ 書き出し → `audio.py` |
| 場面の時刻・腕の命令 | `timeline.py` → `sim_run.py` → 書き出し → `audio.py` |
| 実写の切り方 | `timeline.py` → `sim_run.py` → `cut_real.py` → 書き出し → `audio.py` |
| 見た目だけ（`index.html`・画像） | 書き出し → `audio.py` |
| 音だけ（`mix.html`・音の大きさ） | `audio.py silent.mp4 ../<出力>.mp4` |

どれも、最後に 8 の確認をする。

## 画像が足りないとき（ChatGPT に頼む）

Claude Code は画像を作れない。人物の似顔絵・背景のイラスト・アイコンは、本人に ChatGPT で作ってもらう。

1. 今の動画から、置き換えたい場面のコマと、絵の作りの見本を `handoff_refs/` に書き出す（`render.py --test <秒>` や `ffmpeg -ss`）
2. `~/.claude/skills/promo-video/handoff_imagegen_template.md` を写して、動画のフォルダに `HANDOFF_chatgpt_imagegen.md`（2 回目からは `_2`・`_3`）を書く。手本は `agentphone/HANDOFF_chatgpt_imagegen.md`（人物）と `_2.md`（背景・アイコン）
   - 前置き（動画のこと）・渡す見本・共通の決まり（色の番号・雰囲気・文字とロゴなし・実在に似せない・人物は架空）・作る物の表（ファイル名・大きさ・中身・使う場面）・そのまま貼れるプロンプト・置き場所・作り直しの基準・作らなくてよいもの
   - アイコンは 1 枚の表にまとめて頼み、先に 2〜3 個で作りを確かめてから残りを頼む。動画で使う大きさ（例 24 px）でも読めることを条件にする
3. 置いてもらったら（`assets/imagegen/`）、大きさ・透明・作りのそろい方を確かめ、表を切り分けて名前を付け、`SCENARIO.md` の「画像生成の絵」の表に使う所を書いてから組み込む
