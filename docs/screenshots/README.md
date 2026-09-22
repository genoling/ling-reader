# 截图目录

把 App 实际运行截图按下列文件名放进本目录，仓库主页会自动展示。

| 文件名 | 内容 | 建议尺寸 |
|---|---|---|
| `01-shelf.png` | 书架页（含导入按钮） | 1080×1920 |
| `02-reader.png` | 阅读页（分级高亮 + 页码 + 详情） | 1080×1920 |
| `03-dict.png` | 点词查词弹层（音标 / 释义 / 原句） | 1080×1920 |
| `04-vocab.png` | 生词本列表（单词 / 词性 / 音标 / 含义） | 1080×1920 |
| `05-review.png` | 背单词卡片（记得 / 不记得 / 查看释义） | 1080×1920 |
| `06-settings.png` | 设置页（界面配色 / 发音 / 分级高亮 / 翻译引擎） | 1080×1920 |
| `07-night.png` | 夜间模式阅读 | 1080×1920 |

> 抓图方式（MuMu 模拟器）：
> ```powershell
> $adb = 'E:\Android_Sdk\platform-tools\adb.exe'
> & $adb -s 127.0.0.1:16384 shell screencap -p /sdcard/s.png
> & $adb -s 127.0.0.1:16384 pull /sdcard/s.png docs/screenshots/02-reader.png
> ```
