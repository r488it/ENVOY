# ENVOY MCP

![リゲル](docs/images/rigel_hero.jpg)

**話しかけるだけで、AI がスマホを操作する Android アプリ。**

ENVOY MCP は、スマホを操作する道具を **MCP（Model Context Protocol）** で出すサーバーです。MCP に対応した AI エージェントなら、どれでもつないで使えます。AI エージェントが考えて会話し、ENVOY MCP がスマホの上で手を動かします（アプリを開く・画面を読む・押す・入力する・電話をかける、など）。

このリポジトリでは、一例として **ChatGPT のアプリ（音声モード）と ENVOY MCP をつないでいます**。ChatGPT に声で頼むと、ChatGPT が ENVOY MCP の道具を使ってスマホを操作します。

ホーム画面には、ガラスの球のアバター「**リゲル**」だけがいます。リゲルをタップすると、ChatGPT の音声モードが開きます。

| 1. 話しかける | 2. AI がスマホを操作する | 3. 確定の前に、本人が確かめる |
| :---: | :---: | :---: |
| ![音声モードの間は、画面いっぱいにリゲルが出る](docs/images/rigel.jpg) | ![操作している間は、小さなリゲルが「画面を操作しています」と出す。タップで止められる](docs/images/shop.jpg) | ![注文などを確定する前に、内容を見せて指紋で承認する](docs/images/approval_order.jpg) |
| 音声モードの間は、画面いっぱいにリゲル | 操作の間は小さなリゲル（タップで止まる） | 内容を見せて、指紋で承認 |

## できること

| まとまり | 中身 |
| --- | --- |
| スマホの操作 | アプリやショートカットを開く・画面を読む・押す・文字を入れる・動かす・戻る・ホームに戻る。画面の画像を読んで、押せるものを見つける |
| 手順を覚える | うまくいった操作の手順を「部品」として覚え、次からはすぐに実行する |
| 写真 | 写真を言葉で探して（端末の中の EmbeddingGemma 2）、画面に並べて見せる・大きく見せる・アプリで共有する |
| 電話 | 電話をかける。**リゲルが代わりに電話をかけて用件を伝える**・**かかってきた電話に代わりに出る**（AgentARM の UNO Q と組む）。代行中のやり取りを画面に文字で出し、通話の記録と録音を残す |
| 音声モード（ChatGPT と組むとき） | 開いたときに、最初の一言を自動で送る。「ばいばい」で音声モードを終える。音声モードの間は、画面いっぱいにリゲルを出す |
| リゲルの表情 | 会話に合わせて、リゲルがうれしい・驚く・照れるなどの表情を見せる |
| AgentARM（スマホを載せる腕） | 縦・横に向ける、利用者の方を向く・追う、追いながら撮る、音楽に合わせて踊る、表情に合わせて動く、止める |
| 音楽 | 音楽が流れたら、リゲルの音楽の画面を出す（歌詞・スペクトラム） |
| モニター（PC モード） | スマホをモニターにつなぐと、モニターに全画面のリゲルを出し、モニター側でアプリを操作する |

| リゲルが代わりに電話をかける | かかってきた電話に代わりに出る |
| --- | --- |
| ![リゲルが代わりに電話をかけ、AI だと名乗って用件を伝える](docs/images/demo_call.jpg) | ![リゲルが代わりに電話に出て、用件を聞く](docs/images/demo_incoming.jpg) |

| 頼まれたことを、道具を呼んでこなす（AgentARM と組んで踊る） |
| --- |
| ![「料理の動画を出して」を、open_app・type_text・read_screen・tap の道具でこなし、音楽に合わせて踊る](docs/images/demo_operate.jpg) |

| AgentARM（スマホを載せる腕） | 音楽に合わせて踊る |
| :---: | :---: |
| ![スマホを載せる 3 軸の腕。利用者の方へ向きを変える](docs/images/agentarm.jpg) | ![音楽が流れると、リゲルの音楽の画面を出して踊る](docs/images/agentarm_dance.jpg) |

画像は再現です（腕はシミュレーション、スマホの画面は再現）。

## しくみ

```
 AI エージェント（例: ChatGPT のアプリの音声モード）
   │ MCP
   ▼
 ENVOY MCP の MCP サーバー（スマホの中。合言葉で守る）
   ├ 端末の操作（ユーザー補助、または ADB）
   ├ 写真の検索（端末の中のモデル）
   ├ AgentARM（BLE で UNO Q の腕へ）
   └ 電話の代役（Wi-Fi で UNO Q の電話の代役へ）
```

- 道具は MCP の決まりどおりに出しているので、AI エージェントを選びません。
- 端末の操作は、ユーザー補助（設定でオン）か、ADB（ワイヤレスデバッグで自分の端末につなぐ）を選べます。
- AgentARM の腕の制御と電話の代役（通話の相手と話す部分）は、UNO Q の上で動くプログラムが受け持ちます。UNO Q のプログラムは別のリポジトリです。腕と電話の代役を使わなければ、スマホだけで動きます。

## 安心のしくみ

- **取り消せない操作は、本人が確かめる**: 送信・発信など、取り消せない操作の前には、内容を見せて指紋などで本人が承認します。承認は、その内容に結びついた 1 回限りのものです。
- **いつでも止められる**: 操作中に出る小さなリゲルをタップすると、今の操作を止め、しばらく道具を受け付けません。
- **電話の代役は AI だと名乗る**: リゲルは、電話の相手に最初に AI だと名乗ります。かける前には本人の承認が要ります。
- **個人の情報を記録に出さない**: 電話番号やメールアドレスは記録に出しません（電話番号は末尾 4 桁まで）。
- **画面の画像は設定しだい**: 画面の画像を AI に読ませるかは、設定で決めます。

## 道具の一覧

| まとまり | 道具 |
| --- | --- |
| 様子 | `get_state` |
| 画面 | `read_screen` `get_screenshot` `look_at_screen` `share_screenshot` |
| アプリ | `list_apps` `list_shortcuts` `open_app` `open_shortcut` `open_panel` |
| 操作 | `tap` `type_text` `scroll` `back` `home` `press_key` `wait` |
| 部品 | `list_parts` `run_part` `save_part` `delete_part` |
| 写真 | `search_photos` `show_photo` `share_photo` |
| 電話 | `call_phone` `rigel_call` `rigel_call_status` `rigel_call_hangup` `rigel_incoming` |
| 音声モード・表情 | `end_voice_chat` `rigel_expression` |
| AgentARM | `arm_portrait` `arm_landscape` `arm_look_at_me` `arm_follow_me` `arm_stop_following` `arm_record_video` `arm_switch_camera` `arm_stop_recording` `arm_move` `arm_motion` `arm_dance` `arm_stop` |

## 必要なもの

- Android 12 以降（`minSdk 31`）。Pixel 10 で動きを確かめています
- MCP に対応した AI エージェント（例: ChatGPT のアプリ）
- 端末の操作に、ユーザー補助（または ADB のワイヤレスデバッグ）
- 腕と電話の代役を使うときは、AgentARM と UNO Q（別のリポジトリ）

## ビルド

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :envoymcp:assembleDebug        # 作る
./gradlew :envoymcp:installDebug         # つないだ端末に入れる
./gradlew :envoymcp:testDebugUnitTest    # 単体の試験
```

## はじめの設定

1. ENVOY MCP をホームアプリに選ぶ（リゲルのタップで音声モードを開くため）
2. 設定の「MCP サーバー」をオンにする
3. 使う AI エージェントに、ENVOY MCP の MCP サーバーを登録する（例: ChatGPT のコネクター）
4. 設定の「ユーザー補助」を開き、ENVOY MCP をオンにする（または ADB を使う）
5. 腕を使うときは、設定の「AgentARM」をオンにして、UNO Q とつなぐ

## フォルダー

| 場所 | 中身 |
| --- | --- |
| `envoymcp/src/main/java/com/rabi/envoymcp/` | アプリの入口（ホーム・アシスタント・MCP のサービス・通知の受け口）と、部品のつなぎ役・設定の値 |
| `…/envoymcp/mcp/` | MCP サーバーと道具（道具の一覧は `McpTools.kt`、中身は種類ごとのファイル） |
| `…/envoymcp/a11y/` | ユーザー補助による端末の操作 |
| `…/envoymcp/device/` | 端末の操作の方式（ADB・ユーザー補助の切り替え）・アプリのショートカット |
| `…/envoymcp/device/adb/` | 自分の端末の adbd への接続とペアリング |
| `…/envoymcp/arm/` | AgentARM との連携（BLE・顔の追跡・撮りながら追う・UNO Q の Wi-Fi） |
| `…/envoymcp/call/` | 電話の代役・着信への応答・LINE の通話・通話の記録・連絡先 |
| `…/envoymcp/music/` | 音楽の画面・歌詞・再生中の曲・拍の取り方 |
| `…/envoymcp/voice/` | 音声モードに重ねるリゲル |
| `…/envoymcp/monitor/` | モニター（PC モード）のリゲル |
| `…/envoymcp/media/` | 写真の検索と表示 |
| `…/envoymcp/ui/` | 設定・通話の記録・承認・操作中のオーバーレイ・リゲルの描画（`avatar/`） |
| `…/envoymcp/parts/`・`safety/`・`observe/` | 部品・確定の承認・画面の読み取り |
| `…/envoymcp/core/`・`auth/` | ログ・文字の整え・暗号化した保存・表情などの状態、ChatGPT でサインイン |
| `…/launcher/adb/` | ペアリングの通知の受け口（通知の宛先なので、クラスの名前を変えない） |
| `…/com/google/ai/edge/gallery/` | 写真の検索に使う Google AI Edge Gallery のコード（Apache 2.0） |
| `envoymcp/src/main/assets/` | 部品の見本・同梱したもののライセンス |
| `docs/images/` | この README の画像 |

## 同梱しているもの

- Google AI Edge Gallery の一部（Apache 2.0）: `envoymcp/src/main/assets/licenses/edge-gallery/`
