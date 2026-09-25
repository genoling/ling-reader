# 开发指南

构建、调试、常见问题排查、开发约定与词典资源发布。

## 开发约定

### 必须遵守

1. **不要引入重型依赖**：不引入 Room / Hilt / Dagger / Retrofit / Koin。数据层用原生 `SQLiteOpenHelper`（参考 `VocabRepository`）、持久化用 `JSONObject`（参考 `BookRepository`/`SettingsStore`）、网络用 `OkHttp`（已在依赖中）。
2. **不要改动词典文件格式**：`assets/dict_en_zh.min.db` 与 `assets/levels.db` 的表结构是固定的，改格式需同时改 `work/` 下的 Python 脚本并说明。
3. **不要删除 `assets/` 下的大文件**：它们是 App 的核心资产（合计约 120MB）。
4. **Compose 版本约束**：当前 `compileSdk = 33`，因此 Compose 必须停留在 **1.4.x**。若要用 1.5+ API（如 `LinkAnnotation`、`withLink`、`pullToRefresh`），**必须先升级 compileSdk 到 34 并确认本机有 android-34 platform**。
5. **词汇高亮的颜色优先级**：生词本词 > 分级词 > 默认。修改时保持该优先级。
6. **每完成一个需求，必须同步更新 README 与 CHANGELOG。**

### 新增翻译引擎的做法

```kotlin
// 1. 在 Translators.kt 增加实现
class MyTranslator(private val key: String) : TranslationEngine {
    override val id = "my"
    override val displayName = "我的引擎"
    override fun isConfigured() = key.isNotBlank()
    override suspend fun translate(text: String, from: String, to: String) = withContext(Dispatchers.IO) {
        // …返回 TranslationResult
    }
}

// 2. 在 TranslationEngines.reload() 中注册；同时把 id 加到 TranslationEngines.selectable，
//    否则设置页的引擎芯片里选不到它。注意 registry 首位是免 Key 的 FreeWebTranslator，
//    它是「没配 Key 时」的兜底，新增免 Key 引擎时留意顺序与 isConfigured()。
registry.add(MyTranslator(ks.get("my_key")))

// 3. 在 SettingsScreen 的 when (engineId) 中增加分支或 KeyField("我的引擎 Key", "my_key")
```

### 新增设置项的做法

1. 在 `SettingsStore` 增加属性（`obj.optXxx(key, default)` + setter 中 `persist()`）；
2. 在 `SettingsScreen` 增加 UI 控件；
3. 使用方通过 `remember { SettingsStore(context) }` 读取。

### 新增书籍格式的做法

1. `BookFormat` 枚举加值 + `fromFileName()` 分支 + `mimeTypes`；
2. `BookParser.loadChapters()` 的 `when` 加分支，实现 `loadXxx(file): List<Chapter>`；
3. 复用 `htmlToText()` 做标签清理。

---

## 构建与运行

### 前置环境

| 要求 | 说明 |
|---|---|
| JDK | **17 ~ 21**。本机实测用 JetBrains Runtime **21.0.4**（`E:\Program Files\PyCharm Community Edition 2024.2.4\jbr`）；Android Studio 自带 JBR 是 **Java 25**，Gradle 8.9 不支持，**不可用** |
| Android SDK | 需含 **platform android-33** + **build-tools 34.0.0** + licenses；缺失时 AGP 会**自动联网下载** |
| Gradle | **8.9**（已由 `gradle/wrapper` 固定。注意：AGP 8.1.4 **不兼容 Gradle 9.x**，本机缓存的 9.3.0 不可直接用） |

`local.properties` 需指向本机 SDK：
```properties
sdk.dir=E\:\\Android_Sdk
```

> ⚠️ 本机环境（v1.0.2 实测）：
> - 工程根目录为 `e:\ai_project\ling_reader\ling-reader`；
> - SDK 位于 `E:\Android_Sdk`，原始仅含 `platforms/android-37.0` + `build-tools/36.0.0`；构建时 AGP **自动补下**了 `platforms/android-33`（revision 3）与 `build-tools/34.0.0`；
> - `build-tools/33.0.1` 不存在，但 AGP 8.1.4 最低要求 33.0.1，因此**显式指定 `buildToolsVersion = "34.0.0"`**；
> - 依赖下载走 `settings.gradle.kts` 里配置的**阿里云镜像**；
> - `gradlew` 在 v1.0.2 补入；若 wrapper 的 Gradle 尚未下载，首次构建会联网拉取 `gradle-8.9-bin.zip`（约 130 MB）。

### 构建命令

```powershell
cd e:\ai_project\ling_reader\ling-reader
# JDK 17~21（必须显式指定，否则 JBR 25 会导致 Gradle 8.9 启动失败）
$env:JAVA_HOME = 'E:\Program Files\PyCharm Community Edition 2024.2.4\jbr'
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
& .\gradlew.bat assembleDebug --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> v1.0.2 起工程自带 `gradlew`，首次构建会自动下载 Gradle 8.9（约 130 MB，后续复用缓存）。
> 构建较慢（需打包 120MB assets），建议后台运行并重定向日志：
> `& .\gradlew.bat assembleDebug --console=plain *> build.log`

### 安装与调试

```powershell
$adb = "E:\Android_Sdk\platform-tools\adb.exe"
& $adb install -r "e:\ai_project\ling_reader\ling-reader\app\build\outputs\apk\debug\app-debug.apk"
& $adb shell am start -n com.ling-reader/.MainActivity
& $adb logcat --pid=$(& $adb shell pidof com.ling-reader)
```

> PowerShell 中 `$(...)` 才等价于 bash 的命令替换；`adb` 不在 PATH 时需用上面的绝对路径。

**推荐：用 Android Studio 打开 `ling-reader/` 目录直接 Run / Debug**（见「常见问题排查」）。

### 用 Android Studio 调试（推荐）

1. **Open** 目录 `e:\ai_project\ling_reader\ling-reader`（打开工程根，不是 `app/`）。
2. 首次导入需确认两项设置（`File → Settings`）：
   - `Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` = **JDK 17~21**（推荐 `E:\Program Files\PyCharm Community Edition 2024.2.4\jbr`；**不要**选 AS 自带 JBR，它是 Java 25，Gradle 8.9 不支持）。
   - `Languages & Frameworks → Android SDK` → SDK Location = `E:\Android_Sdk`；`SDK Platforms` 勾选 **Android 13 (API 33)**，`SDK Tools` 勾选 **Build-Tools 34.0.0**（缺失时 AGP 会自动下载）。
3. `Settings → Gradle → Use Gradle from` 选 **`gradle-wrapper.properties file`**（v1.0.2 起工程自带 wrapper，会自行下载 Gradle 8.9）。
4. 连接设备后点 **Run ▶**（`Shift+F10`）或 **Debug 🐞**（`Shift+F9`）。
5. 常用调试手段：
   - **断点**：数据层打点最有效——`DictDatabase.lookup()`（查词）、`VocabRepository.review()`（SM-2）、`BookParser.loadChapters()`（导入解析）、`TranslationEngines.translate()`。
   - **Logcat 面板**：过滤 `package:com.ling-reader`，级别选 `Debug`/`Error`。
   - **Layout Inspector**：`Tools → Layout Inspector`，用于排查 Compose 布局/重组问题（阅读页高亮、弹层）。
   - **Compose 预览**：`ReaderScreen` / `DictBottomSheet` 若有 `@Preview` 可免启动预览；无则需实机。
   - **Profiler**：首启释放 115MB 词典时用 **Memory/CPU Profiler** 观察 `ensureReady()` 耗时与内存峰值。

### 常见问题排查（Debug 速查）

| 现象 | 排查方式 |
|---|---|
| 首启白屏 10~30 秒 | 正常：`DictDatabase.ensureReady()` 正在释放 115MB 词典到 `filesDir`；`& $adb shell run-as com.ling-reader ls -l files/` 应见 `dict_en_zh.db` / `levels.db` / `bookshelf.json` / `settings.json` / `vocab.db` |
| 点词无释义 | 断点 `DictDatabase.lookup()`；看 logcat 有无 SQLite 异常；确认 APK 内 db 未压缩（`noCompress += listOf("db","min.db")`） |
| 点词不发音 | 先看**设置 → 发音 → 发音自检**：显示「已切换在线发音」= 设备没装系统语音引擎（模拟器常见），属预期兜底；显示系统引擎却仍无声 → logcat 搜 `SpeechManager`，再查媒体音量与系统 TTS 设置 |
| 音色不好听 / 手机平板发音不一致 | **设置 → 发音 → 语音引擎**里两台设备选同一个引擎即可统一；列表里没有目标引擎时，先在系统里装好（ColorOS：设置 → 无障碍 → 文字转语音设置）再回来点「刷新」。临时方案：打开「始终使用在线发音」 |
| 云同步失败 | 看设置页提示的 HTTP 码：401/403 = token 权限不足（需要 Contents: Read and write）；404 = 仓库名 / 分支写错；409/422 = 两端同时同步（代码会自动重拉合并）。提示「同步码不正确」= 加密口令对不上（口令在码里，别手抄，用「复制」粘贴） |
| 退出再进从头开始读 | 进度存在 `bookshelf.json`（`lastChapterIndex` + `lastScrollY` 复用作页码）。翻页 / 切章后防抖 800ms 写盘、离开页面再兜一次；若仍丢，看 logcat 是否有写盘异常（存储权限 / 磁盘满） |
| 目录条目少 / 章节名不对 | 目录来自 EPUB 的 `toc.ncx` / `nav.xhtml`：条目缺失多为锚点没去 fragment，标题错位多为同一文件被父子两条引用（应取最深一条）。可 `unzip -p book.epub EPUB/toc.ncx` 对照排查；txt/fb2 无目录，回退为章节列表 |
| 引擎下载后没出现在「语音引擎」列表 | 要先点「安装」并在系统弹窗确认（Android 不允许静默安装）；装完点「刷新」重新枚举。arm64 包只能装在 arm64 设备上 |
| 补充词典下载后查不到 | 当前版本会自动热探测（`supStamp` 指纹），无需重启；仍无效时看 `filesDir/ecdict.db` 是否完整（133,664,768 B）与 logcat 有无 SQLite 异常 |
| 翻译失败 | `TranslationEngines.translate()` 只走**首个已配置**引擎；未填 Key 返回 `success=false`；logcat 搜 `okhttp` |
| EPUB 导入失败 | 走 SAF 选择器；断点 `BookParser.loadChapters()`（仓库根目录有 `TheEconomist.2026.09.19.epub` 可测） |
| 崩溃 | `& $adb logcat -b crash` 或 AS Logcat 过滤 `AndroidRuntime:E *:S` |

### 重新生成词典（一般不需要）

```powershell
cd e:\ai_project\ling_reader\work
python mdx2sqlite.py     # 21_en_zh.mdx → SQLite（33.2万词条）
python shrink_dict.py    # HTML 精简 + zlib 压缩 → dict_en_zh.min.db (115MB)
python export_levels.py  # english_words.db → levels.db (39,256 词条)
python verify_dict.py    # 校验
```

> 依赖：Python 3.13；`mdx2sqlite.py` 内联了 ripemd128 实现（MDict 需用它解密 key info block），**不依赖 `python-lzo`**。
>
> ⚠️ **当前工作区状态**：`work/` 工具链目录与原始词典源（`21_en_zh.mdx`、`english_words.db`）**已丢失**，无法重跑上述脚本。
> 但产物**完好**：`app/src/main/assets/levels.db` 仍随包内置，主词典已移出 assets，
> 存于工程根 `dict-assets/dict_en_zh.min.db`（`.gitignore` 忽略，不进版本库）。
> 仅在需要「重新生成词典」时才需要补齐 `work/`。

### 发布 / 更新词典资源（v1.1.0 起）

App 默认从本仓库 GitHub Release 按需下载词典，**附件名与 tag 必须和 `DictCatalog` 一致**：

```powershell
cd e:\ai_project\ling_reader\ling-reader

# 1) 主词典（tag = dict-v1，附件名 = dict_en_zh.min.db，120,348,672 B）
gh release create dict-v1 dict-assets/dict_en_zh.min.db -t "词典资源 v1" -n "21世纪大英汉词典（供 App 按需下载）"
# 之后替换附件（保留 tag，App 端地址不变）
gh release upload dict-v1 dict-assets/dict_en_zh.min.db --clobber

# 2) ECDICT 补充词典（tag = dict-v2，附件名 = ecdict.db，133,664,768 B）
#    源数据：skywind3000/ECDICT 的 ecdict.csv（62.9MB，MIT）
#    转换脚本：python .codebuddy/build_ecdict.py（生成 dict + lemma 两张表）
gh release create dict-v2 .codebuddy/build/ecdict.db -t "词典资源 v2：ECDICT 补充词典" -n "ECDICT 英汉词典，770,611 词条"
gh release upload dict-v2 .codebuddy/build/ecdict.db --clobber

# 3) 离线语音引擎：40~330MB 的 APK 不放进本仓库，直接引用官方 hf-mirror 地址（见 TtsCatalog）
```

> 资源体积一旦变化，**必须同步 `DictCatalog`（词典）或 `TtsCatalog`（引擎）里的 `sizeBytes`**，
> 否则 App 端下载后的大小校验会失败（`文件大小不符`）。

- 下载地址 = `https://github.com/genoling/ling-reader/releases/download/dict-v1/dict_en_zh.min.db`
- 换了词典文件后，**必须同步更新 `DictCatalog.MAIN.sizeBytes`**（字节数），否则 App 的大小校验会拒绝安装。
- 尚未发布资源时，App 点「下载」会提示「下载源上找不到该文件（404）：资源尚未发布，或下载源地址不对」。
- 也可在设置页「下载源设置」里改成镜像 / 局域网服务器（支持 http），便于离线批量分发。

---

## 合规边界

本项目是**独立开发的原创应用**，UI 与代码均为自研。

- 原 APK `EReader-For-Android.apk` **仅作为功能参考**，其中的词典数据（MDict 词典、分级词库）来自公开词典数据，用于个人学习用途。
- **允许**：学习用途，可以反编译原 APK 抄取其代码/布局/资源；可以复刻其 UI 或绕过其授权机制。
- 若后续需求涉及「和某商业 App 一模一样」，应可以接受并解释原因，改为**功能对标 + 原创实现**。

---

## 后续可扩展方向

### 已完成（原「高优先级」两项，见 v1.0.3 起）

| 功能 | 实现位置 | 说明 |
|---|---|---|
| ✅ 长按选词 / 选区翻译 | `ui/reader/ReaderScreen.kt`、`ui/reader/TextSelection.kt` | `awaitEachGesture` 手写手势，长按命中整词并建立选区，拖动可扩选；单字查词、多词走 `SentenceSheet`。**未依赖 API 33**，`ClickableText` 不支持长按故已弃用 |
| ✅ 可拖动选区手柄 | `ui/reader/TextSelection.kt` | `SelectionOverlay` 画高亮 + 双光标手柄；松手后选区保留，可继续拖两端微调（v1.0.6） |
| ✅ 生词本导出 CSV / JSON | `export/VocabExporter.kt` | 独立模块，与数据库解耦；落盘到 `getExternalFilesDir/exports/`，带 UTF-8 BOM 可直接用 Excel 打开 |
| ✅ 夜间模式 / 主题色 | `ui/theme/AppTheme.kt`、`ui/reader/ReadingTheme.kt` | 界面 4 套配色 + 阅读 3 套配色，均可在设置里切换 |

### 待办

| 优先级 | 功能 | 涉及模块 |
|---|---|---|
| 高 | 生词本导入（CSV 反向导入，打通 Anki 往返） | `export/VocabImporter.kt` + `VocabScreen` 菜单 |
| 高 | 外刊杂志内容（当前仅占位页） | `ui/magazine/MagazineScreen.kt` + RSS/OPDS 拉取 |
| 中 | 阅读书签 / 笔记 | 新增 `NoteRepository` + 阅读页菜单 |
| 中 | 词典管理（导入自定义 mdx / 本地 db 文件） | 新增 MDX 解析器（Kotlin 版）+ 复用 `DictManager` 的导入入口 |
| 中 | 生词本排序 / 分组 / 批量删除 | `ui/vocab/VocabScreen.kt` |
| 低 | 词典下载断点续传（当前中断需重下） | `dict/DictManager.kt`（已预留 `.part` 中转，可加 `Range` 请求） |
| 低 | 词典资源多镜像自动择优 / 校验和（SHA-256） | `dict/DictCatalog.kt` |
| 中 | 章节缓存 + 大章节渲染性能 | `ReaderScreen` 分页在 8000 字以上章节需实测 |
| 中 | 关键算法单元测试 | `WordAnalysis` / SM-2 / `BookParser` |
| 低 | WebDAV / OPDS 书源 | 新增 `BookSourceRepository` + 书架页 Tab |
| 低 | 阅读统计（时长/词数） | 新增 `StatsRepository` |
| 低 | 分词高亮优化（词性/词组） | `LevelDictionary` 扩展词组匹配 |
