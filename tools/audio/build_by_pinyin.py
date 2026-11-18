#!/usr/bin/env python3
"""按拼音聚合生成单字音频（同音字共用一条）。

    python3 tools/audio/build_by_pinyin.py [--workers 6]

原理：单字朗读只承载"音"，同拼音的不同字音频完全相同，可共用。
8852 字 → 1223 个读音 → 1223 条音频（原来按字生成要 8852 条）。

产物：tools/out/audio/c/<md5(拼音)>.m4a + tools/out/data/audio-index.json
      index 里每个 c:字:any 都指向其拼音对应的文件。
词条（w:）和多音变体（c:字:old/modern）保持不变，不做重建。
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_audio import RATE_SR, VOICE, trim_normalize  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, "tools", "out", "data")
AUDIO = os.path.join(ROOT, "tools", "out", "audio")
INDEX = os.path.join(DATA, "audio-index.json")
RATE = 105


def lvl1_set():
    """GB2312 一级字：挑选代表字时优先用最常用的，TTS 读音最可靠。"""
    s = set()
    for hi in range(0xB0, 0xD8):
        for lo in range(0xA1, 0xFF):
            try:
                s.add(bytes([hi, lo]).decode("gb2312"))
            except Exception:
                pass
    return s


def gen(char, out_path, tmpdir):
    raw = os.path.join(tmpdir, "r.wav")
    norm = os.path.join(tmpdir, "n.wav")
    subprocess.run(
        ["say", "-v", VOICE, "-r", str(RATE), "-o", raw,
         "--data-format", f"LEI16@{RATE_SR}", "--", char],
        check=True, capture_output=True, timeout=30,
    )
    ms = trim_normalize(raw, norm)
    if ms < 80:
        return None
    subprocess.run(
        ["afconvert", "-f", "m4af", "-d", "aac", "-b", "32000", "-c", "1", norm, out_path],
        check=True, capture_output=True, timeout=30,
    )
    return ms, os.path.getsize(out_path)


def probe_ms(path):
    r = subprocess.run(["afinfo", path], capture_output=True, text=True)
    m = re.search(r"estimated duration:\s*([\d.]+)", r.stdout or "")
    return int(float(m.group(1)) * 1000) if m else 400


def main():
    workers = 6
    if "--workers" in sys.argv:
        workers = int(sys.argv[sys.argv.index("--workers") + 1])

    lex = json.load(open(os.path.join(DATA, "lexicon.json"), encoding="utf-8"))["chars"]
    groups = {}
    for ch, v in lex.items():
        p = (v.get("p") or "").strip()
        if p:
            groups.setdefault(p, []).append(ch)

    lvl1 = lvl1_set()
    reps = {}
    for p, chars in groups.items():
        # 代表字优先取常用字，避免生僻字 TTS 读音漂移
        rep = next((c for c in chars if c in lvl1), chars[0])
        reps[p] = rep
    print(f"字库 {len(lex)} 字 → {len(groups)} 个读音 → 生成 {len(groups)} 条音频")

    os.makedirs(os.path.join(AUDIO, "c"), exist_ok=True)
    old_index = json.load(open(INDEX, encoding="utf-8")) if os.path.exists(INDEX) else {}
    items = {k: v for k, v in old_index.get("items", {}).items()
             if k.startswith("w:") or k.endswith(":old") or k.endswith(":modern")}
    print(f"保留词条/多音变体 {len(items)} 条")

    result = {}
    failed = []
    with tempfile.TemporaryDirectory() as tmp:
        def work(p):
            digest = hashlib.sha1(("pinyin:" + p).encode()).hexdigest()[:12]
            out = os.path.join(AUDIO, "c", digest + ".m4a")
            # 断点续跑：已生成过的直接复用，避免重复合成
            if os.path.exists(out) and os.path.getsize(out) > 1000:
                return p, (digest, (probe_ms(out), os.path.getsize(out))), None
            t = tempfile.mkdtemp(dir=tmp)
            try:
                r = gen(reps[p], out, t)
                return p, (digest, r), None
            except Exception as e:
                return p, None, str(e)[:80]
            finally:
                shutil.rmtree(t, ignore_errors=True)

        with ThreadPoolExecutor(max_workers=workers) as pool:
            for i, (p, data, err) in enumerate(pool.map(work, list(groups)), 1):
                if data:
                    digest, (ms, size) = data
                    result[p] = {"f": f"c/{digest}.m4a", "ms": ms, "b": size}
                else:
                    failed.append({"pinyin": p, "reason": err})
                if i % 200 == 0 or i == len(groups):
                    print(f"  {i}/{len(groups)}", flush=True)

    # 把每个字指向其读音文件
    for p, chars in groups.items():
        r = result.get(p)
        if not r:
            continue
        for ch in chars:
            items[f"c:{ch}:any"] = r

    index = {
        "version": "0.2.0-draft-pinyin",
        "voice": VOICE,
        "codec": "aac-lc 32kbps mono 22.05kHz",
        "status": "draft-tts",
        "note": "TTS 草稿音频；单字按拼音聚合共用同一文件，未经审定，不得作为发版读音。",
        "items": items,
        "missing": failed,
    }
    json.dump(index, open(INDEX, "w", encoding="utf-8"), ensure_ascii=False)

    used = {v["f"] for v in items.values()}
    removed = 0
    for sub in ("c", "w"):
        d = os.path.join(AUDIO, sub)
        for f in os.listdir(d):
            rel = f"{sub}/{f}"
            if rel not in used:
                os.remove(os.path.join(d, f))
                removed += 1
    total = sum(v["b"] for v in items.values() if v["f"].startswith("c/"))
    uniq = {v["f"]: v["b"] for v in items.values() if v["f"].startswith("c/")}
    print(f"完成：index {len(items)} 条，单字音频 {len(uniq)} 个文件，"
          f"{sum(uniq.values())/1048576:.1f} MB，清理孤儿文件 {removed} 个，失败 {len(failed)} 个")


if __name__ == "__main__":
    main()
