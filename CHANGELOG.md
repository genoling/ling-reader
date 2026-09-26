# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与 [语义化版本](https://semver.org/lang/zh-CN/)。
版本号对应 `app/build.gradle.kts` 中的 `versionName` / `versionCode`。

## [1.6.3] - 2026-09-26

**修好跨章跳页 / 末尾裁字 / 跨页句子翻译；生词本可按来源书、分级词库分类。**

**修复**
- **上一章末页左滑直接落到下一章末页**：`restorePercent`（跨设备进度百分比）只在「首次进书」设置一次，
  跨章时没作废；跨章把 `pageIndex` 置 0 又会命中百分比定位，用旧百分比把新章定位到「百分比页」
  （上一章末页 `lastPercent≈1` → 新章直接到末页）。改为「定位一次即作废」（`onPageChanged` 里置 -1）。
- **第 1 页进度进入时偏移一页**：`lastScrollY` 复用了两种语义 —— 本机页码与「无本机页码」，
  云同步合并写 `0` 与「第 1 页」的合法 `0` 撞车 → 第 1 页被当成「无进度」触发百分比定位。
  引入 `-1` 哨兵（`SyncManager` 合并写 -1、恢复判断改 `< 0`）。
- **行距 26 时文章末尾有句子看不到**：分页用「第一行高度 × 行数」估算块高，而首行带额外 leading、
  比平均行高矮（26sp 时 52~54px vs 57.6px），误差在页内累积把末尾几行挤出屏幕。改用整块平均行高估算 +
  真实的「起始行顶 → 结束行底」高度累加。
- **查词弹层点「翻译」会「刷新整个详情、回到头」**：译文插入让内容变高，`ModalBottomSheet` 重排回
  「部分展开」。改用 `skipPartiallyExpanded = true` 保持完全展开，翻译完成后再 `expand()` + 滚到译文。

**新增**
- **生词本分类**：列表页可按「来源书 / 分级词库（CET4、IELTS、TOEFL…）」分组查看，分组标题带词数。
- **跨页句子也能整句翻译**：长按翻译改用**整章文本**取句（`PagedReader.fullText` + 块偏移），
  被页边界切成两页的句子会补全。

**校验**
- MuMu（行距 26）：第 12 章末页左滑 → 下一章 **1/3**（不再跳末页）；最后一页末尾句
  「This article was downloaded by zlibrary from h…」可见。
- 块结构日志：`p0.b5@136+288` 末尾「…or mending broken 」→ `p1.b0@424` 连续；长按取到完整句
  （跨页的 "mending broken bones" 被拼回）。
- 生词本：按来源书 → 「TheEconomist.2026.09.26 (13)」；按等级 → 「CET4 (1)」「CET6 (1)」「GAOZHONG (9)」…

## [1.6.2] - 2026-09-26

**翻页像翻书一样跨章连读；下载自动挑最快的源、速度看得见；正文排版回到书籍观感。**

**新增**
- **跨章连续阅读**：章末左滑直接进入下一章第 1 页；章首右滑回到**上一章的最后一页**
  （`openAtLastPage`）。分页是异步的，所以回退时**首帧就按末页渲染**
  （`renderIdx = if (pendingEnd) pages.lastIndex else idx`），不会再「先闪一下上一章开头、
  再跳到末尾」；`LaunchedEffect(idx)` 也加了 `if (!pendingEnd)` 守卫，页码 / 进度不会先跳成第 1 页。
- **下载自动挑最快的源**（`data/DownloadSource.kt`）：GitHub Release 直链 + 3 个 gh 代理镜像
  并发试拉 256 KB 测速，按实测速度降序使用、失败依次回退（同一时刻实测：直连 131 KB/s、
  ghproxy.net 89 KB/s、gh-proxy.com 234 KB/s、ghfast.top 231 KB/s —— 写死顺序必然踩坑）。
  词典 / 语音引擎 / 应用更新共用；用户自定义下载源不展开镜像。
- **下载速度实时可见**：状态栏进度通知与设置页「正在下载」都显示实时速度（`1.2 MB/s`），
  一眼分得清是「源慢」还是「卡住了」。

**修复**
- **加了生词却不标红**：`vocab.db` 的 `word` 存词典**原形**（`walked → walk`），高亮与「已收藏」
  却按正文原样词比对，正文里的变形词既不变红、再点还显示「已收藏」。新增 `form` 列
  （逗号分隔的正文词形，`DB_VERSION` → 5），高亮集合 = 原形 ∪ 词形（统一小写）。
  ⚠️ 因此**不能**再用 `vocabWords.size` 当生词数量（会偏大），数量单独用 `vocabCount`。
- **段间距取消**：1.6.1 让每个块之间硬留一行（`PARA_GAP_RATIO`），栏目行 / 标题 / 导语上下都被拉开，
  一页行数明显变少、标题看着被孤立。现在 `paginateFlow()` 不再累加 `gapPx`，渲染侧也去掉 `Spacer`；
  段落之间**紧接**（原文自己的空行由 `parseBlocks()` 切成块），不再额外留白。
- **首行缩进保留**：正文 / 引文段首缩进 2 字符（`INDENT_CHARS`）。缩进只挂
  `ParagraphStyle(TextIndent)`、不增删字符，所以点词查词的 offset 不受影响；测量侧 `displayText()`
  与渲染侧 `WordText(indentFirstLine=…)` 同源，否则分页会与实际排版错位。
- 栏目行 / 标题 / 导语的分级照旧（`WordText` + `blockStyle`）：H1 1.55x、H2 1.25x、H3 1.10x 加粗，
  栏目与引用用次要色；行距仍是阅读面板里的「行距」（默认 28、范围 20~44）。

**已知差异（排版引擎能力，非本版可修）**
- 正文**不两端对齐、不会自动断词**（参考图里的 `hav-ing` / `es-pecially` 那种）。Compose `Text`
  （1.4.3 锁版）没有 justification / hyphenation API；要做到这一步得把正文换成 WebView 排版，
  属大改，暂不做。

**校验**
- 装 debug 包读《The Economist 2026.09.26》（与用户截图同一期、同一篇）：
  - 第 9 章《When America walks away》、第 10 章《Why house prices may be in trouble》，
    页面结构「栏目行 → 标题 → 导语 → 插图 → 日期 → 正文」；
  - 栏目行与日期（`<p>` / `<span>` → 正文块）首行缩进 2 字符、继续行顶格；标题不加缩进；
    正文段首缩进、段与段之间**没有**空行（与参考截图一致）。
- 截图存 `.codebuddy/shot3.png`（walk away）、`.codebuddy/shot4.png`（house prices）。
- 跨章翻页（MuMu，第 10 章起）：10/98 章 1/4 右滑 → **9/98 章 4/4 一次到位**；再右滑 → 3/4；
  左滑 3 次 → 4/4 → 10/98 章 1/4 → 2/4（跨章与章内都仍是每次一页）。

## [1.6.1] - 2026-09-25

**修复「下载并安装」失败；下载变成「看得见、能取消、不留垃圾」。**

**修复**
- **更新安装失败**：点「下载并安装」报 `Failed to find configured root that contains
  /data/data/com.lreader/cache/update-x.y.z.apk` —— `FileProvider` 的 `res/xml/file_paths.xml`
  只放行了 `filesDir`（语音引擎用），而更新包下在 `cacheDir`，`getUriForFile` 找不到对应 root。
  补 `<cache-path name="cache" path="." />` 后安装界面正常拉起。
- **取消更新下载无效**：请求还没建立连接（`activeCall` 尚为空）时点取消，之后仍会继续下完。
  改为在下载循环里每轮检查一次 `cancelled` 标志，已建立连接时由 `Call.cancel()` 提前打断。
- **正文排版**：段落之间空出一行，正文 / 引文的**首行缩进两个字符**。
  （`paginateFlow` 新增 `gapPx` 参与分页、渲染侧用 `Spacer` 还原；测量与渲染共用
  `displayText()` —— 缩进会改变每段行数，两处必须同源。）⚠️ **该项已在 [1.6.2] 回退**：
  实机观感与旧版差异明显（标题被拉开、每页行数变少）；`parseBlocks()` 的 `\r` 处理也一并回退。

**新增**
- **状态栏下载进度通知**：词典 / 语音引擎 / 应用更新下载时在状态栏显示进度，通知里可一键取消
  （`data/DownloadNotifier.kt` 发通知、`data/DownloadCancelReceiver.kt` 接取消广播、
  `data/DownloadCenter.kt` 把「怎么取消我」登记给通知）。Android 13+ 首次下载前申请通知权限，
  **拒绝也照常下载**，只是看不到进度。
- 设置页「正在下载…」右侧也加了「取消」（应用更新），不用专门去状态栏。
- **更新包与词典一致：校验通过才落盘** —— 先写 `.part`，字节数与 Release 声明一致 + APK(ZIP) 头
  都通过才 rename；半包 / 被劫持的文件直接删掉并换镜像重试。
- **启动时清掉没下完的临时文件**（`data/DownloadCleaner.kt`）：进程被杀 / 崩溃后残留的 `.part`
  会在下次启动时删除，不再白占几十 MB。

**校验**
- 模拟器实测：临时构建 v1.5.9 → 检查更新 → 发现 v1.6.0 → 「下载并安装」→ 系统「要更新此应用吗？」
  弹窗正常出现（修复前此处报 `Failed to find configured root`）；`cacheDir` 中得到
  15,157,360 字节的 `update-v1.6.0.apk`（与 Release 声明一致）；下载期间 `dumpsys notification`
  可见 `channel=downloads` 的通知，且带 1 个「取消」action。
- 排版实测（32 段长测试书 / 6 页，逐页抓原始帧量像素）：第 1~6 页每段首行都缩进 76 px（= 2 字符）、
  段间距 ≈110 px（≈ 1 行，行内 63~75 px）、跨页续排行不缩进；段落序列连续无丢字
  （1→01-06、2→07-12、…、6→31-32），页末压边界不溢出。⚠️ 该项排版已在 [1.6.2] 回退。
- `assembleDebug` 零警告；`versionCode` 18 → **19**，`versionName` `1.6.0` → **`1.6.1`**。

---

## [1.6.0] - 2026-09-25

**云同步改为「一键生成」：仓库与令牌内置，不用再填任何内容。**

**新增**
- 设置 → 云同步 → **「一键生成」**：点一下直接得到同步码并立即同步 —— 仓库
  （`genoling/ling-reader-sync`）、分支与令牌全部预置（见 `sync/SyncDefaults.kt`），
  用户不再需要填 GitHub 用户名 / 仓库名 / token。
- 原对话框保留为 **「自定义」**：想用自己的仓库或 token 时打开改即可（已预填内置默认值）。

**实现**
- 令牌由构建时注入：`app/build.gradle.kts` 读 `local.properties` 的 `sync.token`
  （或环境变量 `LR_SYNC_TOKEN`）→ `resValue("string", "sync_token")` → `SyncDefaults.token(context)`。
  `local.properties` 在 `.gitignore` 中，**令牌不进版本库**（本仓库是公开仓库，提交进去会被 GitHub 判定泄露并吊销）。
- 用 `resValue` 而非 `buildConfigField`：本机 JBR 21.0.4 不带 `jlink`，开启 `buildConfig` 会让 AGP
  走 javac + `JdkImageTransform` 而构建失败（`jlink executable ... does not exist`，详见 `docs/development.md`）。
- 未注入令牌的构建（他人 clone / CI）自动退化为原来的手填对话框，功能不受影响。
- ⚠️ 令牌终究在 APK 里：拿到包的人可读写该私有仓库（能删，读到的只有密文）。
  故内嵌的是**专门为此创建的 fine-grained PAT**：Repository access = Only select repositories
  只勾 `ling-reader-sync`，权限仅 `Contents: Read and write`，其它权限一律 `No access`
  （实测该令牌枚举私有仓库只可见 `ling-reader-sync`，且读不到其它仓库的设置接口）。泄露随时可吊销。

**校验**
- 模拟器实测：设置 → 云同步 → 点「一键生成」→ 显示「已配置：genoling/ling-reader-sync」「目录 users/…」+
  「同步完成」；同一令牌独立调 GitHub Contents API PUT→DELETE 成功，云端 `users/` 下确认出现该目录。
- 发布前用**内置的正式 fine-grained token**复核：`/user/repos` 仅可见 `ling-reader-sync` 一个私有仓库
  （其余为公开仓库的只读访问，fine-grained token 固有行为）→ Contents `PUT` / `DELETE` 均成功，
  `assembleDebug` 产物 `resources.arsc` 中 `sync_token` 已为新令牌。
- `assembleDebug` 零警告；`versionCode` 17 → **18**，`versionName` `1.5.2` → **`1.6.0`**。

---

## [1.5.2] - 2026-09-25

**改用正式签名（release keystore）发布：身份固定、可上架，此后所有版本都能直接覆盖安装。**

- `app/build.gradle.kts` 新增 `signingConfigs.release`：密钥读自项目根 `keystore.properties`
  （`keystore/lingreader-release.jks`，两者都在 `.gitignore`，**绝不进版本库**）；
  配置缺失时自动回退 debug 签名，他人 clone 也能正常 `assembleRelease`。
- 发布包改用 `./gradlew assembleRelease`：`app-release.apk` 约 **14.5 MB**（比 debug 包小约 6 MB）。
- 证书指纹（SHA-256）：`7cc79b65556c6d4a638252085e37a8b0867ab652e7c62f58b924195eb6d575b6`。
- ⚠️ **签名变更：首次升级必须先卸载旧版再安装**（Android 要求同包名 + 同签名才能覆盖）。
  卸载会清空本机数据 —— 建议先在旧版「生词本 → 导出 CSV」备份，或确认已开启云同步；
  新版装好后填入**同一个同步码**即可恢复生词本与阅读进度（书籍需重新导入、词典需重新下载）。
- 实测：卸载旧版 → 安装正式签名包 → 启动即自动同步（默认开启）→ 生词本 11 词完整恢复。
- `assembleDebug` 零警告；`versionCode` 16 → **17**，`versionName` `1.5.1` → **`1.5.2`**。

---

## [1.5.1] - 2026-09-25

**设置页改为「折叠分组」：一屏看全所有设置，点开才展开细节。**

- 分组标题行右侧直接显示当前值（如「发音 · 英音 · 语速 0.9」「本地词典 · 主词典 332210 词条」
  「界面配色 · 松绿」），点击展开 / 收起；默认只展开最常用的「阅读显示」。
- 原先 9 个分组（界面配色、云同步、本地词典、发音、阅读显示、分级高亮、翻译引擎、高级、关于）
  全部平铺，要滑好几屏才到底；现在首屏就能看全所有分组，要找什么点开哪一项。
- 配置同步码（粘贴或「生成」）后**自动打开**「进入 App 时自动同步」—— 多设备之间保持一致本就是默认预期。
- 仅界面与开关默认值调整，不改任何设置项的键名与存储格式。
- `assembleDebug` 零警告；`versionCode` 15 → **16**，`versionName` `1.5.0` → **`1.5.1`**。

---

## [1.5.0] - 2026-09-25

**新增云同步：生词本与阅读进度跨设备同步到自己的 GitHub 私有仓库，内容端到端加密。**

**新增**
- **生词本加密云同步**：设置 → 云同步填一个「同步码」即可在手机 / 平板之间双向同步（手动按钮 + 启动自动同步）。
  云端只存密文（AES-256-GCM，密钥由同步码里的口令经 PBKDF2 12 万轮派生），**仓库主人也读不出内容**。
  同步码是一个字符串，打包了仓库、token、用户目录与口令 —— 第二台设备粘贴即完成配对，不需要记任何密码。
- **阅读进度同步**：改存「章节号 + 章内百分比」。页码依赖字号与屏幕，跨设备没有意义；
  从别的设备同步来的进度会按百分比定位到对应位置（本机仍用精确页码）。
- **自动同步**：进入 App 时静默同步一次，失败只在设置页提示，不打断阅读。
- **同步健壮性**：生词新增 `uid`（跨设备主键）/ `updated_at` / `deleted`（软删除）三列 ——
  冲突按时间取新、删除能传播（否则 A 设备删掉的词会被 B 设备重新同步回来）；
  上传带 `sha` 乐观锁，两端同时同步会自动重拉合并。CSV 导出同步追加这三列（Excel 仍可直接打开），
  旧版 CSV 导入时按 `word+sentence` 补算稳定 uid，不会重复成两条。
- **管理员面板**：设置 → 高级 → 管理员模式（密码 `8848`）——
  可查看**本机各数据库 / 目录**（生词本、书架、设置、书籍、封面、插图缓存、词典）的体积并逐个删除，
  也能列出同步仓库里 `users/` 下的**所有用户**并删除某位用户的云端数据（云端只存密文，看不到任何内容）；
  所有删除都有二次确认。
- 老数据平滑升级：数据库自动补 `uid`（UUID）与新列，**生词与复习进度都不丢**。

**校验**
- 模拟器 + 真实私有仓库实测：首次上传 11 条 → 用独立实现解密验证（还原出完整 CSV，含新三列）→
  删库模拟新设备（恢复 11 条）→ 进度清零后同步（恢复为第 14 章 · 百分比定位）→
  启动自动同步（日志「自动同步完成」）。
- `assembleDebug` 零警告；`versionCode` 14 → **15**，`versionName` `1.4.0` → **`1.5.0`**。

---

## [1.4.0] - 2026-09-25

**修复阅读进度丢失与插图排版，新增章节目录。**

**新增**
- 阅读页新增**章节目录**：解析 EPUB 自带的 `toc.ncx` / `nav.xhtml`（含父子层级），
  顶栏「目录」按钮弹出列表、点击跳章、当前章高亮。条目用目录里的完整标题
  ——电子书自带的 `<title>` 常被截断（如 `The selfish case for helping Ukraine has`）。
  txt / fb2 没有目录信息时回退成章节列表。
- **插图内联排版（图文混排）**：EPUB 插图不再独占一页，而是接在原文位置
  （标题 → 导语 → 图片 → 正文），分页按像素高度把图与文字装进同一页。
  封面这类「整章只有一张图」的页面放宽到页高 94%，正文插图限高 62% 以免把整段文字挤到下一页。
- **正文分级排版**：解析 EPUB 的 `h1 / h2 / h3 / h4~h6 / blockquote` 层级
  （`BookParser.MARK_*` 控制字符标记，不改变纯文本兼容性），
  标题按层级放大加粗（1.55x / 1.25x / 1.10x）、栏目与引用用次要色、正文保持常规字号
  —— 不再「标题和正文一个大小」。分页时每块按**自己的行高**累加占位，标题行更占地方不会溢出。
- **书内目录页 / 栏目页的列表排版**：`<li>` 每项独占一行并带项目符号，之前会连成一行
  （如 `When America walks awayWhy house prices may be in troubleBrazil…`）。
- **书内超链接可点击跳章**：`<a href>` 解析成目标文件路径（去 `#anchor`），
  目录页 / 栏目页的链接整行可点（主色 + 下划线），点击直接跳到对应章节；外部链接仍是普通文字。

**修复**
- **阅读进度不再丢失**：此前退出只恢复「章号」，章内页码永远从第 1 页开始，
  且只在按返回键时保存、直接杀进程就丢。现在打开书即恢复页码，翻页 / 切章后
  自动保存（防抖 800ms），离开阅读页时再兜一次。
- **章节标题错位**：同一章节文件常被目录里的「栏目」与「文章」两条同时引用
  （如 `Leaders` 与 `When America walks away`），此前取到的是栏目名，现取最深的一条。
- **目录缺条目**：目录里的锚点（`xxx.html#politics`）此前没去掉 fragment，
  导致部分文章匹配不上而丢失。
- **封面章只剩一个单词 "Cover"**：HTML 的 `<head>`（含 `<title>`）此前被当正文提取，
  现整段剥离；广告页首行也不再显示文件名。
- 正文文字块不再强制 `fillMaxSize`（否则会把同页的插图挤出屏幕）。

**校验**
- 模拟器实测：重启 App 后仍停在第 5 章第 2 页；目录完整（含 Politics / When America walks away）；
  第 9 章第 1 页为「标题 + 导语 + 插图」同页；封面章 1 页且图片铺满。
- `assembleDebug` 零警告通过；`versionCode` 13 → **14**，`versionName` `1.3.0` → **`1.4.0`**。

---

## [1.3.0] - 2026-09-25

**书架封面网格与拖动排序、外刊杂志下载、EPUB 插图、生词详情译文、每日复习刷新、设置页版本更新。**

**新增**
- 书架改为**封面网格**：自动提取 EPUB 封面（EPUB2 `meta cover` / EPUB3 `properties` / 文件名兜底），列数自适应，支持书名搜索。
- 书架**长按拖动排序**，松手即写入 `bookshelf.json`；长按原地抬手弹出「打开 / 删除」菜单。
- **外刊杂志**：浏览 `hehonghui/awesome-english-ebooks` 的 216 期外刊（The Economist / The New Yorker /
  The Atlantic / Wired），下载后自动入书架并提取封面。目录走 jsDelivr data API → GitHub API → 本地缓存，
  下载走 jsDelivr CDN → raw → ghproxy 三级重试。
- **EPUB 插图**：解析 `<img src>` / `<image xlink:href>`，按正文位置插入**插图页**（整页等比显示）；
  图片缓存在 `books/.images/<书名>/`，删书时一并清理。
- **生词详情**：点生词进入详情（完整释义 + 原句）并**自动朗读一次**；原句下方**直接显示译文**（进入时自动翻译）。
- 查词弹层「加入生词本」改为**开关**：已收藏时再点一次即取消。
- 设置页底部新增**关于**：显示当前版本号，并支持**一键检查更新 → 下载并安装**
  （GitHub Release API + ghproxy 镜像兜底，下载后交系统安装器，用户确认后安装）。
- 复习页改为**每日刷新**：只有「当天还没背过」的词进入队列，背过即消失，次日 0 点自动全部重现；
  背单词**默认乱序**（开关不再暴露在界面上）。

**修复**
- EPUB 正文残留 `class="te_head_image"/>` 等属性碎片：img 正则未匹配结尾 `>`，导致标签被吃掉一半；
  现已匹配完整标签并兼容单引号，另加「整行只剩属性碎片时删除该行」的兜底。
- 复习队列为空时不再退回「全部生词」（会破坏「每日刷新」的语义）。

**数据**
- `vocab.db` 新增 `last_review_day` 列（`DB_VERSION` 2 → 3，仍是非破坏性 `ALTER TABLE` 补列）。

**校验**
- `assembleDebug` 零警告；`versionCode` 12 → **13**，`versionName` `1.2.0` → **`1.3.0`**。
- 设备自测（MuMu / Android 12）：封面提取、拖动排序（重启后保持）、外刊下载入书架、插图页显示、
  生词详情译文、生词加入/取消、每日队列递减（10 → 9）、设置页版本号与「已是最新版本」。

---

## [1.2.0] - 2026-09-25

**新增：ECDICT 补充词典（77 万词条）、离线语音引擎一键下载安装、发音引擎可切换；修复查词弹层长词条被截断、派生词查不到。**

**新增**
- 设置页「发音」新增**语音引擎**列表：列出设备上已安装的 TTS 引擎（显示名称 + 包名 + 「系统默认」标记），
  选中即试听并持久化，切换后自动重新绑定引擎（旧引擎 shutdown → 新引擎 init）。
- 新增**「始终使用在线发音」**开关：跳过系统引擎统一走在线发音（有道），
  适合设备没装引擎、或想让两台设备音色完全一致的用户（代价是需联网）。
- `SettingsStore` 新增 `ttsEngine` / `preferOnlineSpeech`；`SpeechManager` 新增
  `enginePackage` / `preferOnline` / `engineItems()` / `engineLabel()`，由 `LRreaderApp` 在启动时套用。
- 引擎枚举改用「`Settings.Secure.tts_default_synth` + `queryIntentServices(TTS_SERVICE)`」实现，
  不依赖 `TextToSpeech.getEngines()`（该 API 需要先持有实例，且在部分 SDK 上取不到）。

**新增 · 词典**
- **ECDICT 补充词典**（Release `dict-v2`，127.5 MB / 770,611 词条，99.8% 带中文释义，MIT）：
  由 `ecdict.csv` 转换而来，表结构与主词典一致，另带 **95,424 条「变形 → 原形」表**（`lemma`）。
  查询顺序改为 **主词典 → 补充词典**：主词典查不到（含词形还原失败）时自动回退，
  于是 `Abkhazia` 这类专名与大量派生词不再显示「未收录」。
  它与主词典一样按需下载，复用 `DictManager` 的下载 / 校验 / 删除机制，**安装包体积不变**。
- 补充词典文件变动（刚下载完 / 刚被删除）会被**热探测**（长度 xor 修改时间指纹）识别，**无需重启 App 即生效**。

**新增 · 发音**
- 设置页「发音」新增**安装离线语音引擎**：4 档开源引擎（sherpa-onnx 官方 APK，Apache-2.0，一包一模型）
  从 hf-mirror 一键下载 → 交系统安装器安装，音质远好于多数系统自带引擎。
  下载复用 `DictManager`（新增 `register()`，支持词典之外的资源），安装走 `FileProvider` + `ACTION_VIEW`
  + `REQUEST_INSTALL_PACKAGES`（Android 不允许静默安装，需用户在系统弹窗确认）。

**修复**
- **查词弹层长词条被截断**：`conversion` 这类多义项词条只能看到前两行，且**无法滑到被截掉的部分**。
  根因是 `DictHtmlView` 的 WebView 自身不接收滚动（手势交给外层弹层），而高度只在 `onPageFinished` 后测一次；
  排版与字体异步完成时量出的 `contentHeight` 偏小，超出部分既看不到也滑不到。
  现改为**高度只增不减**：在 0 / 120 / 360 / 800ms 分档补测 `contentHeight`，
  并注入 JS 读取 DOM 真实高度兜底，另加 10px 余量。
- **派生词查不到**：词典只收了 `receptive`、没收 `receptiveness`，点词得到「未收录该单词」。
  `DictDatabase.resolveKey` 的还原规则从 5 条扩展到**屈折 + 派生两层**
  （新增 -ness / -ment / -tion / -sion / -ance / -ence / -ity / -able / -ive / -ous / -ism / -ist / -ize / -ful / -less …
  以及 -ably→-able、-ibly→-ible、-ly→-le、-ves→-f/-fe 等），
  24 个真实外刊派生词回归：**23/23 全部命中**（改前 19/23）。
- 命中词根时弹层新增一行提示「未收录「X」，以下为词根「Y」的释义」（`DictEntry.formOf`），
  避免用户以为点错了词。
- 词条正文**完全没有中文**时（如 `vt. subdue的变形`、化学名词 `= 1-octene`），
  追加一行说明，不再只显示一行词性。

**校验（设备自测，MuMu / Android 12）**
- 词典：设置页正确显示三档资源；`Abkhazia` 经补充词典出中文释义；`receptiveness` 提示词根并给出 `receptive` 释义；
  `conversion` 10 个义项全部可见且能滑到底（含【橄榄球】【精神病学】【计算机】）。
- 补充词典：删除 → 点下载 → 20 秒完成 → **无需重启即显示「已安装 · 127.5 MB · 770611 词条」**。
- 引擎：4 档列表与体积正确；「下载」48.6 MB 成功；「安装」成功拉起系统安装器（"要安装此应用吗？"）。
- 23 个派生词回归 23/23；`assembleDebug` 零警告；
  `versionCode` 11 → **12**，`versionName` `1.1.0` → **`1.2.0`**；APK 19.1 MB。

---

## [1.1.0] - 2026-09-23

**架构改造：本地词典框架重构 —— 主词典改为「按需下载」，安装包 133.6MB → 19.1MB。**

**新增**
- `dict/DictCatalog.kt`：词典资源清单（资源描述 + 默认下载源，指向 `github.com/genoling/ling-reader` 的 Release `dict-v1`）。
- `dict/DictManager.kt`：进程级资源管理单例 —— 内置释放 / 下载（进度·取消）/ 校验 / 删除，状态经 `onChanged` 回主线程通知 UI。
- 设置页「本地词典」重做为**资源管理区块**：每个资源显示名称/说明/体积/状态，
  支持 下载（带百分比进度条）、取消、删除、显示真实下载地址、**自定义下载源**（镜像 / 自建服务器）。
- `res/xml/network_security_config.xml`：放行明文 HTTP，使国内镜像与局域网源可用（下载结果有大小 + SQLite 头校验兜底）。

**修改**
- **主词典移出 APK**：`app/src/main/assets/dict_en_zh.min.db` → 工程根 `dict-assets/dict_en_zh.min.db`（`.gitignore` 忽略），
  需上传到 GitHub Release 供 App 下载（步骤见 §7.7）。APK 实测 `20,171,695 B ≈ 19.2 MB`。
- `DictDatabase.ensureReady()` 只打开已下载的库，**不再拷贝 assets、不联网**；文件被删除时释放旧句柄后可重新下载。
- `LevelDictionary` 改由 `DictManager` 释放内置资源（统一走 `.part` 中转，失败不留半个库）。
- `ReaderScreen`：词典未下载不再阻塞进入正文，改为底部常驻提示条；`DictBottomSheet` 新增 `dictInstalled` 参数，
  明确区分「本地词典未下载」与「未收录该单词」。
- `SettingsStore` 新增 `dictSourceBase`（下载源前缀，默认空 = 用 `DictCatalog.DEFAULT_SOURCE`）。
- `versionCode` 10 → **11**，`versionName` `1.0.10` → **`1.1.0`**。

**兼容性（老用户升级零成本）**
- 落盘文件名仍是 `filesDir/dict_en_zh.db`，与 v1.0.x 完全一致 —— 升级后 `DictManager` 直接识别为「已安装」，
  **不会重新下载**（已实测：升级安装后设置页显示「已安装 · 114.8 MB · 332210 词条」）。
- 分级词库仍随包内置，阅读高亮不依赖任何下载。
- 真机实测（MuMu / Android 12）：未下载 → 点下载（本地 http 源）→ 进度 30% → 已安装 114.8 MB · 332210 词条 → 点词正常出释义；
  删除词典后阅读页与弹层均正确提示「去设置下载」。

---

## [1.0.10] - 2026-09-23

**修复：变形词（副词 `simultaneously` / `gradually` / `unusually` 等）只有词性、看不到中文意思。**

**根因（两个问题叠加）**
- 21 世纪大英汉词典对变形词只收录指向性说明，`simultaneously` 的词条正文就是
  `<span class="pos">adv.</span><span class="def">simultaneous的变形</span>`，**本身没有中文释义**。
  `DictDatabase.enrichFormEntry` 虽然会补源词释义，但当时是「原文 + 分割线 + 说明 + 源词释义」
  的顺序，于是弹层第一屏是 `adv.` + `simultaneous的变形`，中文释义被压到下面。
- `ReaderScreen` 加入生词本时写的是 `stripHtml(html).take(500)`（**整段去标签 + 截断 500 字**），
  存进去的是 `simultaneously [,siməl'teiniəs] adv. simultaneous的变形 该词为 simultaneous 的变形，
  中文释义参考 simultaneous（adj.） ： adj. 同时发生…` —— 真正的中文排在第 60 多个字之后，
  而 `VocabRow` 是 `maxLines = 2`，**列表里只剩「副词」标签和一句「某某的变形」**。

**修改**
- `enrichFormEntry` 重构：只保留标题（单词 + 音标）与**源词释义**，并把源词的词性标签
  **换成变形词自己的词性**（`adj.` → `adv.`），于是呈现为「`adv.` + 中文释义」；
  源词说明缩成一行小字放在**末尾**。
- `ReaderScreen` 写库改用 `WordAnalysis.meaningText(html)`（「词性 + 中文释义」一行式，
  自动过滤交叉引用），空时才回退 `stripHtml().take(500)`，顺手修掉 500 字截断。
- `WordAnalysis.cleanMeaning` 增加第二轮清洗：剥掉 `xxx的变形` 与
  `该词为…参考…：` 这类说明（说明后面往往还跟着一个词性），保证列表第一眼就是中文。
- `VocabScreen` 的旧数据回填条件由「释义没有中文」放宽为**「没有中文 或 夹着变形说明」**
  （新增 `WordAnalysis.hasFormNote`），已存的脏释义会被重写成「词性 + 源词中文释义」。
- `versionCode` 9 → **10**，`versionName` `1.0.9` → **`1.0.10`**

---

## [1.0.9] - 2026-09-23

**修复：点喇叭 / 点词 / 背单词全部不发音。**

**根因**
- 设备（MuMu 模拟器）**没有安装任何 TTS 引擎**：`settings get secure tts_default_synth` 返回 `null`、
  `pm list packages` 里没有任何 TTS 包，logcat 反复输出
  `E/SpeechManager: TTS 初始化失败, status=-1` 与 `W/TextToSpeech: stop failed: not bound to TTS engine`。
  三个场景（喇叭 / 首次点词 / 背单词）全都没声音，是同一个原因。
- 而代码把「引擎不可用」只写进 logcat 就结束了 —— UI 上没有任何提示，用户只能看到「点了没声音」。
- 另有三个放大问题的实现缺陷：① 每个页面 `remember { SpeechManager(context) }` 各持一份引擎，
  还在 `onDispose` 里 `shutdown()`；② 阅读页 `speech.init()` 排在 `dict.ensureReady()`
  （首次解压 115MB 词典）之后，第一次点词时引擎还没就绪；③ 初始化失败后**每次点击都重新
  new 一个 TextToSpeech 再失败一遍**（日志刷屏）。

**修改**
- `SpeechManager` 改为**进程级单例** `SpeechManager.get(context)`；`LRreaderApp.onCreate()` 启动即
  初始化（并套用设置里的口音 / 语速）；四个页面不再 `shutdown()`。
- 失败处理：记录原因 + `RETRY_COOLDOWN_MS`(15s) 冷却 + 回调 `onStatusChange` / `onOnlineError`，不再静默。
- **新增在线发音兜底**：系统引擎不可用时改用有道 `dictvoice`（免费、免 Key）下载 mp3 并用
  `MediaPlayer` 播放，缓存到 `cacheDir/tts_cache/`；`speak()` 返回 ERROR 也会转兜底。
- 去掉每次 `speak()` 前的无脑 `engine.stop()`（未绑定时刷 `not bound to TTS engine`，且可能打断刚排上的语音）。
- 待播队列从「FIFO 全部读完」改为**只保留最后一个词**，连续点词不再连读。
- 设置页发音分组新增**发音自检**：当前发声通道（系统引擎 / 在线兜底）、失败原因、
  「打开系统语音设置」跳转。
- 顺带：生词本词性 chip 与复习页词性标签由英文缩略改为中文（`adv.` → `副词`，新增
  `WordAnalysis.posLabel()`；`短语:` 等已是中文的原样保留）。
- `versionCode` 8 → **9**，`versionName` `1.0.8` → **`1.0.9`**

---

## [1.0.8] - 2026-09-23

**分级高亮：「忽略基础词」从「仅高中」扩展到「高中 / 四级 / 六级」。**

**修改**
- `LevelDictionary`：`BASIC_LEVEL`（只有高中）→ `BASIC_LEVELS`（高中 / 四级 / 六级）；
  `matchLevel` 改为「词带基础档标记、且该档未被勾选 → 不高亮」。
- **仅当用户勾了非基础档级别时才生效**：否则「只勾高中」会连高中词表自己收录的词一起滤掉
  （实测正文高亮 59.5% → 1.05%、词种只剩 5%），所以勾高中 / 四级 / 六级时该开关不生效。
- 设置页与阅读页开关文案同步为「忽略基础词（高中 / 四级 / 六级）」并补了说明。

**起因（词库数据问题，非程序 bug）**
- 雅思词表按「考雅思要掌握的词汇」收录，`levels.db` 里雅思 3427 词有 **82.7% 与更基础的级别重叠**
  （四级 42.4%、六级 32.6%、考研 64.5%、高中 25.3%），「纯雅思词」只有 175 个（5.1%）。
  只勾雅思时正文染出来的全是 `economic` / `unlikely` / `global` / `crisis` 这类四六级词。
- 各档过滤实测（TheEconomist 6.3MB、68,557 词次、只勾雅思）：

| 策略 | 高亮词次 | 占比 | 词种 |
|---|---|---|---|
| 旧（只滤高中） | 2099 | 3.06% | 785 |
| 只滤高中+四级 | 1026 | 1.50% | 442 |
| **高中+四级+六级（本次采用）** | **301** | **0.44%** | **144** |
| 只留「纯雅思词」 | 35 | 0.05% | 21 |

- 本次采用「滤到六级」：剩下的是 `dearth` / `delirium` / `dormant` / `asylum` / `lucrative` /
  `volatile` / `imminent` 这类真难词；「只留纯雅思词」对雅思只剩 0.05%（一页可能一个都没有），太稀疏故未用。
- **影响面**（单级别在外刊上的词种保留率）：高中 / 四级 / 六级 / 专四 / 专八 / 托福 / GRE **100%**（不受影响），
  雅思 18%、BEC 33%、考研 7%。托福不受影响是因为 TOEFL 与 CET4/CET6 零重叠。开关可随时关闭。
- `versionCode` 7 → **8**，`versionName` `1.0.7` → **`1.0.8`**

---

## [1.0.7] - 2026-09-23

**释义质量 + 免费翻译 + 背单词整句译文。**

**新增**
- **免费在线翻译引擎（默认）**：`translate/Translators.kt` 新增 `FreeWebTranslator`，
  **免 API Key**，装好即可翻译。依次尝试公开的免费接口，一个不通自动换下一个：
  1. 有道智云体验接口 `aidemo.youdao.com/trans`（国内直连，实测可用）
  2. MyMemory `api.mymemory.translated.net/get`（匿名额度）
  3. Google 网页端 `translate.googleapis.com/translate_a/single`
  - `TranslationEngines.DEFAULT_ENGINE` 改为 `free`，并注册在 registry 首位；
    其余引擎没配 Key 时自动兜底到它，「未配置翻译引擎」不再出现
  - 设置页新增「免费在线（默认）」芯片与说明；有道提示改为「需自行申请 Key」
- **译文内存缓存**：`TranslationEngines.translate()` 内建 LRU（128 句），
  复习来回翻卡片不重复联网
- **背单词整句译文**：展开释义后，**原文句子下方**自动显示该句完整中文译文
  （`ReviewScreen.SentenceTranslation`），带「译文生成中…」与失败重试

**修复**
- **副词等变形词看不到中文释义**：词典对 `abruptly` 这类词条只收 `adv. abrupt的变形`，
  没有中文。`DictDatabase.enrichFormEntry()` 现在会查出源词释义拼在词条后
  （原词无音标时一并继承），覆盖任何词性
- **查词弹层含义太多滑不到底**：弹层去掉 `maxHeight = 640.dp`，整个弹层改为
  `verticalScroll`；`DictHtmlView` 新增 `autoHeight`（测出 `contentHeight` 后按内容撑开、
  不消费触摸），释义再长也能一路滑到「原文句子 / 译文」
- **生词本存量数据**：进入生词本时顺带修补「无中文释义」的历史记录
  （`VocabRepository.updateMeaning()`），列表与导出不再显示光秃秃的「xx的变形」

**修改**
- **详细解析改为按词性分层**：`WordAnalysis` 新增 `PosDefs` / `parseDefinitions()` /
  `splitByPos()` / `meaningText()` / `isCrossReference()`；复习页每个词性一个标签 + 逐条
  义项（`PosGroup`），不再把整段释义挤成一坨
- `versionCode` 6 → **7**，`versionName` `1.0.6` → **`1.0.7`**

**真机验证**
- 免费接口实测：`aidemo.youdao.com` HTTP 200（MyMemory 200；Google 本网络超时，仅作兜底）
- 背单词展开释义：原文句子下方出现完整译文
  「在透明的天空下，金色的阳光照耀着一个无边无际的巨大山谷。」
- `abruptly` 补全后分层为 `adv.` + `adj. 骤然的，突然的；出其不意的，意外的…`

---

## [1.0.6] - 2026-09-23

**长按选词 + 可拖动选区手柄。**

**新增**
- **长按选词**：长按正文任意单词即高亮该词并显示两条光标手柄，
  松手后选区**保留**，可继续拖动两端手柄微调（`ui/reader/TextSelection.kt`）
- **长按拖选**：长按后继续左右拖动能整词扩选成短语；端点自动**吸附到单词边界**，
  不会选中半个词
- **选区分流**：松手后单字走查词弹层，多词走 `SentenceSheet` 翻译（单次最多 500 字）
- 选区按 `chapterKey` 记忆，翻页 / 换章自动清空

**修复**
- **选区端点翻转 / 塌成空选区**：结束手柄拖动原以「移动中的端点」当另一侧锚点，
  导致 `26..26` 这类空选区；改为以**最新完整选区**为基准只动自己那一端，并用
  `coerceIn` 夹住另一侧
- **手柄拖一半就卡住**：`pointerInput` / `remember` 误把 `idx` 当 key，选区一变就重启
  手势、丢失累计位移；已移除易变 key，并把回调统一用 `rememberUpdatedState` 包裹

**修改**
- 正文手势由 `Text` + `detectTapGestures` 改为 `Box { Text + SelectionOverlay }` +
  `awaitEachGesture` 手写手势（`ClickableText` 不支持长按，早前已弃用）；
  实现不依赖 Compose 1.5 的 `SelectionContainer`
- README 修正 §2 中 `|jinx` 表格残留字符
- `versionCode` 5 → **6**，`versionName` `1.0.5` → **`1.0.6`**

---

## [1.0.5] - 2026-09-22

**配色可切换 + 升级安全 + 开源准备。**

**新增**
- **设置页「界面配色」**：松绿 / 靛蓝 / 暖橙 / 石墨四套方案，点选即时生效并持久化
  （配色集中在 `ui/theme/AppTheme.kt`，新增一套只需加一个枚举值）
- **全新 Logo**：摊开的书页 + 琥珀色高亮词条，改为矢量自适应图标 + 品牌绿渐变底
- `docs/` 目录：仓库 Logo、截图占位与命名规范

**修复**
- **数据库升级会丢数据**：`VocabRepository.onUpgrade` 原为 `DROP TABLE`，
  一旦升库版本就会清空用户生词本。改为按 `PRAGMA table_info` **缺列补列**的非破坏性迁移，
  并覆盖 `onDowngrade`（回滚安装也不清库）。实测 v1→v2 升级后 5 条生词及其 SM-2 进度全部保留
- `BookParser` 中 `titleMeta` 解析后未使用：现在作为无标题章节的兜底名（`书名 · 第 N 章`），编译零告警

**修改**
- 应用显示名 `lreader` → **`LingReader`**（`applicationId` 保持 `com.lreader`，保证升级不丢数据）
- `.gitignore` 扩为 Android Studio 标准 + 本项目专属（构建产物、日志、`local.properties`、
  `.codebuddy/`、参考素材 `data/`、签名文件、翻译 Key、大体积词典源）
- 清理仓库根目录 20 个历史构建日志
- `versionCode` 4 → **5**，`versionName` `1.0.4` → **`1.0.5`**

---

## [1.0.4] - 2026-09-22

**导航改版 + 阅读体验重构 + Apple 风格。**

**新增**
- **底部导航**：书架 / 背单词 / 外刊杂志（占位页）/ 设置，`app/ui/AppNavHost.kt` 统一承载
- **背单词 Tab 二级切换**：生词本 / 复习（`ui/study/StudyScreen.kt`）
- **外刊杂志占位页** `ui/magazine/MagazineScreen.kt`
- **阅读页重构** `ui/reader/ReadingTheme.kt`：
  - 米黄纸背景（取自参考图实测色 `#EDEBDC`），另含纸白 / 夜间三套配色
  - **左右滑动翻页**：按行切片分页，`Crossfade` 过渡
  - **下拉手势**弹出阅读参数面板（字号 / 行距 / 背景）
  - **右下角「详情」**：章节、页码、字数、收藏数、上下章跳转
  - 页码显示 `n/N`，阅读进度复用 `lastScrollY` 字段记录页码（不改表结构）
- **全局主题** `ui/theme/AppTheme.kt`：暖灰底 + 纯白卡片 + 单一强调色，集中调色

**修复**
- **B7 点词无响应**：分页后误加了一层全屏手势 `Box` 遮挡了正文，导致点击不发音、不弹词条、无法收藏；已把手势合并到父节点

**修改**
- `versionCode` 3 → **4**，`versionName` `1.0.3` → **`1.0.4`**
- 书架顶栏去掉设置入口（已由底部导航承载），标题改为「书架」

---

## [1.0.3] - 2026-09-22

**Bug 修复 + 生词本 / 复习体验重做。**

**修复（6 个）**
- **B1 首次进入阅读页不显示分级高亮**：`loading=false` 早于 `dict/levels.ensureReady()`，且 `WordText` 的 `remember` 未包含「分级库就绪」；已调整顺序并新增 `levelsReady` key。实测首次进入彩色像素 0 → 10041
- **B2 生词红色高亮大小写不匹配**：`vocabWords` 与 `contains()` 统一大小写（`COLLATE NOCASE`）
- **B3 生词本点击不发音**：整行可点击即朗读
- **B4 长按选句翻译未接线**：`ClickableText` 换成 `Text` + `detectTapGestures`（支持 `onLongPress`）
- **B5 `DictBottomSheet` 的 `sourceBook` 未被使用**：补充「来自《书名》」
- **B6 `DictEntry.phonetic` 从未赋值**：解析词条 HTML 的 `<span class="phonetic">`，并给历史数据回填音标（仅 UPDATE，不改表结构）

**新增**
- `analysis/WordAnalysis.kt`：词性提取、纯释义、原句切分（纯 Kotlin，无 Android 依赖）
- `export/VocabExporter.kt`：生词本导出 CSV / JSON，落盘到 `getExternalFilesDir/exports/`
- 生词本列表改为「单词 + 词性 + 音标 + 含义」四项，整行点击发音
- 复习页重做：自动播放美音、**记得 / 不记得 / 查看释义** 三选项、不记得回队尾、
  进度 `已掌握/总数`、「更多释义」展开完整词典、原句高亮 + 详细解析

**修改**
- `versionCode` 2 → **3**，`versionName` `1.0.2` → **`1.0.3`**

---

## [1.0.2] - 2026-09-22

**工程可构建性修复版本。** 无功能代码变更；补齐缺失的构建文件、修正全部环境路径，并实测构建出包。

**新增**
- `local.properties`：指向本机 SDK `E:\Android_Sdk`（原缺失，AGP 无法定位 SDK）
- `gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar` / `gradle-wrapper.properties`：补入官方 Gradle Wrapper，锁定 Gradle 8.9（原缺失，只能依赖本机全局 Gradle）

**修改**
- `app/build.gradle.kts`：`versionCode` 1 → **2**，`versionName` `1.0.0` → **`1.0.2`**
- §3 目录结构：补入 `gradlew` / `gradle/wrapper/`、`local.properties`；标注 `work/` 工具链在当前工作区已缺失
- §7.1 前置环境：环境说明全部改为本机实测值（JDK 用 JBR **21.0.4**；SDK 位于 `E:\Android_Sdk`；Gradle 8.9 由 wrapper 固定）
- §7.2 构建命令：改用 `.\gradlew.bat`，补上必须显式设置的 `JAVA_HOME`
- §7.3 安装调试：`adb` 路径改为 `E:\Android_Sdk\platform-tools\adb.exe`，APK 路径改为本工作区
- §7.4 Android Studio 设置：Gradle JDK / SDK Location 改为本机值；Gradle 来源改为 `gradle-wrapper.properties file`
- §7.6 重新生成词典：路径更新，并说明 `work/` 已丢失、不影响构建

**实测结果**
- 命令：`.\gradlew.bat assembleDebug --console=plain`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`，**140,070,877 B ≈ 133.6 MB**，`BUILD SUCCESSFUL in 2m 31s`
- 环境踩坑：本机 Android Studio 自带 JBR 为 **Java 25**，Gradle 8.9 不支持，必须改用 JBR **21**；本机缓存的 Gradle **9.3.0** 与 AGP 8.1.4 不兼容
- SDK 自动补齐：构建时 AGP 联网下载了 `platforms/android-33`（rev 3）与 `build-tools/34.0.0`
- Kotlin 编译有 4 条 warning（未使用变量/参数 3 处、多余安全调用 1 处），不影响出包

---

## [1.0.1] - 2026-09-22

**文档修正版本。** 无代码变更，仅修正 §7 构建与调试章节中已失效的路径与环境说明。

**修改**
- §7.2/§7.3/§7.6：构建路径由失效的 `Y:\new_midware\ereader\...` 更正为实际工作区 `d:\harness\project\ereader\...`
- §7.2：Gradle 改用 `%USERPROFILE%\.gradle\wrapper\dists\...\gradle-8.9\bin\gradle.bat`（原硬编码他人用户目录）；补充「工程内无 `gradlew`」说明
- §7.3：`adb` 补充绝对路径 `D:\Debugging_tool\adb\platform-tools\adb.exe`，并说明 PowerShell 下需用 `$(...)` 做命令替换
- §7.1：更新本机 SDK 踩坑记录（SDK 位于 `D:\Debugging_tool\android-sdk`）
- §3：assets 文件大小补全为实测字节数

**新增**
- §7.4「用 Android Studio 调试（推荐）」：工程导入、Gradle JDK / SDK 设置、常用断点位置、Logcat / Layout Inspector / Profiler 用法
- §7.5「常见问题排查（Debug 速查）」：首启白屏、点词无释义、TTS 不发音、翻译失败、EPUB 导入失败、崩溃 6 类现象的排查方式
- 原 §7.4「重新生成词典」顺延为 §7.6

---

## [1.0.0] - 2026-09-22

**首个可用版本。** 从零构建（UI 原创），对标英语阅读学习场景的核心功能。

**新增功能**
- 阅读器：TXT / EPUB / FB2 / HTML 导入与章节切分、进度记忆、字号行距调节
- 词典：33.2 万词条英汉词典（21世纪大英汉词典），点词查词、词形还原、HTML 释义渲染
- 分级词库：39,256 词条，10 个级别（CET4/CET6/考研/专四/专八/GRE/雅思/托福/高中/BEC），逐级配色高亮
- 生词本：从查词弹层一键收藏（含原句/来源书/级别），列表页支持搜索、发音、删除
- 背单词：SM-2 间隔重复算法，认识/模糊/不认识三档评分，到期词优先
- 发音：系统 TTS，点词自动朗读，支持美音/英音与语速调节
- 翻译：DeepL / 百度 / 腾讯云 / OpenAI 兼容 四引擎，可插拔接口，用户自填 Key
- 设置页：词典状态、发音、显示、分级高亮、翻译引擎 5 个分组

**技术要点**
- 词典转换链路：MDict `.mdx` → SQLite + zlib 压缩（自实现 ripemd128 解密 + zlib 解压）
- 依赖版本：AGP 8.1.4 / Kotlin 1.8.10 / Compose 1.4.3 / material3 1.1.2（适配 compileSdk 33）
- 零重型依赖：SQLiteOpenHelper + JSONObject + OkHttp

**已知限制**
- 首次启动需释放 120MB 词典到 `filesDir`，约 10~30 秒
- 长按选句翻译尚未接入（当前通过点词后翻译所在句子）→ **v1.0.3 已实现**
- 生词本导出（Anki/CSV）未实现 → **v1.0.3 已实现 CSV / JSON 导出**
- 尚无 WebDAV / OPDS 书源

---

