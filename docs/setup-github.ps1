<#
.SYNOPSIS
  把 LingReader 工程初始化并推送到 GitHub 仓库 ling-reader。

.DESCRIPTION
  1) 环境自检（git、用户身份）
  2) git init（主分支 main）
  3) 预演将要提交的文件，并拦截不该提交的内容
  4) 大文件检查（>100MB 会推送失败，自动提示 Git LFS）
  5) 提交 + 关联远程 + 推送

.EXAMPLE
  # 只做检查，不提交（推荐先跑一次）
  .\docs\setup-github.ps1 -RepoUrl https://github.com/your-name/ling-reader.git -DryRun

.EXAMPLE
  # 正式初始化 + 推送
  .\docs\setup-github.ps1 -RepoUrl https://github.com/your-name/ling-reader.git -Message "chore: 首次开源提交"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RepoUrl,

    [string]$Message = "chore(repo): 初始化 LingReader 开源仓库",

    [string]$Branch = "main",

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# 切到工程根（本脚本位于 docs/ 下）
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
Write-Host "工程根目录：$root`n" -ForegroundColor Cyan

# ---------------------------------------------------------------- 1) 环境自检
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "未找到 git，请先安装 Git for Windows。"
}
Write-Host ("git 版本：" + (git --version)) -ForegroundColor Green

# ---------------------------------------------------------------- 2) 初始化
# 注：DryRun 也会执行 init（只创建 .git，无副作用），因为后续检查都要求处于仓库中
if (-not (Test-Path (Join-Path $root ".git"))) {
    git init -b $Branch | Out-Null
    Write-Host "`n已初始化仓库，主分支：$Branch" -ForegroundColor Green
} else {
    Write-Host "`n检测到已存在 .git，跳过初始化。" -ForegroundColor Green
}

$name = git config user.name
$mail = git config user.email
if (-not $name -or -not $mail) {
    Write-Host "❌ 未配置 git 提交身份，已中止（避免用占位邮箱产生提交记录）。" -ForegroundColor Red
    Write-Host "   请先执行：" -ForegroundColor Yellow
    Write-Host '     git config user.name  "Your Name"' -ForegroundColor Yellow
    Write-Host '     git config user.email "you@example.com"' -ForegroundColor Yellow
    exit 1
}
Write-Host "提交身份：$name <$mail>" -ForegroundColor Green

# ---------------------------------------------------------------- 3) 预演 + 拦截
Write-Host "`n=== 即将提交的文件（前 60 个）===" -ForegroundColor Cyan
# 注意：dry-run 输出形如  add 'path/to/file'，需要同时去掉前缀与引号
$files = git add -A --dry-run 2>$null | ForEach-Object {
    ($_ -replace '^add\s+', '').Trim().Trim("'")
}
$files | Select-Object -First 60
Write-Host ("… 共 " + $files.Count + " 个文件") -ForegroundColor Cyan

$forbidden = $files | Where-Object {
    $_ -match '\.log$' -or
    $_ -match '(^|/)build/' -or
    $_ -match 'local\.properties' -or
    $_ -match '\.codebuddy/' -or
    $_ -match '\.apk$' -or
    $_ -match '\.jks$|\.keystore$' -or
    $_ -match 'translate_keys\.json'
}
if ($forbidden) {
    Write-Host "`n❌ 检测到不该提交的文件，请修正 .gitignore 后重试：" -ForegroundColor Red
    $forbidden | ForEach-Object { Write-Host "   $_" -ForegroundColor Red }
    throw "存在不该提交的文件。"
}
Write-Host "`n✅ 未发现日志 / 构建产物 / 本机配置 / 密钥。" -ForegroundColor Green

# ---------------------------------------------------------------- 4) 大文件检查
Write-Host "`n=== 大文件检查（>50MB 提示，>100MB 会被 GitHub 拒绝）===" -ForegroundColor Cyan
$big = $files | Where-Object { Test-Path $_ } | ForEach-Object {
    $len = (Get-Item $_).Length
    if ($len -gt 50MB) { [pscustomobject]@{ Path = $_; MB = [math]::Round($len / 1MB, 1) } }
} | Sort-Object MB -Descending

if ($big) {
    $big | Format-Table -AutoSize
    if ($big | Where-Object { $_.MB -gt 100 }) {
        Write-Host "⚠️ 存在 >100MB 的文件，直接 push 会失败。建议启用 Git LFS：" -ForegroundColor Yellow
        Write-Host '   git lfs install' -ForegroundColor Yellow
        Write-Host '   git lfs track "app/src/main/assets/*.db"' -ForegroundColor Yellow
        Write-Host '   git add .gitattributes' -ForegroundColor Yellow
    }
} else {
    Write-Host "✅ 没有超过 50MB 的文件。" -ForegroundColor Green
}

if ($DryRun) {
    Write-Host "`n[预演结束] 未做任何提交。确认无误后去掉 -DryRun 再执行。" -ForegroundColor Cyan
    exit 0
}

# ---------------------------------------------------------------- 5) 提交 / 推送
git add -A
Write-Host "`n=== 提交 ===" -ForegroundColor Cyan
git commit -m $Message

if (git remote | Select-String -Quiet '^origin$') {
    git remote set-url origin $RepoUrl
} else {
    git remote add origin $RepoUrl
}
Write-Host "远程 origin -> $RepoUrl" -ForegroundColor Green

Write-Host "`n=== 推送 ===" -ForegroundColor Cyan
git push -u origin $Branch

Write-Host "`n完成 ✅" -ForegroundColor Green
git log --oneline -1
Write-Host "`n后续发布 Release 时记得打 tag：" -ForegroundColor Cyan
Write-Host "  git tag -a v1.0.5 -m `"LingReader v1.0.5`"; git push origin v1.0.5"
