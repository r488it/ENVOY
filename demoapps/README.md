# 撮影・試験用の架空のアプリ

ENVOY MCP の動きを、実在のアプリの画面やロゴを使わずに見せる・試すための、架空のアプリです。どれも通信はしません。

| アプリ | パッケージ | 中身 | 見せられること |
| --- | --- | --- | --- |
| ぷちぷちパズル | `com.rabi.demo.puzzle` | パズルの画面の上に、全画面の広告が出る。右上の小さな ×（読み上げの名前「広告を閉じる」）でだけ閉じ、戻るでは消えない。「つぎのステージへ」でまた広告が出る | 押す場所を指して教える（`point_at`・`wait_for_user`） |
| まいにちマート | `com.rabi.demo.mart` | いつもの品物の一覧 → 注文の確認 →「注文を確定する」→ 受付。注文はどこにも届かない | 確定の前の、本人の承認 |
| MyTube Music | `com.rabi.demo.mytube` | 自作の曲「リゲルのダンス」を流す。再生中の曲として、曲名とアーティストを Android に知らせる | リゲルの音楽の画面と、腕の踊り（スマホが出す音から拍を取る） |

## 入れ方

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :demoapps:puzzle:installDebug :demoapps:mart:installDebug :demoapps:music:installDebug
```
