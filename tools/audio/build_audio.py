#!/usr/bin/env python3
"""
发音音频包构建（macOS 本地 TTS 草稿版）

    python3 tools/audio/build_audio.py [--workers 4] [--force]

流程：audio-plan.json → say 合成 → 去静音 / 峰值归一 → afconvert 转 AAC mono 32k
产物：tools/out/audio/{c,w}/*.m4a  +  tools/out/data/audio-index.json

注意：这是「草稿音」（TTS 合成，多音字用同音字串控制读音），
按 docs/feature.md 红线 B-01 不得作为发版读音，须由审定顾问复核后替换为 L1 真人录音。
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import wave
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLAN = os.path.join(ROOT, "tools", "out", "data", "audio-plan.json")
OUTDIR = os.path.join(ROOT, "tools", "out", "audio")
INDEX = os.path.join(ROOT, "tools", "out", "data", "audio-index.json")
VOICE = "Tingting"
RATE_SR = 22050
THRESH = 260          # 静音判定阈值（16bit）
HEAD_MS, TAIL_MS = 40, 90
PEAK = 0.85


def trim_normalize(in_wav, out_wav):
    with wave.open(in_wav, "rb") as w:
        nch, sw, sr = w.getnchannels(), w.getsampwidth(), w.getframerate()
        raw = w.readframes(w.getnframes())
    if sw != 2:
        shutil.copyfile(in_wav, out_wav)
        return 0
    import array
    a = array.array("h")
    a.frombytes(raw)
    n = len(a)
    start, end = 0, n - 1
    while start < n and abs(a[start]) < THRESH:
        start += 1
    while end > start and abs(a[end]) < THRESH:
        end -= 1
    if start >= end:
        return 0
    head = int(sr * HEAD_MS / 1000)
    tail = int(sr * TAIL_MS / 1000)
    start = max(0, start - head)
    end = min(n, end + tail)
    seg = a[start:end]
    peak = max(1, max(abs(x) for x in seg))
    gain = min(4.0, (PEAK * 32767) / peak)
    if gain != 1.0:
        seg = array.array("h", (max(-32768, min(32767, int(x * gain))) for x in seg))
    with wave.open(out_wav, "wb") as w:
        w.setnchannels(nch)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(seg.tobytes())
    return int(len(seg) / sr * 1000)


def build_one(item, tmpdir):
    key = item["id"]
    digest = hashlib.sha1(key.encode()).hexdigest()[:12]
    sub = "w" if key.startswith("w:") else "c"
    out = os.path.join(OUTDIR, sub, digest + ".m4a")
    raw = os.path.join(tmpdir, digest + ".wav")
    norm = os.path.join(tmpdir, digest + ".n.wav")
    text = item["text"]
    try:
        subprocess.run(
            ["say", "-v", VOICE, "-r", str(item.get("rate", 120)),
             "-o", raw, "--data-format", f"LEI16@{RATE_SR}", "--", text],
            check=True, capture_output=True, timeout=30,
        )
        ms = trim_normalize(raw, norm)
        if ms < 80:
            return key, None, "silent"
        os.makedirs(os.path.dirname(out), exist_ok=True)
        subprocess.run(
            ["afconvert", "-f", "m4af", "-d", "aac", "-b", "32000", "-c", "1", norm, out],
            check=True, capture_output=True, timeout=30,
        )
        return key, (f"{sub}/{digest}.m4a", ms, os.path.getsize(out)), None
    except subprocess.CalledProcessError as e:
        return key, None, (e.stderr or b"").decode()[:120]
    finally:
        for p in (raw, norm):
            if os.path.exists(p):
                os.remove(p)


def main():
    global FORCE
    FORCE = "--force" in sys.argv
    workers = 4
    if "--workers" in sys.argv:
        workers = int(sys.argv[sys.argv.index("--workers") + 1])
    plan = json.load(open(PLAN, encoding="utf-8"))
    items = plan["items"]
    os.makedirs(OUTDIR, exist_ok=True)

    existing = {}
    if os.path.exists(INDEX) and not FORCE:
        try:
            existing = json.load(open(INDEX, encoding="utf-8")).get("items", {})
        except Exception:
            existing = {}
    todo = [it for it in items if it["id"] not in existing]
    print(f"计划 {len(items)} 条，已有 {len(items) - len(todo)} 条，待合成 {len(todo)} 条")

    result = dict(existing)
    missing = []
    with tempfile.TemporaryDirectory() as tmp:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for i, (key, data, err) in enumerate(pool.map(lambda it: build_one(it, tmp), todo), 1):
                if data:
                    result[key] = {"f": data[0], "ms": data[1], "b": data[2]}
                else:
                    missing.append({"id": key, "reason": str(err)[:80]})
                if i % 60 == 0 or i == len(todo):
                    print(f"  {i}/{len(todo)} 已生成", flush=True)

    index = {
        "version": plan["version"],
        "voice": VOICE,
        "codec": "aac-lc 32kbps mono 22.05kHz",
        "status": "draft-tts",
        "note": "TTS 草稿音频，多音字以同音字串控制读音，未经审定，不得作为发版读音。",
        "items": result,
        "missing": missing,
    }
    json.dump(index, open(INDEX, "w", encoding="utf-8"), ensure_ascii=False)
    total = sum(v["b"] for v in result.values())
    print(f"完成：{len(result)} 条，{total / 1024 / 1024:.2f} MB，缺失 {len(missing)} 条")
    if missing:
        print("缺失样例：", missing[:8])


if __name__ == "__main__":
    main()
