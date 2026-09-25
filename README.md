<div align="center">

<img src="docs/logo.png" alt="LingReader" width="120">

# LingReader

**本地优先的英语阅读学习 App**

点词查词 · 10 级词汇高亮 · 生词本 · SM-2 背单词 · 句子翻译
词典与词库完全离线，**无次数限制、无 VIP 门槛**

[![Release](https://img.shields.io/github/v/release/genoling/ling-reader?color=3DDC84)](https://github.com/genoling/ling-reader/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)](#技术栈)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.8.10-7F52FF?logo=kotlin&logoColor=white)](#技术栈)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.4.3-4285F4?logo=jetpackcompose&logoColor=white)](#技术栈)
[![License](https://img.shields.io/badge/License-MIT-lightgrey)](LICENSE)

</div>

---

## 简介

LingReader 面向「读英文原著 / 外刊」的学习场景，把**生词查询 → 分级标记 → 生词沉淀 → 间隔重复复习**串成一条完整链路。

所有词典查询都在本地 SQLite 完成，不联网也能用；翻译走可插拔引擎，默认内置免 Key 的免费在线引擎，装好即用。应用不采集任何用户数据，书架、生词本、设置全部存于本机。

## 核心特性

| 模块 | 能力 |
|---|---|
| **阅读** | TXT / EPUB / FB2 / HTML 导入；自动探测 UTF-8 / GBK / BOM；章节自动切分；**EPUB 章节目录**（含层级，点击跳章）、**插图内联排版（图文混排）**；阅读进度记忆（精确到页）；字号行距可调 |
| **书架** | **封面网格**（自动提取 EPUB 封面）、书名搜索、**长按拖动排序** |
| **外刊杂志** | 内置 216 期外刊（The Economist / The New Yorker / The Atlantic / Wired），一键下载进书架 |
| **查词** | 点击单词即查；**主词典 33 万 + ECDICT 补充词典 77 万词条**，主词典查不到自动回退；词形还原（`books`→`book`）；音标；长按选词、拖动手柄扩选短语 |
| **分级高亮** | 39,256 词条 / 10 个级别（CET4、CET6、考研、专四、专八、GRE、雅思、托福、高中、BEC），逐级配色、逐级开关 |
| **生词本** | 查词弹层一键收藏，自动带音标、词性、所在原句与来源书目；搜索、朗读、导出 CSV / JSON |
| **背单词** | SM-2 排程 + **每日刷新**（当天背过的当天不再出现、次日全部重现）、默认乱序；生词详情含**原句译文**；自动播报美音 |
| **云同步** | 生词本与阅读进度**加密同步**到自己的 GitHub 私有仓库（云端只有密文）；同步码一次配对，多设备自动合并 |
| **管理员** | 设置 → 高级 → 管理员模式（密码）：查看 / 清理本机数据库，删除云端用户数据 |
| **更新** | 设置页显示当前版本，**一键检查更新并下载安装** |
| **翻译** | 默认**免 Key 免费在线**（有道 → MyMemory → Google 自动兜底）；可切换 DeepL / 百度 / 腾讯云 / OpenAI 兼容 |
| **发音** | 系统语音引擎可切换（手机 / 平板选同一引擎即音色一致）；可一键下载安装离线神经网络引擎；无引擎自动在线兜底；美音 / 英音、语速可调 |
| **界面** | 4 套主题配色 + 3 套阅读配色（纸白 / 米黄 / 夜间） |

## 截图

| 书架 | 阅读（分级高亮） | 点词查词 |
|---|---|---|
| <img src="docs/screenshots/01-shelf.png" width="220"> | <img src="docs/screenshots/02-reader.png" width="220"> | <img src="docs/screenshots/03-dict.png" width="220"> |

| 生词本 | 背单词 | 夜间模式 |
|---|---|---|
| <img src="docs/screenshots/04-vocab.png" width="220"> | <img src="docs/screenshots/05-review.png" width="220"> | <img src="docs/screenshots/07-night.png" width="220"> |

## 下载安装

无需编译，直接安装 APK：

1. 打开 **[Releases](https://github.com/genoling/ling-reader/releases/latest)** 页面，下载最新版附件 `LingReader-<版本>-debug.apk`（约 19 MB）
2. 手机上安装时允许「安装未知来源应用」
3. 首次进入 App 后到 **设置 → 本地词典** 依次下载（默认走本仓库 GitHub Release，直连慢时可在同一页改「下载源」）：
   - **21世纪大英汉词典**（约 115 MB，主词典，必下）
   - **ECDICT 补充词典**（约 127 MB，77 万词条，主词典查不到时自动回退；可选）
4. 想要更好听的朗读：**设置 → 发音 → 安装离线语音引擎**，选一档下载后点「安装」，
   装完回到上面的「语音引擎」里选中它即可

> **词典未下载时 App 依然可用**：阅读、分级高亮、生词本、翻译都正常，只是点词查不到释义。
>
> 当前发布的是 **debug 签名**安装包。将来切换 release 签名的正式版时两者签名不同，**必须先卸载再安装**（应用内数据会清空），建议提前用 **生词本 → 导出 CSV** 备份。

## 快速开始

**环境要求**：JDK 17~21、Android SDK（platform android-33 + build-tools 34.0.0）。工程自带 Gradle Wrapper（8.9），无需本机安装 Gradle。

```bash
git clone https://github.com/genoling/ling-reader.git
cd ling-reader

# 指向本机 Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 构建（JAVA_HOME 必须指向 JDK 17~21）
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 19 MB）

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Windows 下用 `gradlew.bat`；不要使用 Java 25（Gradle 8.9 不支持）。
> 完整的构建、调试与问题排查请见 [docs/development.md](docs/development.md)。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 1.8.10 · Jetpack Compose 1.4.3（Material 3） |
| 构建 | Gradle 8.9 · AGP 8.1.4 · minSdk 24 / targetSdk 33 / compileSdk 33 |
| 数据 | 原生 `SQLiteOpenHelper`（生词本、词典）+ `JSONObject`（书架、设置） |
| 网络 | OkHttp（句子翻译、词典按需下载） |
| 依赖策略 | 零重型依赖：不使用 Room / Hilt / Dagger / Retrofit / Koin |

**词典数据**：33.2 万词条英汉词典（21 世纪大英汉词典，含音标），来源 MDict `.mdx`，经 `mdx → SQLite + zlib` 转换；正文 HTML 以 zlib 压缩存储，查询时解压渲染。

## 项目结构

```text
ling-reader/                      # Android 工程根目录（GitHub 仓库根）
├── app/                          # 应用模块
│   ├── build.gradle.kts          # compileSdk 33 / 依赖清单
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── levels.db             # 分级词库（4.6 MB，随包内置）
│       ├── res/
│       │   └── xml/network_security_config.xml   # 放行明文 HTTP 下载源
│       └── java/com/lreader/
│           ├── LRreaderApp.kt        # Application：初始化翻译引擎与 TTS
│           ├── MainActivity.kt       # 唯一 Activity，承载 Compose
│           ├── model/                # 数据模型
│           ├── book/                 # TXT / EPUB / FB2 / HTML 解析
│           ├── dict/                 # 词典资源清单、下载管理、查词
│           ├── translate/            # 可插拔翻译引擎
│           ├── speech/               # TTS 发音
│           ├── data/                 # 书架 / 生词本 / 设置持久化
│           ├── analysis/             # 词性、释义、原句处理
│           ├── export/               # 生词本导出 CSV / JSON
│           └── ui/                   # Compose 页面与主题
│               ├── AppNavHost.kt     # 导航图 + 底部导航
│               ├── shelf/ reader/ vocab/ study/     # 书架 / 阅读 / 生词本 / 背单词
│               ├── magazine/ settings/              # 外刊（占位）/ 设置
│               └── theme/AppTheme.kt                # 4 套主题配色
├── dict-assets/                  # 词典发布资源（.gitignore，需上传 GitHub Release）
├── docs/                         # 设计文档与截图
├── CHANGELOG.md                  # 版本历史
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
└── LICENSE
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 完整目录树、模块职责、关键数据流、设计原则 |
| [docs/development.md](docs/development.md) | 构建与调试、常见问题排查、开发约定、词典资源发布、后续规划 |
| [CHANGELOG.md](CHANGELOG.md) | 各版本变更记录 |

## 许可证

[MIT](LICENSE)

内置于本项目 / 通过 GitHub Release 分发的词典与词库数据（21 世纪大英汉词典、分级词库）版权归原权利人所有，**仅供个人学习使用**。
