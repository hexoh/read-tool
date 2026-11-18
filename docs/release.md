# 打包与发版

本项目打 release 包有两条路：**CI 自动**（推荐）和 **本机手动**。
签名密钥、GitHub Secrets、换机器后的环境恢复，都在这里说清楚。

| 方式 | 谁执行 | 适合 |
| --- | --- | --- |
| CI 自动 | 推 tag 后由 GitHub Actions 构建 | 日常发版 |
| 本机手动 | 自己在终端 `./gradlew` | 调试、CI 不可用时兜底 |

---

## 1. 自动打包（GitHub Actions）

推一个 tag 就会自动构建、签名、发 Release。工作流文件：`.github/workflows/android-release.yml`。

```bash
git tag v1.2.0
git push --tags
```

执行流程：

```
checkout → JDK 21 → Android SDK → 从 Secrets 还原 keystore
        → assembleRelease（版本号取自 tag，versionCode 用运行序号）
        → readtool-<版本>.apk 挂到对应 Release
```

### 1.1 准备签名密钥

**密码可以自己定，密钥也可以自己生成后再接入**——构建只认 `android/keystore.properties`，
不管密钥是谁造的：

```bash
# 方式 A：脚本新建，密码随机生成（推荐，省心）
bash tools/make-keystore.sh

# 方式 B：脚本新建，密码自己指定
bash tools/make-keystore.sh -p 'MyPass123'

# 方式 C：复用已有密钥（自己用 keytool / Android Studio 生成的，或以前发版用的）
bash tools/make-keystore.sh -e -o ~/keys/release.jks
#   别名不是 readtool 时加：-a 你的别名
```

脚本会把 keystore 路径、密码、别名写进 `android/keystore.properties`（不入库），
并导出一份 `<路径>.base64`。

### 1.2 填 GitHub Secrets

仓库 → `Settings → Secrets and variables → Actions`，新增 4 个：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 base64（脚本已导出到 `<路径>.base64`） |
| `STORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名，默认 `readtool` |
| `KEY_PASSWORD` | key 密码（与 STORE_PASSWORD 相同） |

### 1.3 发版

```bash
git tag v1.2.0
git push --tags
```

完成后到仓库 Releases 页面下载 `readtool-1.2.0.apk`。

### 1.4 密钥在 CI 上是怎么走的

仓库里没有密钥（`.gitignore` 排除了 `*.jks` 与 `keystore.properties`），所以每次构建都由
workflow 现场还原：

```yaml
- name: Restore keystore from secrets
  run: |
    printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/readtool.jks"
    {
      echo "storeFile=$RUNNER_TEMP/readtool.jks"
      echo "storePassword=$STORE_PASSWORD"
      echo "keyAlias=$KEY_ALIAS"
      echo "keyPassword=$KEY_PASSWORD"
    } > android/keystore.properties
```

- Secrets 只能存文本，密钥是二进制，所以用 base64 传输。
- 还原目标是 `$RUNNER_TEMP`，Job 结束后随虚拟机一起销毁，**不会留在 GitHub 上**。
- `keystore.properties` 是本地与 CI 的唯一契约：有它 Gradle 就签名，没有就产出未签名包。

`make-keystore.sh` 只在**你的电脑上跑一次**，CI 不会执行它。

---

## 2. 本地手动打包

```bash
bash tools/make-keystore.sh            # 首次：生成签名
cd android && ./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

没有 `android/keystore.properties` 时，release 包**不签名、装不上**；只是自己调试请用：

```bash
cd android && ./gradlew assembleDebug
```

---

## 3. 换电脑 / 新人接手

**只想发版 → 基本零配置。** 代码和 Secrets 都在 GitHub 上：新机器装个 git，clone 下来改代码、
打 tag 推送，CI 就出包。不需要装 JDK、SDK，也不需要密钥。

**想在本机打包 → 要恢复 4 样东西**（它们都不在仓库里）：

| 需要 | 为什么不在仓库 | 怎么恢复 |
| --- | --- | --- |
| JDK 21 + Android SDK | 系统级安装 | 装 JDK 21；再装 `platforms;android-35` + `build-tools;35.0.0` |
| `android/local.properties` | gitignore，机器路径 | 新建一行 `sdk.dir=/你的/SDK/路径` |
| `~/.gradle/gradle.properties` | 用户级配置 | 加 `org.gradle.java.home=<JDK 21 路径>` |
| keystore + 密码 | **绝不入库** | 从备份取回 `.jks`，跑 `bash tools/make-keystore.sh -e -o <路径>` |

### 3.1 自检

新机器上先跑这个，缺什么会直接列出来（只读，不改文件）：

```bash
bash tools/doctor.sh
```

它检查 7 项：

| 检查项 | 说明 |
| --- | --- |
| JDK 版本 | 必须是 21（Kotlin 2.0.21 / AGP 8.7.2 的上限） |
| SDK 目录 | 来自 `android/local.properties` 或 `ANDROID_HOME` |
| `platforms;android-35` | compileSdk 35 |
| `build-tools;35.0.0` | 打包工具链 |
| `gradlew` | 是否可执行（wrapper 8.9 首次会自动下载） |
| keystore | 文件存在、密码与别名可用（用 `keytool -list` 实际验证） |
| node / python3 | 可选，仅重建数据 / 音频时需要 |

正常输出示例：

```
Java
  [ok]   JDK 21 — java version "21.0.10"
Android SDK
  [ok]   SDK 目录 /Users/you/Library/Android/sdk
  [ok]   platforms;android-35
  [ok]   build-tools;35.0.0
Gradle
  [ok]   gradlew 可执行（wrapper 8.9）
签名
  [注意] 没有 android/keystore.properties：release 包会未签名
全部就绪（7 项通过）
```

缺失项会汇总并以非 0 退出，方便脚本化判断。

---

## 4. 三条铁律

1. **已发布的密钥不能换。** 签名不一致会导致老版本无法覆盖升级，只能换包名重发。
   重新生成只在从未发布过时安全。
2. **keystore 与密码必须单独备份。** 它不在仓库里，旧机器坏掉就再也拿不回来。
   建议密码管理器 + 私有云盘各存一份。
3. **`android/gradle.properties` 里不要写 `org.gradle.java.home`。** 那是机器相关路径，
   提交后会让 CI 直接失败。本机需要固定 JDK 21 时写到 `~/.gradle/gradle.properties`。
