// ENVOY MCP の中で動かす、外からつなぐための中継 (ngrok-go、MIT)。
// ngrok の決まったドメイン (https://<ドメイン>) に届いたものを、このスマホの中の MCP サーバー (http://127.0.0.1:<ポート>) へ取り次ぐ。
// アプリ (mcp/PublicTunnel.kt) が子プロセスとして動かし、出した行を読んで様子を知る。
//
//   - 認証トークンは環境変数 NGROK_AUTHTOKEN からだけ読む (引数・ファイルには書かない)。出す行には出さない (伏せる)
//   - ドメインとポートは、引数 (-domain・-port) か環境変数 (NGROK_DOMAIN・MCP_PORT) で受け取る
//   - Host ヘッダーは届いたまま渡す (書き換えない。ngrok-go の取り次ぎが元の Host を残す)
//   - -watch-stdin のときは、標準入力が閉じたら (アプリが終わったら) 終わる
//   - 出す行 (1 行に 1 つ。アプリが読む):
//     READY https://<ドメイン>          つながった (つなぎ直せたときも出す)
//     RETRY <理由>                      ngrok との接続が切れた・つながらない。つなぎ直している
//     ERROR <理由>                      続けられない (このあと終わる)
//     REQ <メソッド> <パス> <状態> <時間>  取り次いだ (パスの問い合わせの部分と、合言葉らしい部分は伏せる)
//     NOUPSTREAM <理由>                 MCP サーバーに届かない
//     LOG <中身>                        ngrok-go の注意 (WARN 以上)
//   - 終了コード: 0 止めた、1 続けられない、2 設定が足りない、3 認証トークンかアカウントの問題
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"

	"golang.ngrok.com/ngrok/v2"
)

// ビルドのときに入れる (-ldflags "-X main.version=...")
var version = "dev"

const (
	exitStopped = 0
	exitFailed  = 1
	exitUsage   = 2
	exitAuth    = 3
)

func main() { os.Exit(run()) }

func run() int {
	domainArg := flag.String("domain", os.Getenv("NGROK_DOMAIN"), "ngrok の決まったドメイン (例: xxx.ngrok-free.app)")
	portArg := flag.Int("port", envInt("MCP_PORT", 8765), "取り次ぐ先 (127.0.0.1) のポート")
	watchStdin := flag.Bool("watch-stdin", false, "標準入力が閉じたら終わる")
	flag.Parse()

	// トークンは読んだら環境変数から消す (このあと動かすものに渡さない)
	token := strings.TrimSpace(os.Getenv("NGROK_AUTHTOKEN"))
	_ = os.Unsetenv("NGROK_AUTHTOKEN")
	out := &printer{w: os.Stdout, secret: token}

	domain, ok := cleanDomain(*domainArg)
	switch {
	case token == "":
		out.status("ERROR", "NO_TOKEN")
		return exitUsage
	case !ok:
		out.status("ERROR", "BAD_DOMAIN")
		return exitUsage
	case *portArg < 1 || *portArg > 65535:
		out.status("ERROR", "BAD_PORT")
		return exitUsage
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	go func() { <-sig; cancel() }()
	if *watchStdin {
		go func() { _, _ = io.Copy(io.Discard, os.Stdin); cancel() }()
	}

	// ngrok-go の記録は WARN 以上だけを、伏せてから LOG の行で出す (切れた・つなぎ直す は RETRY の行で出すので省く)
	logger := slog.New(skipHandler{slog.NewTextHandler(out, &slog.HandlerOptions{
		Level: slog.LevelWarn,
		ReplaceAttr: func(groups []string, a slog.Attr) slog.Attr {
			if len(groups) == 0 && a.Key == slog.TimeKey {
				return slog.Attr{}
			}
			return a
		},
	})})
	slog.SetDefault(logger)

	// 続けられない失敗 (認証トークンが違う など)。ngrok-go は待って試し直し続けるので、ここで止めてアプリに任せる
	var fatalMu sync.Mutex
	var fatal error
	fail := func(err error) {
		fatalMu.Lock()
		if fatal == nil {
			fatal = err
		}
		fatalMu.Unlock()
		cancel()
	}
	fatalErr := func() error { fatalMu.Lock(); defer fatalMu.Unlock(); return fatal }

	var readyURL atomic.Value
	readyURL.Store("")
	var down atomic.Bool
	agent, err := ngrok.NewAgent(
		ngrok.WithAuthtoken(token),
		ngrok.WithClientInfo("envoy-mcp", version),
		ngrok.WithLogger(logger),
		ngrok.WithEventHandler(func(e ngrok.Event) {
			switch ev := e.(type) {
			case *ngrok.EventAgentConnectSucceeded:
				// つなぎ直せた (入口もつなぎ直してある)。最初につながったときは、入口ができてから READY を出す
				if down.Swap(false) {
					if u, _ := readyURL.Load().(string); u != "" {
						out.status("READY", u)
					}
				}
			case *ngrok.EventAgentDisconnected:
				if ev.Error == nil || ctx.Err() != nil || errors.Is(ev.Error, context.Canceled) {
					return
				}
				if isAccountError(ev.Error) {
					fail(ev.Error)
					return
				}
				down.Store(true)
				out.status("RETRY", describe(ev.Error))
			case *ngrok.EventHTTPRequestComplete:
				out.status("REQ", fmt.Sprintf("%s %s %d %dms", ev.Method, cleanPath(ev.Path), ev.StatusCode, ev.Duration.Milliseconds()))
			case *ngrok.EventUpstreamDialComplete:
				if ev.Err != nil {
					out.status("NOUPSTREAM", oneLine(ev.Err.Error()))
				}
			}
		}),
	)
	if err != nil {
		out.status("ERROR", describe(err))
		return exitFailed
	}

	upstream := "http://127.0.0.1:" + strconv.Itoa(*portArg)
	fwd, err := agent.Forward(ctx, ngrok.WithUpstream(upstream), ngrok.WithURL("https://"+domain))
	if err != nil {
		if f := fatalErr(); f != nil {
			out.status("ERROR", describe(f))
			return exitAuth
		}
		if ctx.Err() != nil {
			return exitStopped
		}
		out.status("ERROR", describe(err))
		_ = agent.Disconnect()
		return exitFailed
	}
	u := fwd.URL().String()
	readyURL.Store(u)
	out.status("READY", u)

	select {
	case <-ctx.Done():
	case <-fwd.Done():
	}
	// 入口を閉じ、ngrok との接続を切る (次に動かしたとき、すぐ同じドメインを使えるように)
	_ = agent.Disconnect()
	if f := fatalErr(); f != nil {
		out.status("ERROR", describe(f))
		return exitAuth
	}
	if ctx.Err() == nil {
		out.status("ERROR", "ENDPOINT_CLOSED")
		return exitFailed
	}
	return exitStopped
}

// --- 出す行 -------------------------------------------------------------

// 標準出力に 1 行ずつ出す。認証トークンらしいものは、出す前に伏せる
type printer struct {
	mu     sync.Mutex
	w      io.Writer
	secret string
}

func (p *printer) status(kind, text string) { p.line(kind + " " + text) }

func (p *printer) line(s string) {
	s = p.mask(oneLine(s))
	p.mu.Lock()
	defer p.mu.Unlock()
	_, _ = io.WriteString(p.w, s+"\n")
}

// slog の出力先 (1 件ずつ届く。LOG を付けて出す)
func (p *printer) Write(b []byte) (int, error) {
	for _, s := range strings.Split(strings.TrimRight(string(b), "\n"), "\n") {
		p.line("LOG " + s)
	}
	return len(b), nil
}

// ngrok の認証トークンの形 (数字で始まり、途中に _ がひとつ)
var tokenLike = regexp.MustCompile(`\b[0-9][A-Za-z0-9]{20,}_[A-Za-z0-9]{10,}\b`)

func (p *printer) mask(s string) string {
	if p.secret != "" {
		s = strings.ReplaceAll(s, p.secret, "…")
	}
	return tokenLike.ReplaceAllString(s, "…")
}

// 改行をつめて 1 行にし、長すぎれば切る
func oneLine(s string) string {
	s = strings.Join(strings.Fields(s), " ")
	if r := []rune(s); len(r) > maxLine {
		s = string(r[:maxLine]) + "…"
	}
	return s
}

const maxLine = 400

// skipHandler は、RETRY の行と同じことを言う ngrok-go の記録を省く
type skipHandler struct{ slog.Handler }

var skipped = map[string]bool{
	"failed to reconnect session":             true,
	"session closed, starting reconnect loop": true,
}

func (h skipHandler) Handle(ctx context.Context, r slog.Record) error {
	if skipped[r.Message] {
		return nil
	}
	return h.Handler.Handle(ctx, r)
}

func (h skipHandler) WithAttrs(a []slog.Attr) slog.Handler {
	return skipHandler{h.Handler.WithAttrs(a)}
}
func (h skipHandler) WithGroup(n string) slog.Handler { return skipHandler{h.Handler.WithGroup(n)} }

// --- 失敗の読み取り -----------------------------------------------------

var (
	errCode  = regexp.MustCompile(`ERR_NGROK_\d+`)
	docsLink = regexp.MustCompile(`https://ngrok\.com/docs/errors/\S*`)
)

// 認証トークンかアカウントの問題 (待って試し直しても直らない)
var accountCodes = map[string]bool{
	"ERR_NGROK_105":  true, // 認証トークンの形が違う
	"ERR_NGROK_107":  true, // 認証トークンが使えない
	"ERR_NGROK_108":  true, // 同じアカウントで、ほかの所でも動いている
	"ERR_NGROK_4018": true, // 認証トークンが無い
}

func isAccountError(err error) bool {
	msg := err.Error()
	return accountCodes[errCode.FindString(msg)] || strings.HasPrefix(msg, "authentication failed")
}

// アプリが読む理由: ngrok のエラーの番号があれば先に置き、案内の URL は落として 1 行にする
func describe(err error) string {
	msg := docsLink.ReplaceAllString(err.Error(), "")
	if c := errCode.FindString(msg); c != "" {
		msg = c + " " + strings.ReplaceAll(msg, c, "")
	}
	return oneLine(msg)
}

// --- 形を整える ---------------------------------------------------------

var hostname = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$`)

// ドメインだけにする (https:// や / の後ろは落とす)。形が違えば false
func cleanDomain(s string) (string, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	s = strings.TrimPrefix(strings.TrimPrefix(s, "https://"), "http://")
	if i := strings.IndexAny(s, "/?#"); i >= 0 {
		s = s[:i]
	}
	return s, len(s) <= 253 && hostname.MatchString(s)
}

// 記録に出すパス: 問い合わせの部分 (? の後ろ) を落とし、合言葉らしい部分を伏せる
func cleanPath(p string) string {
	if i := strings.IndexAny(p, "?#"); i >= 0 {
		p = p[:i]
	}
	segs := strings.Split(p, "/")
	for i, s := range segs {
		if looksSecret(s) {
			segs[i] = "…"
		}
	}
	p = strings.Join(segs, "/")
	if len(p) > maxPath {
		p = p[:maxPath] + "…"
	}
	if p == "" {
		p = "/"
	}
	return p
}

const maxPath = 120

// 合言葉らしい部分: 長くて、言葉をつないだ形 (小文字を - や . でつないだもの。oauth-protected-resource など) ではない
func looksSecret(s string) bool {
	if len(s) < 16 {
		return false
	}
	joined := false
	for _, c := range s {
		switch {
		case c == '-' || c == '.':
			joined = true
		case c < 'a' || c > 'z':
			return true
		}
	}
	return !joined
}

func envInt(k string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(k)); err == nil {
		return v
	}
	return def
}
