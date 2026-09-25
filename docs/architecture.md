# 架构说明

LingReader 的目录组织、模块职责与关键数据流。

## 项目概览

| 项目 | 值 |
|---|---|
| 应用名 | **LingReader**（包名仍为 `com.lreader`，不可变更以保数据） |
| 包名 | `com.lreader` |
| 语言 | Kotlin |
| UI | Jetpack Compose（Material 3） |
| minSdk / targetSdk / compileSdk | 24 / 33 / 33 |
| AGP / Gradle / Kotlin | 8.1.4 / 8.9 / 1.8.10 |
| Compose | Compose 1.4.3 + Compiler 1.4.3 + material3 1.1.2 |
| 构建产物 | `app/build/outputs/apk/debug/app-debug.apk`（实测 20171695 B ≈ 19.2 MB，词典改为按需下载后） |
| 当前版本 | **v1.1.0**（`versionCode` = 11、`versionName` = `1.1.0`） |

### 设计原则

- **纯本地**：词典、分级词库、生词本全部离线，无网络也能查词/背单词。
- **零重型依赖**：不引入 Room / Hilt / Retrofit；用原生 `SQLiteOpenHelper` + `JSON` + `OkHttp`。
- **词典按需下载**：主词典（约 115MB）**不随包内置**，用户在 设置 → 本地词典 里下载
  （默认 GitHub Release，可改成镜像 / 自建服务器）；小体积的分级词库仍随包内置。
  落盘路径 `filesDir/dict_en_zh.db` 与 v1.0.x 完全一致 —— **老用户升级后直接复用，无需重新下载**。
- **可插拔翻译**：翻译引擎通过接口注册；默认内置**免 Key 的免费在线引擎**（开箱即用），也支持自填 API Key，不绑定任何厂商。

---

## 目录结构

```
ling-reader/                       # ★ Android 工程根目录（GitHub 仓库根）
├── README.md                  # 项目说明
├── dict-assets/               # 词典发布资源（已 .gitignore）：dict_en_zh.min.db，需上传 GitHub Release，见 docs/development.md
├── settings.gradle.kts        # 仓库配置（含阿里云镜像）
├── build.gradle.kts           # 根构建（AGP 8.1.4 / Kotlin 1.8.10）
├── gradle.properties
├── local.properties           # sdk.dir=<本机 SDK 路径>（勿提交）
├── gradlew                    # ★ Gradle Wrapper（v1.0.2 补入）
├── gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties   # distributionUrl = gradle-8.9-bin.zip
└── app/
    ├── build.gradle.kts       # 模块构建（compileSdk 33 / 依赖清单）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   └── levels.db          # 4.6MB(4,800,512B) 分级词库（3.9万词条，随包内置）
        │                          # ※ 主词典 dict_en_zh.min.db 已移出 assets，改从 GitHub Release 下载
        ├── res/                   # 图标、主题、字符串；xml/network_security_config.xml 放行 http 下载源
        └── java/com/lreader/
            ├── LRreaderApp.kt             # Application：初始化翻译引擎
            ├── MainActivity.kt            # 唯一 Activity，承载 Compose
            │
            ├── model/
            │   └── Models.kt              # 全部数据类
            │
            ├── sync/
            │   ├── SyncEngine.kt          # ★ 通用双向合并（下载→解密→合并→加密上传，sha 乐观锁 + 冲突重试）
            │   ├── SyncCrypto.kt          # 端到端加密：PBKDF2(口令, salt=syncId, 12 万轮) → AES-256-GCM
            │   ├── SyncCode.kt            # 同步码：仓库 + token + 用户目录 + 口令打包成一个字符串
            │   ├── SyncDefaults.kt       # 内置同步仓库默认值（「一键生成」用；token 构建时由 resValue 注入）
            │   ├── GithubBackend.kt       # GitHub Contents API 后端（私有仓库，每人一个目录）
            │   ├── VocabSync.kt           # 生词本同步（云端是加密 CSV）
            │   ├── ProgressSync.kt        # 阅读进度（章节 + 章内百分比）
            │   └── SyncManager.kt         # 调度：sync() / autoSync() / awaitInitialSync()
            │
            ├── dict/
            │   ├── DictCatalog.kt         # 词典资源清单 + 默认下载源（GitHub Release 常量）
            │   ├── DictManager.kt         # ★ 资源安装管理：内置释放 / 下载(进度·取消) / 校验 / 删除
            │   ├── DictDatabase.kt        # 主词典查询（只打开已下载的库）
            │   └── LevelDictionary.kt     # 分级词库查询
            │
            ├── analysis/
            │   └── WordAnalysis.kt        # 词性 / 纯释义 / 原句切分（纯 Kotlin）
            │
            ├── export/
            │   └── VocabExporter.kt       # 生词本导出 CSV / JSON（独立模块）
            │
            ├── speech/
            │   └── SpeechManager.kt       # TTS 发音封装
            │
            ├── translate/
            │   ├── TranslationEngine.kt   # 引擎接口（扩展点）
            │   ├── Translators.kt         # 5 个引擎实现（含免 Key 的免费在线引擎，默认）
            │   └── TranslationEngines.kt  # 注册中心 + Key 持久化
            │
            ├── data/
            │   ├── BookRepository.kt      # 书架（JSON 持久化）
            │   ├── VocabRepository.kt     # 生词本（SQLite + SM-2）
            │   └── SettingsStore.kt       # 设置（JSON 持久化）
            │
            ├── book/
            │   └── BookParser.kt          # TXT/EPUB/FB2/HTML 解析
            │
            └── ui/
                ├── AppNavHost.kt          # 导航图 + 底部导航 + 路由定义
                ├── theme/AppTheme.kt      # 全局视觉规范（集中调色）
                ├── study/StudyScreen.kt   # 背单词 Tab（生词本/复习 二级切换）
                ├── magazine/MagazineScreen.kt # 外刊杂志（占位页）
                ├── shelf/ShelfScreen.kt   # 书架页
                ├── reader/
                │   ├── ReaderScreen.kt    # 阅读页（核心，分页/滑动/下拉调参/长按选词）
                │   ├── ReadingTheme.kt    # 阅读配色：纸白 / 米黄 / 夜间
                │   ├── TextSelection.kt   # 选区模型 + 光标手柄渲染/拖动（独立模块）
                │   └── DictBottomSheet.kt # 查词弹层 + 选句翻译弹层
                ├── vocab/
                │   ├── VocabScreen.kt     # 生词本列表
                │   └── ReviewScreen.kt    # 背单词
                ├── admin/AdminScreen.kt   # 管理员面板（密码进入）：本机数据库体积 / 删除、云端用户删除
                └── settings/SettingsScreen.kt # 设置页（词典资源管理、云同步、管理员入口）
```

> 词典与词库的原始构建工具链（`mdx2sqlite.py` / `shrink_dict.py` / `export_levels.py` / `verify_dict.py` 与 21 世纪大英汉词典 `.mdx` 源）不在本仓库内，
> 其产物即 `dict-assets/dict_en_zh.min.db` 与 `app/src/main/assets/levels.db`；重建流程见 [development.md](development.md)。

---

## 模块职责

### 数据模型 `model/Models.kt`

| 类型 | 用途 |
|---|---|
| `Book` | 书籍元信息（id 用绝对路径、格式、上次阅读章节/滚动位置） |
| `BookFormat` | 枚举 TXT/EPUB/FB2/HTML/UNKNOWN + MIME 数组 |
| `Chapter` | 章节（index、title、content） |
| `DictEntry` | 查词结果（word、html、dictionaryName、level、levelName、phonetic） |
| `VocabWord` | 生词本条目（含 SM-2 的 repetition/interval/easiness/grade/nextReview） |
| `ReviewGrade` | 复习评级枚举 FORGOT(0)/VAGUE(3)/KNOWN(5) |
| `TranslationResult` | 翻译结果（sourceText、translatedText、engine、success、errorMessage） |
| `LevelConfig` | 分级配置（level、displayName、enabled、color） |

### 词典层

**`dict/DictCatalog.kt`** — 词典资源清单（v1.1.0 新增）
- `DictResource`：`id` / 展示名 / 说明 / `fileName`（落盘名）/ `remoteFile`（附件名）/ `sizeBytes` / `assetName`（非空=随包内置）/ `removable`。
- `MAIN`（英汉词典，`assetName = null` → **需下载**）；`LEVELS`（分级词库，`assetName = "levels.db"` → 内置）。
- `DEFAULT_SOURCE = https://github.com/genoling/ling-reader/releases/download/dict-v1/`，用户可在设置页覆盖。

**`dict/DictManager.kt`** — 资源安装管理器（v1.1.0 新增，进程级单例 `get(context)`）
- 状态：未安装 / 下载中(progress) / 已安装 / error；`onChanged` 主线程回调驱动设置页刷新。
- `ensureInstalled()`：已安装直接用；**内置资源**从 assets 释放（写 `.part` 再 rename，失败不留半个库）；
  **下载型资源绝不自动联网**（不会出现「一进阅读页就偷偷下 115MB」）。
- `download()`：OkHttp 流式下载 + 200ms 节流进度 + 可取消 + **双重校验**（字节数 + `SQLite format 3` 文件头）+ rename 落盘。
- `remove()` 仅对 `removable` 资源生效（分级词库内置，删掉会让高亮永久失效，故禁止删除）。
- 网络明文 HTTP（镜像 / 局域网源）由 `res/xml/network_security_config.xml` 放行。

**`dict/DictDatabase.kt`** — 主词典（下载后位于 `filesDir/dict_en_zh.db`）
- `ensureReady()`：**不再拷贝 assets、不联网**，只打开本地文件；文件缺失或被外部删除时返回 false 并释放旧句柄
  （`isInstalled()` / `isExtracted()` 均可探测安装状态）。
- 表结构：`dict(word TEXT PRIMARY KEY, html BLOB)`，html 是 **zlib 压缩**的 UTF-8 片段。
- `lookup(word, levels)` → 词形还原后查询，解压 HTML，附带分级信息；
  **主词典 miss 时自动回退到 ECDICT 补充词典**（`DictCatalog.ECDICT`，77 万词条，另带 `lemma` 变形表）。
- 两个库表结构一致（`dict(word, html)`），补充词典多一张 `lemma(form, base)`（94,524 条，来自 ECDICT 的 `exchange` 字段）；
  它是可选资源，文件变动用 `supStamp`（长度 xor 修改时间）热探测，下载完 / 删除后无需重启 App 即生效。
- `resolveKey()` 候选顺序：原词 → 小写 → 首字母大写 → **词形还原两层**：
  ① 屈折（-s / -es / -ies / -ed / -ing / -er / -est / -ves / -ier / 撇号缩写）；
  ② 派生后缀（-ness / -ment / -tion / -sion / -ance / -ence / -ity / -able / -ible / -ive / -ous /
  -ism / -ist / -ize / -ful / -less / -ish / -ary / -ory / -ly / -al / -ic，外加 -ably→-able、-ibly→-ible、-ly→-le）。
  派生命中时 `DictEntry.formOf` 记录源词，弹层显示「未收录 X，以下为词根 Y 的释义」。
- `ensureReadable()`：词条正文一个中文都没有时（如 `vt. subdue的变形`、化学名词 `= 1-octene`）追加一行说明，避免只剩一行词性。
- `enrichFormEntry()`：词条若是「xx的变形」型交叉引用（`WordAnalysis.crossRefTarget`），
  取出源词词条正文拼在其后（原词无音标时继承源词音标），保证任何词性都能看到中文释义。

**`dict/LevelDictionary.kt`** — 分级词库（assets/levels.db，由 DictManager 释放）
- 表结构：`level_word(word, level, phonetic, meaning)`、`level_meta(level, name, word_count)`。
- `ensureReady()` 时**全量载入内存**构建 `HashMap<word, List<level>>`，查询 O(1)。
- `levelsOf(word)` / `matches(word, enabledLevels)` / `primaryLevel(word)` / `colorOf(level)`。

### 语音 `speech/SpeechManager.kt`

- **进程级单例**：`SpeechManager.get(context)`，四个页面共用一份；页面 `onDispose` **不**释放引擎
  （每页各自 init/shutdown 是「首次点词没声音」的主因），`LRreaderApp.onCreate()` 启动即 `init()`。
- 首选 `android.speech.tts.TextToSpeech`（离线、无次数限制）：
  - `Accent` 枚举 `US`(美音) / `UK`(英音)；`rate` 语速（0.5~2.0）；`enabled` 开关。
  - **`enginePackage`**：指定引擎（空 = 跟随系统默认），用三参构造 `TextToSpeech(ctx, listener, pkg)` 绑定；
    赋值即 `rebind()`（旧引擎 shutdown → 重新 init），指定引擎不可用时回退系统默认并重试一次。
  - **`preferOnline`**：强制走在线发音（跳过系统引擎，手机 / 平板音色一致，代价是联网）。
  - **离线引擎下载安装**：`TtsCatalog` 列出 4 档 sherpa-onnx 官方引擎 APK（Apache-2.0、一包一模型，
    托管在 hf-mirror），经 `DictManager.register()` 登记后与词典共用同一套下载机制；
    下载完由 `TtsInstaller` 经 FileProvider（`${applicationId}.fileprovider`）交给系统安装器
    （`ACTION_VIEW` + `application/vnd.android.package-archive`），用户确认后才真正安装。
  - `engineItems()` 枚举设备上的引擎：`Settings.Secure.tts_default_synth` 取默认引擎，
    `queryIntentServices("android.intent.action.TTS_SERVICE")` 取引擎列表（Android 11+ 依赖 Manifest 的 `<queries>` 声明）。
    **刻意不用** `TextToSpeech.getEngines()` —— 该 API 需先持有实例，且在部分 SDK 上取不到。
  - 语言逐级回退：用户选择 → 另一种口音 → `Locale.ENGLISH` → 引擎默认。
  - 初始化失败（设备无引擎，模拟器常见）记录原因并进入 **15 秒冷却**，不再每次点击都重建引擎。
- **在线发音兜底**：系统引擎不可用（或 `speak()` 返回 ERROR）时改用有道 `dictvoice` 免费接口
  下载 mp3（`cacheDir/tts_cache/<hash>.mp3` 缓存复用）→ `MediaPlayer` 播放，保证「点了就有声音」；
  失败回调 `onOnlineError`，设置页给出原因。
- 未就绪时只保留**最后一个**待播词：连续点词不会排队连读、也不互相打断。
- `Status` / `Mode` + `onStatusChange` 回调，供设置页「发音自检」展示当前发声通道。
- **注意**：`get()` 内部持 `applicationContext`，避免内存泄漏。

### 翻译层

**`translate/TranslationEngine.kt`** — 扩展接口
```kotlin
interface TranslationEngine {
    val id: String
    val displayName: String
    fun isConfigured(): Boolean
    suspend fun translate(text: String, from: String = "en", to: String = "zh"): TranslationResult
}
```

**`translate/Translators.kt`** — 5 个实现
| 类 | 端点 | 鉴权 |
|---|---|---|
| `FreeWebTranslator`（默认） | 依次尝试 `aidemo.youdao.com/trans` → `api.mymemory.translated.net/get` → `translate.googleapis.com/translate_a/single` | **免 Key**，`isConfigured()` 恒为 true |
| `DeepLTranslator` | `api.deepl.com/v2/translate` | `DeepL-Auth-Key`；key 以 `:fx` 结尾时用 `api-free.deepl.com` |
| `BaiduTranslator` | `fanyi-api.baidu.com/api/trans/vip/translate` | MD5(appid+q+salt+key) |
| `TencentTranslator` | `tmt.tencentcloudapi.com` | TC3-HMAC-SHA256 手工签名 |
| `OpenAITranslator` | `{baseUrl}/v1/chat/completions` | Bearer Token |

- `FreeWebTranslator` 用独立 OkHttpClient（连接 8s / 读 10s），多端点串行尝试不会等太久；
  中文语言码按平台差异化：有道 `zh-CHS`、Google/MyMemory `zh-CN`
- MyMemory 免费额度用尽时仍返回 HTTP 200，但正文是 `MYMEMORY WARNING…`，已识别并转下一个端点

**`translate/TranslationEngines.kt`** — 注册中心
- `init(context)` 在 `LRreaderApp.onCreate()` 调用。
- `saveKey(name, value)` → 持久化到 `filesDir/translate_keys.json` → `reload()` 重建引擎实例。
- `DEFAULT_ENGINE = "free"`，且 `FreeWebTranslator` 注册在 `registry` **首位**：
  `current()` 没命中时 `available().first()` 兜底到免费在线，因此不会再出现「无可用引擎」。
- `translate(text, from, to)` 优先用用户在设置里选中的引擎，其次**首个已配置**的引擎；
  成功结果写入内存 LRU 缓存（`CACHE_MAX = 128`），`clearCache()` 可手动清空。

**Key 名称约定**：`deepl`、`baidu_appid`、`baidu_key`、`tencent_id`、`tencent_key`、`openai_key`、`openai_url`、`openai_model`。

### 数据仓库层

| 类 | 存储 | 说明 |
|---|---|---|
| `BookRepository` | `filesDir/bookshelf.json` | 书架列表；`importFile()` 把外部文件拷到 `filesDir/books/`，重名自动加后缀 |
| `VocabRepository` | `filesDir/vocab.db` (SQLite) | 生词 CRUD + **SM-2 算法**（`review()` 方法） |
| `SettingsStore` | `filesDir/settings.json` | 全部设置项，用 `obj.optXxx(key, default)` 读取、setter 中持久化 |

**SM-2 算法规则**（`VocabRepository.review`）：
```
grade < 3  → repetition=0, interval=1天
grade >= 3 → repetition+=1
             rep==1 → interval=1
             rep==2 → interval=6
             rep>=3 → interval=round(interval*EF)
EF' = EF + (0.1 - (5-grade)*(0.08 + (5-grade)*0.02))，下限 1.3
```

### 书籍解析 `book/BookParser.kt`

| 格式 | 解析方式 |
|---|---|
| TXT | `readTextAutoEncoding()` 探测 BOM/UTF-8/GBK；正则切分章节 |
| EPUB | 读 `META-INF/container.xml` → OPF → manifest/spine → XHTML → `htmlToText()` |
| FB2 | 正则提取 `<section>` 块 → `htmlToText()` |
| HTML | 全文转文本，单章节 |

`htmlToText()`：剥离 script/style、`<br>`→`\n`、`</p>`→`\n\n`、去标签、实体反转义、压缩空行。

### UI 层

**`ui/AppNavHost.kt`** — 路由
```kotlin
Routes.SHELF    = "shelf"       // 书架（起始页）
Routes.READER   = "reader?bookId={bookId}"
Routes.SETTINGS = "settings"
Routes.VOCAB    = "vocab"
Routes.REVIEW   = "review"
```
bookId 用 `URLEncoder/URLDecoder` 编解码（因为它可能是文件路径）。

**`ui/reader/ReaderScreen.kt`** — 核心页面
- 载入：book → chapters → 词典/分级库/TTS 初始化 → 生词集合
- `WordText()`：`Box { Text + SelectionOverlay }`，正文用 `buildAnnotatedString` 打两个注解：
  - `"WORD"` → 单词本身
  - `"SENT"` → 该词所在句子（`sentenceOf()` 按 `. ! ? \n 。！？` 切）
- 手势（`awaitEachGesture` 手写，替代 `ClickableText` 的 `detectTapGestures`）：
  - **轻点** → 取 `"WORD"` 注解 → 发音 → 查词
  - **长按** → `Selections.wordAt()` 命中单词并建立选区；长按后继续拖动可整词扩选；松手提交
- `PagedReader`：`var selection by remember(chapterKey)`，翻页/换章自动清空选区
- **正文分级排版**：`BookParser.htmlToText` 在 `h1/h2/h3/h4~h6/blockquote` 处插入控制字符标记
  （`MARK_TITLE`…`MARK_QUOTE`），阅读页 `parseBlocks()` 还原成 `TextBlock(text, BlockKind)`，
  按 `BlockKind.scale/bold/dim` 渲染（标题 1.55x/1.25x/1.10x 加粗、栏目与引用用次要色）；
  测量与渲染共用 `blockStyle()`，否则分页行数会对不上
- **书内链接与列表**：`htmlToText` 把 `<li>` 转成「换行 + `• `」、把 `<a href>` 转成
  `MARK_LINK 目标路径 MARK_LINK_TEXT 文字 MARK_LINK_END`；阅读页将「整块内容就是链接」的块渲染为
  `LinkLine`（主色 + 下划线，点击 → `onInternalLink(路径)` → `chapters.indexOfFirst { it.sourcePath == 路径 }` 跳章），
  正文里与文字混排的链接退化为普通文字，避免整段染色
- **分页 + 图文混排**：先用透明 `Text` 逐块测量（`onTextLayout`，key = `layoutKey(段号, 块号)`），
  插图只读图片头部（`inJustDecodeBounds`）按正文宽度等比换算高度，再由 `paginateFlow()` 按**像素**
  把「文字块 + 插图」装进同一页（`ReaderPage.blocks`）；每块按自己的行高累加，一页由多个 `PageBlock` 组成，
  渲染时 `Column` 顺序排布 `WordText` 与 `ChapterImageView`。
  正文插图限高页高的 62%（否则宽屏上图片会撑满整页、把整段文字挤到下一页），
  整章几乎只有一张图（封面）时放宽到 94%
- **目录**：`BookParser.loadToc(book, chapters)` 解析 EPUB 的 `toc.ncx` / `nav.xhtml`
  （保留父子层级、锚点去 fragment），顶栏「目录」按钮 → 弹层点击跳章；无目录信息（txt/fb2）时回退成章节列表
- **阅读进度**：`lastChapterIndex` + `lastScrollY`（复用为页码）；进入时恢复，
  翻页 / 切章后防抖 800ms 自动保存，离开页面 `onDispose` 再兜一次
- 选区提交分流：单字 → 查词弹层；多字 → `onPhraseSelect` 走句子翻译
- 手势回调用 `rememberUpdatedState` 包裹，避免长寿命手势闭包读到旧选区
- 配色优先级：**生词本词(红)** > **分级词(各级色)** > 默认黑

**`ui/reader/TextSelection.kt`** — 选词/选区（独立模块，可单测）
- `TextSelection(start, end)`：字符偏移半开区间，含 `normalized()` / `slice()`
- `Selections`：`wordAt()` 取词、`snapBoundary()` 端点吸附词边界、`isSingleWord()` 分流查词/翻译
- `SelectionOverlay`：按行求交画高亮块 + 两条光标竖线 + 底部圆形手柄
- `Handle` 拖拽：`getBoundingBox` 正推光标位置，`getOffsetForPosition` 反查偏移（不依赖 Compose 1.5 的 `SelectionContainer`）
  - 拖动以**最新完整选区**为基准只动自己那一端，`coerceIn` 夹住另一侧，不会翻转或塌成空选区
  - `pointerInput` / `remember` **不带 `idx` 等易变 key**，否则选区一变手势重启、拖一半卡住
- `extendSelection()`：长按拖动时把原始偏移按锚点词向左右扩展

**`ui/reader/DictBottomSheet.kt`**
- `DictBottomSheet`：单词标题 + 音标 + 级别标签 + 发音按钮 + 收藏按钮 + WebView 释义 + 整句翻译区
  - 外层 `Column` 整体 `verticalScroll`（**不设 maxHeight**）：释义再长也能一路滑到「原文句子 / 译文」
- `DictHtmlView(html, autoHeight)`：按内容撑开 WebView 高度，`onTouchEvent` 恒返回 false 不消费触摸，
  滚动手势交还外层弹层。**高度只增不减**：WebView 既然不接收滚动，一旦量小，超出部分就永久看不到
  （`conversion` 这类多义项词条曾被截断）——现在 0/120/360/800ms 分档补测 `contentHeight`，
  再用注入 JS 读 DOM 真实高度取最大值，另加 10px 余量
- `SentenceSheet`：独立整句翻译弹层（同样可滚动）
- `WRAP_HTML` 常量：包裹词典 HTML 的样式模板（含 `.xref` 变形词提示样式）

**`ui/vocab/VocabScreen.kt`** — 生词本列表（搜索/发音/删除/FAB 进入复习）
- 进入本页时修补历史数据：缺音标补音标、**释义无中文的补中文**（`repo.updateMeaning`）

**`ui/vocab/ReviewScreen.kt`** — 背单词（卡片 + 三档评分 + 进度条 + 完成统计）
- 切卡时预取 `DictDatabase.lookup(word)`，供「详细解析」按词性分层渲染
- `WordDetail(word, entry)`：原句（高亮该词）+ `SentenceTranslation`（整句译文）+
  `PosGroup` 分层释义（词性标签 + 逐条义项）+ 复习历史
**`ui/settings/SettingsScreen.kt`** — 5 个设置分组
**`ui/shelf/ShelfScreen.kt`** — 书架（SAF 文件选择器导入、删除、生词本入口角标）

---

## 关键数据流

### 跨设备同步（生词本 / 阅读进度）

```
进入 App（LRreaderApp）
  → SyncManager.autoSync()                      [IO 线程；失败只记日志，设置页可见]
      → SyncEngine.run()                        每个文件一轮：
           下载 users/<syncId>/*.enc   （GitHub Contents API，顺带拿到 sha）
           → SyncCrypto.decrypt()    （口令 → PBKDF2 → AES-GCM；失败 = 同步码不对）
           → 解析（CSV / JSON）
           → merge()                 按主键去重，同主键取 updatedAt 更新的一条
           → changes() → apply()     只把有变化的写回本地（生词库 / bookshelf.json）
           → SyncCrypto.encrypt() → upload(带 sha)
                 冲突（409/422）→ 重新下载合并，最多 3 轮
```

- 主键：生词用 `uid`（老数据升级时自动补 UUID）；进度用**书名**（`Book.id` 是本机路径编码，跨设备不同）
- 阅读页启动时会 `awaitInitialSync()` 短暂等同步结束，保证生词高亮与云端一致
- 同步码 `LR1.<base64>` 内含口令，**云端只有密文**；同一同步码即同一个用户目录
- 生成同步码：设置 → 云同步 → **「一键生成」**—— 仓库 / 分支 / 令牌预置在 `SyncDefaults`，
  点一下即生成新同步码（新 `syncId` + 新口令）并立即同步一次；「自定义」入口可换成自己的仓库与 token。
  令牌由构建时注入（`resValue("string", "sync_token")`，见 `docs/development.md`），未注入的构建自动退回手填对话框

### 点词查词

```
用户点击单词
  → WordText.onClick(offset)
  → 取 "WORD" / "SENT" 注解
  → SpeechManager.speak(word)          [自动发音]
  → DictDatabase.lookup(word, levels)  [IO线程]
       ├─ resolveKey()   词形还原
       ├─ SQLite 查询 html BLOB
       ├─ Inflater 解压
       └─ LevelDictionary.primaryLevel() 附分级
  → DictBottomSheet 展示
```

**词典未下载时**：`ensureReady()` 返回 false → `lookup()` 返回 null → 弹层显示
「本地词典未下载 + 请到 设置 → 本地词典 下载」，与「未收录该单词」区分开；
阅读页底部同时有一条常驻提示，避免用户误以为词库太小查不到词。

### 加入生词本

```
点击弹层「+」
  → VocabRepository.add(VocabWord(word, meaning, phonetic, sentence, sourceBook, level))
  → 更新 vocabWords 集合（正文立即变红）
  → 书架角标 +1
```

### 背单词

```
VocabScreen → FAB「开始复习」
  → VocabRepository.dueToday()   [next_review <= now，为空则取全部]
  → ReviewScreen 逐张卡片
  → 评分 → VocabRepository.review(word, grade)  [SM-2 更新]
  → 全部完成 → FinishedView 统计
```

### 句子翻译

```
查词弹层点「翻译」
  → TranslationEngines.translate(sentence, to = settings.targetLang)
  → 命中内存 LRU（128 句）→ 直接返回，不联网
  → 未命中 → current()（设置里选中的引擎）
       └─ 没配 Key / 未选 → available().first() 兜底 = FreeWebTranslator（免 Key）
            → 有道体验接口 → 失败换 MyMemory → 再失败换 Google
  → 展示译文或错误信息（成功结果写回缓存）
```

### 长按选词 / 选区

```
长按正文（awaitLongPressOrCancellation）
  → layout.getOffsetForPosition(position)
  → Selections.wordAt(text, offset)        [命中整词，空白处则 null]
  → selection = hit                        [SelectionOverlay 画高亮 + 两条手柄]
  ├─ 长按后继续移动 → extendSelection(text, hit, 手指偏移)   [整词扩选]
  └─ 松手 → onSelectionCommit(sel)
       → picked = sel.slice(text).trim().replace(空白→单空格)
       ├─ 空            → selection = null
       ├─ isSingleWord  → onWordClick(picked)   → 发音 + DictBottomSheet
       └─ 多词           → onPhraseSelect(picked.take(500)) → SentenceSheet 翻译

拖动已有手柄（Handle 的 detectDragGestures）
  → 手指位移累加 → getOffsetForPosition(probe) → snapBoundary 吸附词边界
  → 以「最新完整选区」为基准只改自己那一端（coerceIn 夹住另一侧）
  → onSelectionChange(新选区) → 松手 onCommit
```

### 背单词：原句整句翻译

```
点「查看释义」→ revealed = true
  → WordDetail 渲染：原文句子 → SentenceTranslation(sentence)
  → LaunchedEffect(sentence, retry)
       ├─ loading = true              → 「译文生成中…」
       ├─ TranslationEngines.translate(sentence, to = settings.targetLang)
       │     └─ 默认免费在线引擎（免 Key），成功结果进 LRU 缓存
       ├─ success  → 译文文本（Surface 浅底衬，紧贴原句下方）
       └─ failure  → 错误原因 + 「重试」按钮（retry+1 触发重新请求）
  → 切到下一张卡：dictEntry 预取 + 翻译缓存各自生效，来回翻不重复联网
```

**词性分层释义（同页「详细解析」）**

```
entry?.html（DictDatabase.lookup 返回，已补全变形词释义）
  → WordAnalysis.parseDefinitions(html)    [按 <span class="pos"> 切片 → PosDefs(pos, defs)]
  → 过滤 isCrossReference（「xx的变形」这类无中文的指向性说明）
  → 每个词性一个标签 + 逐条义项（PosGroup）
兜底：词典查不到 → WordAnalysis.splitByPos(word.meaning) 按词性切分存库文本
```

---

