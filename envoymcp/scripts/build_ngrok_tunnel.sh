#!/bin/bash
# スマホの中で動かす、外からつなぐための中継 (tunnel/ngrok。ngrok-go を使う、MIT) を Android 用にビルドし、ENVOY MCP に入れる準備をする。
# - 使うライブラリの版は tunnel/ngrok/go.mod と go.sum で固定する (ビルドの前に go mod verify で中身を確かめる)
# - Go は go.mod の toolchain (go1.27.3) 以上で作る (それより古い Go で作ると、ngrok-go が証明書の注意を出す)。
#   手元の Go が古ければ、その版を Go が落として使う (GOTOOLCHAIN=auto)
# - Android の名前解決 (bionic) を使うため、NDK の clang で cgo を有効にしてビルドする
# - 出力: src/main/jniLibs/arm64-v8a/libngroktunnel.so (アプリの nativeLibraryDir に展開され、そこから実行できる)
#         src/main/assets/licenses/ngrok-go/LICENSES.txt (同梱するときの表示。中に入るライブラリと Go のライセンス)
#   どちらも Git には入れない (envoymcp/.gitignore)。ビルドし直したら ./gradlew :envoymcp:installDebug で入れ直す
#   要るもの: Go 1.21 以上 (PATH か ~/.local/opt/go1.27.2)、Android NDK (sdk.dir の ndk/ の下、または ANDROID_NDK_HOME)
set -euo pipefail
cd "$(dirname "$0")/.."

API=31   # minSdk と同じ
SRC=tunnel/ngrok

if ! command -v go >/dev/null; then
  [ -x "$HOME/.local/opt/go1.27.2/bin/go" ] && export PATH="$HOME/.local/opt/go1.27.2/bin:$PATH"
fi
command -v go >/dev/null || { echo "Go がありません (1.21 以上)" >&2; exit 1; }
export GOTOOLCHAIN=auto GOFLAGS=-mod=readonly

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK=$(sed -n 's/^sdk\.dir=//p' ../local.properties 2>/dev/null || true)
  SDK=${SDK:-$HOME/Library/Android/sdk}
  ANDROID_NDK_HOME=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)
fi
HOST=$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64   # NDK の prebuilt は Apple シリコンでも darwin-x86_64
CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android${API}-clang"
[ -x "$CC" ] || { echo "NDK の clang がありません: $CC" >&2; exit 1; }

OUT=src/main/jniLibs/arm64-v8a/libngroktunnel.so
LIC=src/main/assets/licenses/ngrok-go
mkdir -p "$(dirname "$OUT")" "$LIC"
(
  cd "$SRC"
  go mod verify >/dev/null
  NGROK=$(go list -m -f '{{.Version}}' golang.ngrok.com/ngrok/v2)
  CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC" go build -trimpath -buildvcs=false \
    -ldflags "-s -w -X main.version=ngrok-go-$NGROK" -o "$OLDPWD/$OUT" .

  # ライセンスの表示: 中に入るライブラリ (版つき) と、Go の標準ライブラリ
  {
    echo "ENVOY MCP の中の、外からつなぐ中継 (libngroktunnel.so) に入っているもの"
    GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -deps -f '{{with .Module}}{{if not .Main}}{{.Path}} {{.Version}} {{.Dir}}{{end}}{{end}}' . |
      sort -u | while read -r path ver dir; do
        printf '\n==== %s %s ====\n\n' "$path" "$ver"
        for f in "$dir"/LICENSE* "$dir"/NOTICE* "$dir"/PATENTS*; do [ -f "$f" ] && cat "$f"; done
      done
    printf '\n==== Go %s (標準ライブラリ) ====\n\n' "$(go env GOVERSION)"
    cat "$(go env GOROOT)/LICENSE"
  } > "$OLDPWD/$LIC/LICENSES.txt"
)
BUILT=$(go version "$OUT" | awk '{print $2}')
echo "できました: envoymcp/$OUT ($(wc -c <"$OUT" | awk '{printf "%.1f MB", $1 / 1048576}')) ngrok-go $(cd "$SRC" && go list -m -f '{{.Version}}' golang.ngrok.com/ngrok/v2)・$BUILT"
