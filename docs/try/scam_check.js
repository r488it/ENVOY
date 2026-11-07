// 詐欺の見分け (アプリの ScamCheck と同じ決まり。決まりは scam_data.js から読む)。ブラウザの中だけで動き、どこにも送らない。
// - 比べる前に文字をそろえる: 全角・半角 (NFKC)、英字は小文字、カタカナはひらがなに。相手の言葉は空白と読点も除く
// - 強さ: 「お金の話」か「大事な番号を聞く」が、急がせる・名乗る・おどす・秘密にさせる・身内を名乗る・自動音声のどれかと一緒に出たら「高い」。
//   種類が 3 つ以上でも「高い」。それ以外で 1〜2 つなら「気をつけて」
const ScamCheckJs = (() => {
  const fold = (s) => {
    let out = '';
    for (const ch of s.normalize('NFKC').toLowerCase()) {
      const c = ch.codePointAt(0);
      out += c >= 0x30a1 && c <= 0x30f6 ? String.fromCodePoint(c - 0x60) : ch;
    }
    return out;
  };
  const norm = (s) => fold(s).replace(/[ \t\n\v\f\r、,・]/g, '');
  const signs = SCAM_DATA.signs.map((s) => ({
    key: s.key,
    label: s.label,
    re: new RegExp(s.patterns.map((p) => '(?:' + fold(p) + ')').join('|'), 'u'),
  }));
  const pressure = new Set(SCAM_DATA.pressure);

  const levelOf = (keys) => {
    if (keys.length === 0) return 'NONE';
    const ask = keys.includes('MONEY') || keys.includes('NUMBERS');
    return (ask && keys.some((k) => pressure.has(k))) || keys.length >= 3 ? 'HIGH' : 'CAUTION';
  };

  /** 相手の言葉 (1 行ずつ) から { level, reasons } */
  const check = (peer) => {
    const t = peer.map(norm).join('\n');
    const hit = signs.filter((s) => s.re.test(t));
    return { level: levelOf(hit.map((h) => h.key)), reasons: hit.map((h) => h.label) };
  };

  /** 「詐欺の疑い: 高い」(なしなら空) */
  const title = (level) => (level === 'NONE' ? '' : `${SCAM_DATA.mark}: ${SCAM_DATA.levels[level]}`);

  /** 台本ごとに、アプリと同じ結果になるか (同じ数, 全体の数) */
  const selfCheck = () => {
    let same = 0;
    for (const s of SCAM_DATA.scripts) {
      const r = check(s.lines);
      if (r.level === s.expected.level && r.reasons.join('・') === s.expected.reasons.join('・')) same++;
    }
    return [same, SCAM_DATA.scripts.length];
  };

  return { check, title, selfCheck };
})();
