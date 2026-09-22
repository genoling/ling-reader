# 工程完整性检查 / 缺失文件清单

> 上传 GitHub 前逐项确认。状态：✅ 已有 · ⚠️ 待补 · ➖ 本项目不需要

## 一、仓库必备文件

| 文件 | 状态 | 说明 |
|---|---|---|
| `README.md` | ✅ | 已含简介、功能、截图占位、构建步骤、环境依赖 |
| `LICENSE` | ✅ | MIT，含第三方词典数据声明 |
| `.gitignore` | ✅ | Android Studio 标准 + 本项目专属 |
| `docs/logo.png` | ✅ | 仓库 Logo（与 App 图标同源几何） |
| `docs/repo-info.md` | ✅ | 仓库简介 / 标签 / Release 文案 |
| `docs/screenshots/README.md` | ✅ | 截图命名规范与抓取方式 |
| `docs/setup-github.ps1` | ✅ | 初始化 + 提交 + 推送脚本 |
| `docs/screenshots/01~07*.png` | ⚠️ **待补** | 实际运行截图，7 张（见该目录说明） |
| `CHANGELOG.md` | ➖ | README §8 版本历史已承担，避免两处维护 |
| `CONTRIBUTING.md` | ⚠️ 可选 | 单人项目可暂缓 |
| `.github/workflows/build.yml` | ⚠️ 可选 | CI 自动编译，首次推送后再加 |

## 二、Android 工程结构

| 项 | 状态 | 说明 |
|---|---|---|
| `settings.gradle.kts` / 根 `build.gradle.kts` | ✅ | 含阿里云镜像 |
| `gradle.properties` | ✅ | |
| `gradle/wrapper/gradle-wrapper.jar` + `gradlew` + `gradlew.bat` | ✅ | 锁定 Gradle 8.9 |
| `local.properties` | ✅ 已排除 | 含本机 SDK 路径，`.gitignore` 已忽略 |
| `app/build.gradle.kts` | ✅ | `applicationId = com.lreader` |
| `app/proguard-rules.pro` | ✅ | |
| `app/src/main/AndroidManifest.xml` | ✅ | |
| `res/mipmap-anydpi-v26/` 自适应图标 | ✅ | |
| `res/mipmap/` 低版本回退图标 | ✅ | 矢量 layer-list，API 24~25 可用 |
| `res/mipmap-{hdpi,xhdpi,...}/` 各密度 PNG | ⚠️ 可选 | 当前用矢量方案，多数设备正常；若个别启动器不显示可再补位图 |
| `res/values/strings.xml` 英文版本 `values-en/` | ⚠️ 可选 | 目前仅中文 |
| `assets/dict_en_zh.min.db`（114.8 MB） | ✅ 保留 | 核心资产，必须随仓库分发 |
| `assets/levels.db`（4.6 MB） | ✅ 保留 | 同上 |

> 🔴 **阻塞项：`dict_en_zh.min.db` = 114.8 MB，超过 GitHub 单文件 100 MB 硬上限，直接 push 必定失败。**
>
> 三种处理方式，请择一：
>
> | 方案 | 做法 | 代价 |
> |---|---|---|
> | **A. Git LFS（推荐）** | `git lfs install` → `git lfs track "app/src/main/assets/*.db"` → `git add .gitattributes` | 免费额度仅 **1 GB 存储 + 1 GB/月流量**；一次克隆就消耗 114.8 MB 流量，约 9 次克隆后当月超限 |
> | **B. 词典仅作为 Release 资产** | 把 `assets/*.db` 加入 `.gitignore`，Release 里附带词典包，App 首次启动下载 | 需改造资源加载逻辑；失去「完全离线开箱即用」，属较大改动 |
> | **C. 拆分存储** | 用 `split` 切成 <100 MB 的分片提交，构建前合并 | 仓库里是碎文件，克隆后必须按脚本拼回，体验差 |
>
> 已实测：不加 LFS 直接 `git add` 时，`docs/setup-github.ps1` 会在预演阶段拦截并给出提示。

## 三、发布相关

| 项 | 状态 | 说明 |
|---|---|---|
| 调试签名 | ✅ 自动 | `assembleDebug` 用 Android 默认 debug key |
| **Release 签名 keystore** | ⚠️ **待创建** | 发布前必须生成，并妥善保管；已在 `.gitignore` 排除 |
| `signingConfigs` 配置 | ⚠️ 待加 | 见下方命令 |
| 版本号 | ✅ | `versionCode 5` / `versionName 1.0.5` |

生成 keystore：

```powershell
keytool -genkeypair -v -keystore lingreader-release.jks `
  -alias lingreader -keyalg RSA -keysize 2048 -validity 10950
```

> **务必妥善保存**：keystore 一旦丢失，将无法再对已上架的应用发布覆盖升级包。

## 四、上架前合规检查

| 项 | 状态 | 说明 |
|---|---|---|
| 词典数据来源声明 | ✅ | 已写入 `LICENSE`，仅供个人学习使用 |
| 用户数据本地化 | ✅ | 生词本 / 书架 / 设置全部存本机，不上传 |
| 网络权限用途 | ✅ | 仅翻译功能；不填 Key 则完全不联网 |
| 第三方平台 Key 处理 | ✅ | 用户自行填写，存本机 `translate_keys.json`，已在 `.gitignore` 排除 |
| 隐私政策页面 | ⚠️ 待补 | 上架 Google Play 需要 |

## 五、当前必需的收尾动作

1. ⚠️ 补 7 张运行截图到 `docs/screenshots/`
2. ⚠️ 确认词典 db 是否能直接推送（>100 MB 建议先上 Git LFS）
3. ⚠️ 创建 Release keystore
4. ⚠️ 替换 `README.md` / `docs/repo-info.md` 中的 `<your-name>` 为真实 GitHub 用户名
