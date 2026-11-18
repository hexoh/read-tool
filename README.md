# 识字（read-tool）

拍下书页，逐字点选、听读音；也可以直接查一个字。
**本应用不提供、不内置任何经文正文**——只做「字与读音」这一件事，定位为繁体 / 古籍识字工具。

这是一个 **Android 原生应用**（Kotlin + Compose + CameraX + ONNX Runtime）。
端侧 OCR、拍照、发音全链路已在模拟器（arm64 / API 33）实测跑通。

---

## 为什么会有这个APP

我的妈妈闲暇时间喜欢读经书或者其他的书籍，但是有时候她有些字不认识，她又不会使用手机查这个字叫什么，而且有些字也是繁体字，所以我就做了这个APP，里面包含了简体和繁体的常用字，可以当一个识字APP使用，发音使用TTS合成，有些生硬。

## 1. 快速开始

环境要求：**JDK 21**、Android SDK（compileSdk 35 / build-tools 35.0.0）、Gradle Wrapper 8.9。

两处机器相关的配置，换机器时要改：

- `android/gradle.properties` → `org.gradle.java.home` 指向本机 JDK 21
- `android/local.properties` → `sdk.dir` 指向本机 Android SDK（该文件不入库，需本地新建）

```bash
cd android
./gradlew :domain:test          # 领域逻辑单元测试（19 例）
./gradlew assembleDebug         # 产出 app/build/outputs/apk/debug/app-debug.apk
```

依赖仓库走阿里云镜像（`android/settings.gradle.kts`），无需代理。
`debug` 含 arm64-v8a + x86_64（可跑模拟器），`release` 仅 arm64-v8a（体积更小）。

---

## 2. 目录结构

```
android/                        ← 应用本体，唯一产物
  app/src/main/java/.../app/
    MainActivity.kt             入口：系统相机 / 相册 Intent、OCR 调度、页面状态
    ui/                         Compose 界面：首页 / 拍照 / 结果页 / 查字 / 字卡 / 主题
    ocr/OcrEngine.kt            端侧 OCR：检测 + 识别 + 竖排旋转 + 形近字纠错
    audio/SoundPlayer.kt        发音播放（SoundPool + TTS 兜底）
    render/PageRenderer.kt      把文字排成可点击样张（竖排 / 横排）
    data/AppData.kt  Store.kt   读取 assets / 本机持久化（纠错、生字本、历史）
  app/src/main/assets/
    models/                     PP-OCRv6 small：det 9.4MB + rec 20MB + keys.txt
    audio/{c,w}/                1436 条 m4a（运行时读取）
    data/                       7 个数据 json（运行时读取）
  app/src/main/res/             主题、字符串、FileProvider 路径
  domain/src/main/kotlin/       纯 Kotlin 领域层（零 Android 依赖）
    ReadingResolver / ContextJudge / HitTest / Forms / Readings / Model
  domain/src/test/              JUnit 测试（19 例）
  build.gradle.kts  settings.gradle.kts  gradle/  gradlew

tools/                          ← 数据与音频的"生产线"（不改数据不用碰）
  data/                         人工维护的唯一源
    readings.source.json        读音差异集（旧读 / 今读 + 出处 + TTS 同音串）
    variants.json               异体字组
    demo.json                   示例页文本（字表 + 说明文，无经文）
  out/                          构建产物暂存区（见 §5）
    data/                       7 个 json，与 android assets 的 data 同步
    audio/{c,w}/                1436 条 m4a
  build-data.js                 生成基线数据 → tools/out/data（⚠️ 见 §5）
  audio/build_by_pinyin.py      按拼音聚合生成音频（当前主路径）
  audio/build_audio.py          逐字生成音频（早期版本，保留参考）
  ocr/verify_ocr.py             OCR 管线回归校验（Python + onnxruntime）
  make-keystore.sh              生成 release 签名密钥 + 打印 CI Secrets
  doctor.sh                     检查本机 JDK / SDK / 签名是否就绪（换电脑时先跑）
  package.json  package-lock.json

.github/workflows/
  android-release.yml           推 tag 自动构建签名并发布 Release（见 docs/release.md）

docs/                           ← 规格与设计记录
  feature.md                    需求规格：功能要求 FR-101~505、产品红线 B-01~B-08
  ui-design.md                  UI 设计规格（色板被 Theme.kt 引用）
  audio-pack.md                 音频索引契约与转正路径
  release.md                    打包与发版：签名、Secrets、本地打包、换机自检
```

> `docs/` 里的规格是 2026-11-16/17 的规划版本，实现过程中有调整（如读音默认策略）。
> **内容与代码冲突时，以代码和本 README 为准。**

> 网页原型（原 `app/` 目录）与网页端测试已删除，仓库只保留 Android 应用。

---

## 3. 功能一览

| 能力 | 落点 |
| --- | --- |
| 拍照 / 相册导入 → 端侧 OCR → 结果页全链路 | `ocr/OcrEngine.kt` + `MainActivity.kt` |
| 竖排 / 横排识别、字级切框、照片做底图 + 字框叠加校准 | `OcrEngine.kt` + `ui/ResultScreen.kt` + `render/PageRenderer.kt` |
| 点字发音：最近框中心命中、连点打断、TTS 兜底 | `domain/HitTest.kt` + `audio/SoundPlayer.kt` |
| 字卡：字形 / 拼音 / 注音 / 部首笔画 / Unicode / 无录音提示 | `ui/CharCard.kt` |
| 多音字与旧读 / 今读双音堆叠、词条联动 | `domain/ReadingResolver.kt` |
| 朗读：串播、高亮跟随、语速 0.5–1.5x、缺音频 Toast | `ui/ResultScreen.kt` + `SoundPlayer.kt` |
| 三级读音判定：词条 → 单字差异集 → 现代音（永不空） | `domain/ReadingResolver.kt` + `Readings.kt` |
| 块级语境判定（加权证据 + 保守阈值） | `domain/ContextJudge.kt` |
| 形近字纠错（词表 + 形近字表约束）、逐字把握度提示 | `OcrEngine.correctText()` + `confusables.json` |
| 简繁异体三视图 | `domain/Forms.kt` |
| 本机纠错记忆、生字本、历史、查字 | `data/Store.kt` + `ui/LookupScreen.kt` |

---

## 4. 数据资产

| 项 | 规模 | 说明 |
| --- | --- | --- |
| 字库 `lexicon.json` | 15,444 字 | GB2312 一二级 + BIG5 繁体 |
| 通用读音 `pinyin.json` | 20,167 字 | 兜底读音，未经审定 |
| 音频包 | 1,436 条 m4a（8.9 MB） | 单字 1,312（按 1,280 个拼音聚合）+ 词条 124 |
| 读音差异集 `readings.source.json` | 105 词条 + 16 差异字 | 旧读 / 今读的唯一人工入口 |
| 语境词表 `context-terms.json` | ritual 139 + formulas | 诵读词组 + 纠错依据 |
| 形近字表 `confusables.json` | 27 组 / 77 字 | **仅 Android 有**，手工维护，非脚本生成 |
| OCR 模型 | det 9.4MB + rec 20MB | PP-OCRv6 small ONNX，Apache-2.0 |

> **数据状态**：字库扩容部分的读音与全部音频均为 `draft-tts`（Tingting 合成草稿音），
> 读音未经审定顾问复核。发版前需按 `docs/audio-pack.md §6` 转正。

---

## 5. 数据与音频构建（改数据前必读）

数据流向：

```
tools/data/*.json  →  构建脚本  →  tools/out/{data,audio}  →  手动复制  →  android/.../assets/
```

### 5.1 ⚠️ `build-data.js` 只重建 686 字基线

当前 15,444 字库是**后期扩充产物**，不由 `build-data.js` 产出。直接重跑会用 686 字基线覆盖，
脚本已内置防呆闸门，检测到回退会中止：

```
[已中止] 现有 lexicon 15444 字，本脚本将生成 686 字。
```

确需回退到基线才加 `--force`。**日常改读音 / 词表，请直接编辑 `tools/out/data/` 下的 json。**

### 5.2 音频

```bash
python3 tools/audio/build_by_pinyin.py --workers 6   # 读 lexicon，按拼音聚合，断点续跑
```

> `build_audio.py` 是早期逐字版本，读 `tools/out/data/audio-plan.json`（仅 826 条基线）。
> **不要对它加 `--force`**：会按基线清单重建 `audio-index.json`，把 15600 条索引打回 826 条。

### 5.3 产物同步到 App

构建脚本写的是 `tools/out/`，App 读的是 `android/.../assets/`，两者需手动同步：

```bash
cp -R tools/out/data/.  android/app/src/main/assets/data/
cp -R tools/out/audio/. android/app/src/main/assets/audio/
# audio-plan.json 只给旧脚本用，App 不需要，可不复制
```

OCR 模型或参数调整后，用 `python3 tools/ocr/verify_ocr.py` 做横排 / 竖排回归。

---

## 6. 测试

```bash
cd android && ./gradlew :domain:test     # 19 例：读音判定 / 阅读顺序 / 语境 / 字形 / 命中
```

---

## 7. 打包与发版

推 `v*` tag 会自动构建、签名并发 Release：

```bash
git tag v1.2.0 && git push --tags
```

签名密钥的准备（三种接入方式）、GitHub Secrets、本地手动打包、换电脑后的环境恢复
与 `tools/doctor.sh` 自检，全部写在 **[docs/release.md](docs/release.md)**。

## 8. 已知限制

- **读音审定**：15,444 字读音多为 fallback，未逐字审定；生僻 / 佛经字的旧读覆盖不足。
- **发音**：按拼音聚合的草稿 TTS 音，同音字完全一样；非真人录音，未做 LUFS 响度归一。
- **OCR 形近字**：`般→股`、`罣→罢`、`觀→覲`、`自→目` 等偶发误识，tiny 容量所限；已用词表 + 形近字表兜住大部分，不保证全对。
- **词表纠错**：只对长度 ≥4 且形近的字生效，3 字词不参与，仍有低概率误纠。
- **拍照历史**：依赖 `cacheDir` 临时图片，被系统清理后重开拍照页会回退到手动录入。
- **识别耗时**：约 2–3 秒，略高于早期 ≤2s 目标。
- **词级释义**：字卡无释义区（规格 Q-2 未决，按「不做」处理）。

---

## 9. 许可与合规

- 端侧 OCR：PaddleOCR PP-OCRv6（Apache-2.0，商用免费）+ ONNX Runtime（Apache-2.0）。
- 音频为 macOS 内置 Tingting 语音合成的草稿音，替换真人录音前需确认授权。
- 应用不内置经文正文、不做经文库 / 检索，以降低《互联网宗教信息服务许可证》相关风险。
- 详细的需求边界与产品红线见 `docs/feature.md §4`。
