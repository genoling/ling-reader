# GitHub 仓库主页信息

直接复制粘贴到 GitHub 仓库设置里。

## 仓库名

```
ling-reader
```

## Description（仓库简介，≤350 字符）

```
本地优先的英语阅读学习 Android App：离线点词查词、10 级词汇高亮、生词本、SM-2 背单词、句子翻译。
33.2 万词条词典内置，无次数限制、无 VIP 门槛。Kotlin + Jetpack Compose。
```

**短版（推荐，更清爽）**

```
离线点词查词 + 分级词汇高亮的英语阅读 App，内置 33 万词条词典与 SM-2 背单词。Kotlin / Compose。
```

## Website

留空，或填 Releases 页面：`https://github.com/<your-name>/ling-reader/releases`

## Topics（仓库标签，逐个添加）

```
android  kotlin  jetpack-compose  english-learning  e-reader  dictionary  vocabulary
spaced-repetition  sm2  offline-first  epub  txt  cet4  cet6  ielts  toefl  gre
```

## 仓库设置建议

| 选项 | 建议值 |
|---|---|
| Features → Issues | ✅ 开 |
| Features → Discussions | ✅ 开（放使用提问与词库讨论） |
| Features → Wiki | ❌ 关（文档统一放 `docs/`，避免两处维护） |
| Pull Requests → Allow squash merging | ✅ 开（保持线性历史） |
| Pull Requests → Allow merge commits | ❌ 关 |
| Default branch | `main` |

## 首次 Release 文案模板

**Tag**：`v1.0.5`
**Release title**：`LingReader v1.0.5 — 首个公开版本`

```markdown
## LingReader v1.0.5

本地优先的英语阅读学习 App 首个公开版本。

### 核心能力
- 📖 支持 TXT / EPUB / FB2 / HTML，章节自动切分
- 👆 点词即查，33.2 万词条离线词典，词形还原 + 音标 + 自动发音
- 🌈 10 级词汇高亮（CET4/CET6/考研/专四/专八/GRE/雅思/托福/高中/BEC）
- ⭐ 生词本：单词 / 词性 / 音标 / 含义，可导出 CSV / JSON
- 🔁 SM-2 间隔重复背单词：自动美音 + 记得 / 不记得 / 查看释义
- 🌐 可插拔翻译：DeepL / 百度 / 腾讯云 / OpenAI 兼容
- 🌙 三种阅读配色（纸白 / 米黄 / 夜间）+ 四套界面主题

### 安装
下载 `app-debug.apk` 直接安装（约 133 MB，内置离线词典）。

### 环境
minSdk 24 / targetSdk 33，Android 7.0 及以上。

### 已知限制
- 首次启动需释放词典到应用私有目录，约 10~30 秒
- 翻译功能需自行配置第三方平台 Key
- 外刊杂志订阅尚未实现（仅保留入口）
```

## Badge 片段（已内置在 README，可按需替换用户名）

```markdown
[![Release](https://img.shields.io/github/v/release/<your-name>/ling-reader)](https://github.com/<your-name>/ling-reader/releases)
[![Stars](https://img.shields.io/github/stars/<your-name>/ling-reader)](https://github.com/<your-name>/ling-reader/stargazers)
[![Issues](https://img.shields.io/github/issues/<your-name>/ling-reader)](https://github.com/<your-name>/ling-reader/issues)
```
