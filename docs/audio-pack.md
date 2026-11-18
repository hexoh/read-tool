# 发音音频包（草稿版 v0.2.0-draft-pinyin）

## 1. 包里有什么

| 项 | 数量 | 说明 |
| --- | --- | --- |
| 音频文件 | 1436 条（8.9 MB） | `tools/out/audio/c/*.m4a`（1312 条单字）＋ `w/*.m4a`（124 条词条） |
| 索引条目 | 15600 条 | 字库 15444 字全部指向各自拼音对应的单字音频 |
| 双版本条目 | 35 条 | `:old` 旧读 / `:modern` 今读，仅差异集内 |
| 编码 | AAC-LC 32 kbps、单声道、22.05 kHz | 峰值归一，去首尾静音 |
| 时长 | 最短 258ms / P50 463ms / 最长 2.43s | 单字 105 字/分，词组 130 字/分 |
| 索引 | `tools/out/data/audio-index.json` | 含文件相对路径、时长、字节数 |
| 缺失 | 0 | 合成失败会记入 `missing` 并在构建日志中报出 |

音色：macOS `Tingting`（zh_CN）。**这是草稿音，不是发版音。**

## 2. 索引契约（Android 端照此实现，换音频不改代码）

```
键：w:{词条}:{old|modern|any}      词条音频，优先命中
    c:{字}:{old|modern|any}        单字音频，词条缺失时回落
值：{ "f": "w/32aea2493d43.m4a",   相对 tools/out/audio 的路径
      "ms": 565,                   时长，用于朗读节奏与超时
      "b": 6917 }                  字节数，用于资源包体积核算
```

播放优先级（`android` 端 `ResultScreen.kt` + `audio/SoundPlayer.kt`）：
`w:{词}:{主读音}` → `w:{词}:any` → `c:{字}:{主读音}` → `c:{字}:any` → 系统 TTS → 明确提示「暂无录音」。

## 3. 单字为什么按拼音聚合

单字朗读只承载「音」，同拼音的字音频完全相同，可共用一条文件。

- 15444 字 → 1280 个不同拼音 → 1312 条单字音频（剩 32 条是 `:old` / `:modern` 变体）。
- 相比逐字合成（15444 条），体积从数十 MB 降到 8.9 MB。
- 代表字优先取 GB2312 一级字，避免生僻字 TTS 读音漂移。

## 4. 多音字怎么合对的

`say` 读单字会用默认读音，旧读读不出来。因此差异集里每个旧读都配了一串**同音字**：

| 词条 | 旧读 | 合成文本 | 今读 | 合成文本 |
| --- | --- | --- | --- | --- |
| 般若 | bō rě | 波惹 | bān ruò | 班弱 |
| 南无 | ná mó | 拿摩 | nán wú | 南无 |
| 僧伽 | sēng qié | 僧茄 | sēng jiā | 僧家 |
| 给孤独园 | jǐ gū dú yuán | 几孤独园 | gěi … | 给孤独园 |
| 摩诃 | mā hē | 妈喝 | mó hē | 魔喝 |
| 摩睺罗伽 | … qié | …罗茄 | … jiā | …罗家 |

同音字串写在 `tools/data/readings.source.json` 的 `tts` 列。**它是合成控制参数，不是读音依据**；读音依据是 `old` / `modern` 拼音与 `src` 出处。

## 5. 两个构建脚本

| 脚本 | 输入 | 产物 | 现状 |
| --- | --- | --- | --- |
| `tools/audio/build_by_pinyin.py` | `tools/out/data/lexicon.json` | 按拼音聚合的单字音频 + 索引 | **当前音频包由它生成** |
| `tools/audio/build_audio.py` | `tools/out/data/audio-plan.json` | 逐字 / 逐词音频 + 索引 | 早期逐字版本，保留作参考 |

两者共用同一套后处理：

1. `say -v Tingting -r {105|130}` → 22.05 kHz / 16-bit / 单声道 WAV
2. 去静音：以 260/32768 为阈值找首尾有效样本，前留 40ms、后留 90ms
3. 峰值归一：目标 0.85，增益上限 4×（避免把底噪抬起来）
4. `afconvert -f m4af -d aac -b 32000` → m4a
5. 写 `audio-index.json`（含 ms / 字节数），失败项进 `missing`
6. 断点续跑：已存在文件 / 索引的条目跳过

```bash
python3 tools/audio/build_by_pinyin.py --workers 6   # 按拼音聚合（当前主路径）
python3 tools/audio/build_audio.py --workers 3       # 逐字合成（旧路径）
```

> 目标响度 −16 LUFS 尚未做：本机没有 ffmpeg，峰值归一只能保证不削波、不忽大忽小。接 ffmpeg 后应改为 `loudnorm` 两遍处理。

## 6. 转正路径（替换成审定音，零代码改动）

1. 审定顾问复核 `tools/data/readings.source.json`：`old` / `modern` / `src` 三列逐条过，`tts` 列删掉。
2. 按同一份清单录制真人音，文件名保持 `c|w + hash(id)`，或按 `f` 字段覆盖同名文件。
3. 重跑索引生成，把 `status` 从 `draft-tts` 改为 `recorded`。
4. 复制到 `android/app/src/main/assets/audio/` 并同步 `assets/data/audio-index.json`。
5. 设置页「离线资源」会显示版本与「正式/草稿」标记，无需改 UI。

## 7. 覆盖边界（说清楚，别夸大）

- 音频按拼音聚合，**不是逐字录音**：15444 字共用 1280 个读音文件，同音字听起来完全一样。
- 差异集外的字只有普通读音，没有旧读版本。
- 覆盖率的正确口径是**字次**（字次覆盖，而非字种覆盖），当前还没做覆盖率统计脚本，接入真实样张后需补。
- 未收录音不会静默：字卡显示拼音 + 「暂无录音」，朗读时跳过并提示「本页 N 个字暂无录音」。
