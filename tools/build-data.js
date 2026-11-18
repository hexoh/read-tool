#!/usr/bin/env node
/**
 * 数据构建：读音差异集 → 运行时数据结构
 *  1. tools/out/data/readings.json  词条 + 单字差异集（含出处）
 *  2. tools/out/data/lexicon.json   字信息（拼音/注音/部首/笔画/结构/繁体候选）
 *  3. tools/out/data/context-terms.json  块级语境判定词表
 *  4. tools/out/data/audio-plan.json 待合成音频清单（交给 tools/audio/build_audio.py）
 *  5. tools/out/data/demo.json      示例页（字表 / 说明文，不含经文）
 */
const fs = require('fs');
const path = require('path');
const cnchar = require('cnchar');
cnchar.use(require('cnchar-radical'));
cnchar.use(require('cnchar-trad'));
const { pinyin } = require('pinyin-pro');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'tools', 'out', 'data');
const read = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));

// ---------- 拼音 → 注音 ----------
const INITIALS = [
  ['zh', 'ㄓ'], ['ch', 'ㄔ'], ['sh', 'ㄕ'], ['b', 'ㄅ'], ['p', 'ㄆ'], ['m', 'ㄇ'], ['f', 'ㄈ'],
  ['d', 'ㄉ'], ['t', 'ㄊ'], ['n', 'ㄋ'], ['l', 'ㄌ'], ['g', 'ㄍ'], ['k', 'ㄎ'], ['h', 'ㄏ'],
  ['j', 'ㄐ'], ['q', 'ㄑ'], ['x', 'ㄒ'], ['r', 'ㄖ'], ['z', 'ㄗ'], ['c', 'ㄘ'], ['s', 'ㄙ'],
];
const FINALS = {
  a: 'ㄚ', o: 'ㄛ', e: 'ㄜ', 'ê': 'ㄝ', ai: 'ㄞ', ei: 'ㄟ', ao: 'ㄠ', ou: 'ㄡ',
  an: 'ㄢ', en: 'ㄣ', ang: 'ㄤ', eng: 'ㄥ', er: 'ㄦ', i: 'ㄧ', ia: 'ㄧㄚ', ie: 'ㄧㄝ',
  iao: 'ㄧㄠ', iu: 'ㄧㄡ', iou: 'ㄧㄡ', ian: 'ㄧㄢ', in: 'ㄧㄣ', iang: 'ㄧㄤ', ing: 'ㄧㄥ',
  iong: 'ㄩㄥ', u: 'ㄨ', ua: 'ㄨㄚ', uo: 'ㄨㄛ', uai: 'ㄨㄞ', ui: 'ㄨㄟ', uei: 'ㄨㄟ',
  uan: 'ㄨㄢ', un: 'ㄨㄣ', uen: 'ㄨㄣ', uang: 'ㄨㄤ', ong: 'ㄨㄥ', ü: 'ㄩ', 'üe': 'ㄩㄝ',
  'üan': 'ㄩㄢ', 'ün': 'ㄩㄣ', v: 'ㄩ', ve: 'ㄩㄝ', van: 'ㄩㄢ', vn: 'ㄩㄣ', io: 'ㄧㄛ',
};
const TONE_MARK = { 'ā': ['a', 1], 'á': ['a', 2], 'ǎ': ['a', 3], 'à': ['a', 4],
  'ō': ['o', 1], 'ó': ['o', 2], 'ǒ': ['o', 3], 'ò': ['o', 4],
  'ē': ['e', 1], 'é': ['e', 2], 'ě': ['e', 3], 'è': ['e', 4],
  'ī': ['i', 1], 'í': ['i', 2], 'ǐ': ['i', 3], 'ì': ['i', 4],
  'ū': ['u', 1], 'ú': ['u', 2], 'ǔ': ['u', 3], 'ù': ['u', 4],
  'ǖ': ['ü', 1], 'ǘ': ['ü', 2], 'ǚ': ['ü', 3], 'ǜ': ['ü', 4] };
const TONE_SIGN = { 1: '', 2: 'ˊ', 3: 'ˇ', 4: 'ˋ', 5: '˙' };

function pinyinToZhuyin(syllable) {
  let tone = 5, plain = '';
  for (const ch of syllable) {
    if (TONE_MARK[ch]) { plain += TONE_MARK[ch][0]; tone = TONE_MARK[ch][1]; }
    else if (ch >= '0' && ch <= '9') { tone = +ch; }
    else plain += ch;
  }
  plain = plain.replace(/ü/g, 'v');
  // y / w 开头的整体认读还原
  const Y = { yi: 'i', ya: 'ia', yao: 'iao', ye: 'ie', you: 'iou', yan: 'ian', yin: 'in',
    yang: 'iang', ying: 'ing', yong: 'iong', yu: 'v', yue: 've', yuan: 'van', yun: 'vn', yo: 'io' };
  const W = { wu: 'u', wa: 'ua', wo: 'uo', wai: 'uai', wei: 'uei', wan: 'uan', wen: 'uen',
    wang: 'uang', weng: 'ong' };
  if (Y[plain]) plain = Y[plain];
  else if (W[plain]) plain = W[plain];
  else if (/^yu/.test(plain)) plain = 'v' + plain.slice(2);

  let initial = '';
  for (const [k, v] of INITIALS) {
    if (plain.startsWith(k)) {
      // zhi/chi/shi/ri/zi/ci/si 的「空韵」只写声母
      initial = v;
      plain = plain.slice(k.length);
      if (plain === 'i' && /^(ㄓ|ㄔ|ㄕ|ㄖ|ㄗ|ㄘ|ㄙ)$/.test(v)) plain = '';
      break;
    }
  }
  // 空韵：zhi/chi/shi/ri/zi/ci/si 只写声母
  if (plain === '' && /^(ㄓ|ㄔ|ㄕ|ㄖ|ㄗ|ㄘ|ㄙ)$/.test(initial)) {
    return tone === 5 ? '˙' + initial : initial + TONE_SIGN[tone];
  }
  // j/q/x + v → ü
  let final = FINALS[plain];
  if (final === undefined) return null;
  if ((initial === 'ㄐ' || initial === 'ㄑ' || initial === 'ㄒ') && plain.startsWith('v')) {
    final = FINALS[plain.replace(/^v/, 'ü')] || final;
  }
  const body = initial + (final || '');
  return tone === 5 ? '˙' + body : body + TONE_SIGN[tone];
}
const toZhuyin = (py) => py.split(/\s+/).map(pinyinToZhuyin).filter(Boolean).join(' ');

// ---------- 源数据 ----------
const src = read(path.join(ROOT, 'tools', 'data', 'readings.source.json'));
const demo = read(path.join(ROOT, 'tools', 'data', 'demo.json'));
const variantGroups = read(path.join(ROOT, 'tools', 'data', 'variants.json')).groups;
const NEGATIVE = ['版权所有', '版权', '出版社', '印刷', '定价', '网址', '电话', '地址', '目录', '页码', '微信', '公众号'];

const words = src.words.map(([w, oldP, modernP, oldT, modernT, source]) => ({
  w, old: oldP, modern: modernP, ttsOld: oldT || w, ttsModern: modernT || w, src: source,
  diff: oldP !== modernP,
}));
const chars = src.chars.map(([c, oldP, modernP, oldT, modernT, source]) => ({
  c, old: oldP, modern: modernP, ttsOld: oldT || c, ttsModern: modernT || c, src: source,
  diff: oldP !== modernP,
}));
// 多音字候选（不做旧读/今读二分，只提供候选，由词组语境决定）
const polyphones = (src.polyphones || []).map(([c, rs, note]) => ({
  c, readings: rs.map((p) => ({ p, z: toZhuyin(p) })), note: note || '',
}));
const polyMap = new Map(polyphones.map((e) => [e.c, e]));

// ---------- 需要发音的单字集合 ----------
const needChars = new Set();
words.forEach((w) => [...w.w].forEach((c) => needChars.add(c)));
chars.forEach((e) => needChars.add(e.c));
polyphones.forEach((e) => needChars.add(e.c));
demo.pages.forEach((p) => [...(p.text + p.note)].forEach((c) => /[\u3400-\u9FFF]/.test(c) && needChars.add(c)));
[...(demo.baseChars || '')].forEach((c) => needChars.add(c));
variantGroups.forEach((g) => g.forEach((c) => needChars.add(c)));

// ---------- lexicon ----------
const charDiff = new Map(chars.map((e) => [e.c, e]));
const lexicon = { version: src.version, chars: {} };
for (const c of [...needChars].sort()) {
  const e = charDiff.get(c);
  const modernP = e ? e.modern : null;
  let p = modernP;
  if (!p) {
    const got = pinyin(c, { toneType: 'symbol', type: 'string' });
    p = got && got.trim() ? got.trim() : null;
  }
  let rad = null, strk = null, struct = null;
  try { const r = cnchar.radical(c); if (r && r[0]) { rad = r[0].radical; struct = r[0].struct; } } catch (_) {}
  try { const s = cnchar.stroke(c); strk = typeof s === 'number' ? s : null; } catch (_) {}
  let trad = null;
  try {
    const t = cnchar.convert.simpleToTrad(c);
    const arr = Array.isArray(t) ? t : String(t).split('');
    if (arr.length && arr[0] !== c) trad = arr;
  } catch (_) {}
  let simp = null;
  try {
    const sp = cnchar.convert.tradToSimple(c);
    const arr = Array.isArray(sp) ? sp : String(sp).split('');
    if (arr.length && arr[0] !== c) simp = arr[0];
  } catch (_) {}
  const vg = variantGroups.find((g) => g.includes(c));
  lexicon.chars[c] = {
    p, z: p ? toZhuyin(p) : null,
    o: e ? { p: e.old, z: toZhuyin(e.old) } : null,
    m: e ? { p: e.modern, z: toZhuyin(e.modern) } : null,
    poly: polyMap.has(c) ? polyMap.get(c).readings : null,
    polyNote: polyMap.has(c) ? polyMap.get(c).note : null,
    rad, strk, struct, trad, simp,
    vari: vg ? vg.filter((x) => x !== c) : null,
    cp: 'U+' + c.codePointAt(0).toString(16).toUpperCase().padStart(4, '0'),
  };
}

// 词组读音的注音：把词条里每个字的音节注音回填到字库（字卡按词条读音显示时用）
for (const w of words) {
  const cs = [...w.w];
  for (const py of new Set([w.old, w.modern])) {
    const syl = py.split(/\s+/);
    if (syl.length !== cs.length) continue;
    cs.forEach((c, i) => {
      const e = lexicon.chars[c];
      const z = pinyinToZhuyin(syl[i]);
      if (!e || !z) return;
      e.zy = e.zy || {};
      e.zy[syl[i]] = z;
    });
  }
}

// ---------- 运行时差异集 ----------
const readings = {
  version: src.version,
  note: src.note,
  words: words.slice().sort((a, b) => b.w.length - a.w.length),
  chars: Object.fromEntries(chars.map((e) => [e.c, {
    old: { p: e.old, z: toZhuyin(e.old) }, modern: { p: e.modern, z: toZhuyin(e.modern) },
    src: e.src, diff: e.diff,
  }])),
  polyphones: Object.fromEntries(polyphones.map((e) => [e.c, { readings: e.readings, note: e.note }])),
};

// ---------- 语境判定词表 ----------
const contextTerms = {
  version: src.version,
  // 强证据：诵读类术语（命中即加权）
  ritual: words.map((w) => w.w),
  // 套语（作为词典条目收录，非经文正文）
  formulas: ['如是我闻', '一时', '闻如是', '稽首', '南无佛', '愿以此功德'],
  // 强负样本：现代印刷品
  negative: NEGATIVE,
  // 现代文本常见的虚词，出现越多越不像诵读本
  modernMarkers: ['我们', '你们', '他们', '这个', '那个', '可以', '所以', '因为', '如果', '说明', '设置'],
};

// ---------- 音频计划 ----------
const plan = [];
for (const w of words) {
  if (w.diff) {
    plan.push({ id: `w:${w.w}:old`, text: w.ttsOld, rate: 130 });
    plan.push({ id: `w:${w.w}:modern`, text: w.ttsModern, rate: 130 });
  } else {
    plan.push({ id: `w:${w.w}:any`, text: w.ttsOld, rate: 130 });
  }
}
for (const e of chars) {
  if (e.diff) {
    plan.push({ id: `c:${e.c}:old`, text: e.ttsOld, rate: 105 });
    plan.push({ id: `c:${e.c}:modern`, text: e.ttsModern, rate: 105 });
  } else {
    plan.push({ id: `c:${e.c}:any`, text: e.ttsModern, rate: 105 });
  }
}
for (const c of needChars) {
  if (charDiff.has(c)) continue;
  plan.push({ id: `c:${c}:any`, text: c, rate: 105 });
}

// 通用读音兜底表：字库未收的字也能给出拼音（只给现代读音，不给旧读，不猜）
const fallback = {};
for (let cp = 0x4E00; cp <= 0x9FA5; cp++) {
  const c = String.fromCodePoint(cp);
  if (lexicon.chars[c]) continue;
  const py = pinyin(c, { toneType: 'symbol', type: 'string' });
  if (py && py !== c && !py.includes('?') && /^[\p{Script=Latin}\p{M}]+$/u.test(py)) fallback[c] = py;
}

// 防呆闸门：当前字库（15444 字）是后期扩充产物，本脚本只能重建 686 字基线。
// 直接覆盖会让字库无声回退，所以默认中止，需显式 --force 才写。
const existingLex = path.join(OUT, 'lexicon.json');
if (!process.argv.includes('--force') && fs.existsSync(existingLex)) {
  const prevN = Object.keys(JSON.parse(fs.readFileSync(existingLex, 'utf8')).chars || {}).length;
  const newN = Object.keys(lexicon.chars).length;
  if (prevN > newN) {
    console.error(`[已中止] 现有 lexicon ${prevN} 字，本脚本将生成 ${newN} 字。`);
    console.error('覆盖会丢失扩充字库；15444 字库不由本脚本产出，勿用本脚本重建。');
    console.error('确实要回退到基线请加 --force。');
    process.exit(1);
  }
}

fs.mkdirSync(OUT, { recursive: true });
const dump = (name, obj) => {
  fs.writeFileSync(path.join(OUT, name), JSON.stringify(obj));
  return `${name} ${(JSON.stringify(obj).length / 1024).toFixed(1)}KB`;
};
const log = [
  dump('readings.json', readings),
  dump('lexicon.json', lexicon),
  dump('context-terms.json', contextTerms),
  dump('audio-plan.json', { version: src.version, items: plan }),
  dump('demo.json', demo),
  dump('pinyin.json', fallback),
];
console.log(log.join('\n'));
console.log(`兜底读音 ${Object.keys(fallback).length} 字`);
console.log(`词条 ${words.length}（差异 ${words.filter((w) => w.diff).length}）｜单字差异 ${chars.length}｜字库 ${needChars.size}｜音频 ${plan.length} 条`);
