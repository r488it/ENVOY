#!/usr/bin/env python3
"""
ENVOY の決め事を確かめる (Claude Code のフックと、手で動かす確認の両方で使う)。

確かめること:
- 鍵や API キーらしい文字・秘密のファイル (.env・鍵のファイルなど) が入っていない
- ボードの名前が入っていない (どのボードでも動くので、名前を出さない)
- コードのコメントに、過去の経緯 (以前は・〜に変えた・日付など) を書いていない
- 単体の試験が通る (コードを変えたときだけ)

使い方:
- pre-commit: PreToolUse (Bash) のフック。git commit のときだけ確かめ、決め事に合わなければ止める (終了コード 2)
- after-edit: PostToolUse (Edit・Write) のフック。書いた文字だけを確かめ、合わなければ Claude に知らせる
- check: 手で動かす。今の変更 (ステージ・未ステージ・新しいファイル) を確かめる。--no-tests で試験を省く
- 見つけた秘密の文字は、先頭だけ出して残りは伏せる
"""
from __future__ import annotations

import json
import math
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JBR = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# --- 鍵や API キー ---------------------------------------------------------

SECRET_PATTERNS = [
    ("API キー (sk-)", re.compile(r"sk-(?:ant-|proj-)?[A-Za-z0-9_-]{32,}")),
    ("Google の API キー", re.compile(r"AIza[0-9A-Za-z_-]{35}")),
    ("GitHub のトークン", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,})")),
    ("AWS のアクセスキー", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Slack のトークン", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}")),
    ("秘密鍵", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
    ("JWT", re.compile(r"\beyJ[A-Za-z0-9_-]{15,}\.eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}")),
]
# 名前が鍵らしい変数に、長くてでたらめな文字を入れている
ASSIGNED_SECRET = re.compile(
    r"(?i)(?:api[_-]?key|secret|password|passwd|token|bearer)[A-Za-z0-9_]*[\"']?\s*[:=]\s*[\"']([A-Za-z0-9_+/=.-]{24,})[\"']"
)
# 文字の並びの定数 (合言葉を作る文字の一覧など) は鍵ではない
ALPHABET = re.compile(r"ABCDEF|abcdef|0123456|23456789")
BEARER = re.compile(r"(?i)\bBearer\s+([A-Za-z0-9._~+/=-]{32,})")

SECRET_FILES = re.compile(
    r"(?:^|/)(?:\.env(?:\..+)?|secrets\.properties|local\.properties|google-services\.json|"
    r"id_(?:rsa|ed25519|ecdsa)|[^/]+\.(?:jks|keystore|pem|p12|pfx|key))$"
)


def entropy(s: str) -> float:
    n = len(s)
    return -sum(c / n * math.log2(c / n) for c in (s.count(ch) for ch in set(s)))


def mask(s: str) -> str:
    return s[:6] + "…" if len(s) > 6 else "…"


def find_secrets(text: str) -> list[str]:
    found = []
    for name, pat in SECRET_PATTERNS:
        for m in pat.finditer(text):
            found.append(f"{name} {mask(m.group(0))}")
    for pat in (ASSIGNED_SECRET, BEARER):
        for m in pat.finditer(text):
            v = m.group(1)
            if entropy(v) >= 3.5 and not ALPHABET.search(v) and not any(v in f for f in found):
                found.append(f"鍵らしい値 {mask(v)}")
    return found


# --- ボードの名前 ----------------------------------------------------------

BOARD = re.compile(r"(?i)\buno[\s_-]?q\b")

# --- コメントの中の過去の経緯 ----------------------------------------------

HISTORY = re.compile(
    r"以前|(?:^|[\s、。（(・])前は|元々|もともと|旧(?:来|版|仕様|名|実装|方式)|かつて|昔は|当初"
    r"|(?:これ|今)まで(?:と同じ|どおり|通り|は|の(?:版|作り|仕様|やり方))"
    r"|前の(?:版|バージョン|作り|仕様|実装|やり方)"
    r"|に(?:変えた|変更した|移した|置き換えた|改名した)(?!ら|とき|あと|後)|名前を変えた|改名|リネーム|廃止"
    r"|(?:今回|前回)の(?:変更|修正|対応)"
    r"|(?i:\b(?:formerly|renamed from|used to be|was changed|in the old version)\b)"
    r"|20\d\d[-/.年]\s?\d{1,2}"
)
SLASH_EXT = {".kt", ".kts", ".java", ".gradle", ".js", ".ts", ".c", ".cc", ".cpp", ".h", ".swift"}
HASH_EXT = {".py", ".sh", ".properties", ".pro", ".toml", ".yml", ".yaml", ".cfg"}
MARKUP_EXT = {".xml", ".html"}


def comment_of(path: str, line: str) -> str | None:
    """1 行のうちコメントの部分 (コメントが無ければ None。著作権の表示は除く)"""
    ext = Path(path).suffix.lower()
    s = line.strip()
    if "Copyright" in s:
        return None
    if ext in SLASH_EXT:
        if s.startswith(("//", "/*", "*")):
            return s
        m = re.search(r"(?<![:\\])//\s(.*)$", line)
        return m.group(1) if m else None
    if ext in HASH_EXT or Path(path).name == ".gitignore":
        if s.startswith("#") and not s.startswith("#!"):
            return s
        m = re.search(r"\s#\s(.*)$", line)
        return m.group(1) if m else None
    if ext in MARKUP_EXT and "<!--" in s:
        return s
    return None


# --- 変更を集める ----------------------------------------------------------

def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True).stdout


def added_lines(diff: str) -> list[tuple[str, int, str]]:
    """diff から足した行 (パス・行番号・中身) を取り出す"""
    out, path, no = [], None, 0
    for raw in diff.splitlines():
        if raw.startswith("+++ "):
            path = raw[6:] if raw.startswith("+++ b/") else None
        elif raw.startswith("@@"):
            m = re.search(r"\+(\d+)", raw)
            no = int(m.group(1)) if m else 0
        elif raw.startswith("+") and path:
            out.append((path, no, raw[1:]))
            no += 1
    return out


def untracked() -> list[str]:
    return [p for p in git("ls-files", "--others", "--exclude-standard").splitlines() if p]


def file_lines(path: str) -> list[tuple[str, int, str]]:
    f = ROOT / path
    try:
        if f.stat().st_size > 2_000_000:
            return []
        data = f.read_bytes()
    except OSError:
        return []
    if b"\0" in data:
        return []
    text = data.decode("utf-8", errors="replace")
    return [(path, i + 1, line) for i, line in enumerate(text.splitlines())]


def changes(include_unstaged: bool) -> tuple[list[str], list[tuple[str, int, str]]]:
    """コミットに入る (かもしれない) ファイルと、足した行"""
    opts = ["-U0", "--no-color", "--no-ext-diff"]
    paths = set(git("diff", "--cached", "--name-only", "--diff-filter=ACMR").splitlines())
    lines = added_lines(git("diff", "--cached", *opts))
    if include_unstaged:
        paths |= set(git("diff", "--name-only", "--diff-filter=ACMR").splitlines())
        lines += added_lines(git("diff", *opts))
        for p in untracked():
            paths.add(p)
            lines += file_lines(p)
    return sorted(p for p in paths if p), lines


# --- 確かめる --------------------------------------------------------------

def check_lines(lines: list[tuple[str, int, str]]) -> list[str]:
    problems, seen = [], set()
    for path, no, line in lines:
        where = f"{path}:{no}"
        for s in find_secrets(line):
            problems.append(f"鍵や API キーらしいもの: {where} ({s})")
        if BOARD.search(line):
            problems.append(f"ボードの名前: {where}")
        c = comment_of(path, line)
        if c:
            m = HISTORY.search(c)
            if m and (where, m.group(0)) not in seen:
                seen.add((where, m.group(0)))
                problems.append(f"コメントに過去の経緯: {where} (「{m.group(0).strip()}」: {c.strip()[:60]})")
    return problems


def check_files(paths: list[str]) -> list[str]:
    return [f"秘密のファイル: {p}" for p in paths if SECRET_FILES.search(p)]


def needs_tests(paths: list[str]) -> bool:
    return any(
        not p.endswith(".md")
        and (p.startswith(("envoymcp/", "demoapps/", "gradle/")) or p.endswith((".kts", "gradle.properties")))
        for p in paths
    )


def run_tests(paths: list[str]) -> list[str]:
    env = dict(os.environ)
    if Path(JBR).is_dir():
        env["JAVA_HOME"] = JBR
    tasks = [":envoymcp:testDebugUnitTest"]
    for app in ("puzzle", "mart", "music"):
        if any(p.startswith(f"demoapps/{app}/") for p in paths):
            tasks.append(f":demoapps:{app}:compileDebugKotlin")
    r = subprocess.run(
        ["./gradlew", "-q", "--console=plain", *tasks], cwd=ROOT, env=env, capture_output=True, text=True
    )
    if r.returncode == 0:
        return []
    failed = []
    for f in (ROOT / "envoymcp/build/test-results/testDebugUnitTest").glob("*.xml"):
        try:
            for case in ET.parse(f).getroot().iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failed.append(f"{case.get('classname', '').rsplit('.', 1)[-1]}.{case.get('name')}")
        except ET.ParseError:
            pass
    if failed:
        return [f"単体の試験が通らない: {name}" for name in failed[:15]]
    tail = "\n".join((r.stderr or r.stdout).strip().splitlines()[-15:])
    return [f"ビルドか試験が失敗した ({' '.join(tasks)}):\n{tail}"]


def report(problems: list[str], head: str) -> None:
    print(head, file=sys.stderr)
    for p in problems:
        print(f"- {p}", file=sys.stderr)


# --- フックの入口 ----------------------------------------------------------

COMMIT = re.compile(r"\bgit\b((?:\s+-[Cc]\s+\S+)*)\s+commit\b")


def commit_repo(command: str, cwd: str) -> Path | None:
    """git commit の対象のリポジトリ (コミットでなければ None)"""
    m = COMMIT.search(command)
    if not m:
        return None
    base = Path(cwd or ROOT)
    target = re.findall(r"-C\s+(\S+)", m.group(1))
    for t in target:
        base = base / os.path.expanduser(t.strip("'\""))
    try:
        top = subprocess.run(["git", "-C", str(base), "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True).stdout.strip()
    except OSError:
        return None
    return Path(top).resolve() if top else None


def pre_commit(event: dict) -> int:
    if event.get("tool_name") != "Bash":
        return 0
    command = (event.get("tool_input") or {}).get("command", "")
    if commit_repo(command, event.get("cwd", "")) != ROOT:
        return 0
    include_unstaged = bool(re.search(r"\bgit\b[^;&|]*\badd\b|\bcommit\b[^;&|]*\s-(?:a|[A-Za-z]*a[A-Za-z]*)\b|--all\b", command))
    paths, lines = changes(include_unstaged)
    problems = check_files(paths) + check_lines(lines)
    for s in find_secrets(command):
        problems.append(f"コミットのコマンドやメッセージに鍵らしいもの ({s})")
    if BOARD.search(command):
        problems.append("コミットのメッセージにボードの名前")
    if not problems and needs_tests(paths):
        problems += run_tests(paths)
    if problems:
        report(problems, "ENVOY の決め事に合わないので、コミットを止めました。直してから、もう一度コミットしてください。")
        return 2
    return 0


def after_edit(event: dict) -> int:
    ti = event.get("tool_input") or {}
    path = ti.get("file_path", "")
    try:
        rel = str(Path(path).resolve().relative_to(ROOT))
    except ValueError:
        return 0
    if rel.startswith(".claude/hooks/"):
        return 0
    texts = [ti.get("new_string"), ti.get("content")] + [e.get("new_string") for e in ti.get("edits") or []]
    lines = []
    for t in texts:
        if isinstance(t, str):
            lines += [(rel, i + 1, line) for i, line in enumerate(t.splitlines())]
    problems = check_files([rel]) + check_lines(lines)
    if problems:
        report(problems, "ENVOY の決め事に合わない所があります (行の番号は、書いた文字の中での番号)。直してください。")
        return 2
    return 0


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    if mode in ("pre-commit", "after-edit"):
        try:
            event = json.load(sys.stdin)
        except json.JSONDecodeError:
            return 0
        return pre_commit(event) if mode == "pre-commit" else after_edit(event)
    paths, lines = changes(include_unstaged=True)
    problems = check_files(paths) + check_lines(lines)
    if "--no-tests" not in sys.argv and needs_tests(paths):
        problems += run_tests(paths)
    if problems:
        report(problems, "ENVOY の決め事に合わない所があります:")
        return 1
    print(f"決め事に合っています ({len(paths)} ファイル)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
