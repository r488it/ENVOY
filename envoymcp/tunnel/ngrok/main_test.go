package main

import (
	"bytes"
	"errors"
	"strings"
	"testing"
)

// 試験用の作りものの認証トークン (本物ではない)
const fake = "2abcdefghijklmnopqrstuvwxyzA_abcdefghijklmnopqrstu"

func TestTokenIsNeverPrinted(t *testing.T) {
	var b bytes.Buffer
	p := &printer{w: &b, secret: fake}
	p.status("ERROR", "authentication failed: bad token.\nYour authtoken: "+fake)
	_, _ = p.Write([]byte("level=WARN msg=x token=" + fake + "\n"))
	// 知らないトークンでも、ngrok の形なら伏せる
	p.status("RETRY", "token 1abcdefghijklmnopqrstuvwxyzB_ABCDEFGHIJKLMN")
	out := b.String()
	if strings.Contains(out, "abcdefghijklmnopqrstuvwxyz") {
		t.Fatalf("トークンが出ています: %q", out)
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 3 || !strings.HasPrefix(lines[0], "ERROR ") || !strings.HasPrefix(lines[1], "LOG ") || !strings.HasPrefix(lines[2], "RETRY ") {
		t.Fatalf("1 件 1 行になっていません: %q", lines)
	}
}

func TestDescribePutsCodeFirst(t *testing.T) {
	err := errors.New("authentication failed: The authtoken is invalid.\n\nERR_NGROK_107\n\nhttps://ngrok.com/docs/errors/err_ngrok_107")
	got := describe(err)
	if got != "ERR_NGROK_107 authentication failed: The authtoken is invalid." {
		t.Fatalf("got %q", got)
	}
	if !isAccountError(err) {
		t.Fatal("認証の失敗を、続けられない失敗として読めません")
	}
	if isAccountError(errors.New("failed to dial ngrok server with address \"connect.ngrok-agent.com:443\": dial tcp: lookup connect.ngrok-agent.com: no such host")) {
		t.Fatal("ネットの失敗を、続けられない失敗と読んでいます")
	}
}

func TestCleanPath(t *testing.T) {
	cases := map[string]string{
		"/mcp":                                    "/mcp",
		"/mcp?session=abc":                        "/mcp",
		"/mcp/AbCdEfGh23456789XyZaBcDeFgH23456":   "/mcp/…",
		"/.well-known/oauth-protected-resource":   "/.well-known/oauth-protected-resource",
		"/.well-known/oauth-authorization-server": "/.well-known/oauth-authorization-server",
		"/authorize?code=secret&state=x":          "/authorize",
		"/x/qwertyuiopasdfghjkl":                  "/x/…",
		"":                                        "/",
	}
	for in, want := range cases {
		if got := cleanPath(in); got != want {
			t.Errorf("cleanPath(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestCleanDomain(t *testing.T) {
	ok := map[string]string{
		"example-name.ngrok-free.app":             "example-name.ngrok-free.app",
		" https://Example-Name.ngrok-free.app/ ":  "example-name.ngrok-free.app",
		"https://example-name.ngrok-free.app/mcp": "example-name.ngrok-free.app",
	}
	for in, want := range ok {
		if got, good := cleanDomain(in); !good || got != want {
			t.Errorf("cleanDomain(%q) = %q, %v", in, got, good)
		}
	}
	for _, bad := range []string{"", "localhost", "a b.ngrok.app", "-x.ngrok.app", "x..app"} {
		if _, good := cleanDomain(bad); good {
			t.Errorf("cleanDomain(%q) は通らないはず", bad)
		}
	}
}
