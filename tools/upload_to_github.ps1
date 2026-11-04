# 用 GitHub Git Data API 上传源码（无需本机安装 git）
#
# 用法：
#   $env:GITHUB_TOKEN = "ghp_xxx"          # 需要 repo 的 Contents: Read and write 权限
#   .\tools\upload_to_github.ps1 -DryRun   # 先看会传哪些文件
#   .\tools\upload_to_github.ps1
#
# 参数：
#   -Owner xptx-pld -Repo robot -Branch main -Message "..."
#
# 说明：仓库为空时创建首个 commit 并建立分支；已有历史时在其上追加一个 commit。
# 不会 force push，不会删除服务端已有文件。

param(
    [string]$Owner = 'xptx-pld',
    [string]$Repo = 'robot',
    [string]$Branch = 'main',
    [string]$Root = (Resolve-Path "$PSScriptRoot\..").Path,
    [string]$Message = 'restore: 基于激光雷达的自主导航机器人（Android + ROS2 + 后台）',
    [switch]$DryRun,
    [switch]$PromptToken,
    [string]$TokenFile = '',
    [switch]$Clipboard
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# ---------------------------------------------------------------- 文件挑选

$excludeDirs = @('.git', '20191118', '雷达SDK', 'recovery', 'build', '.gradle', '.idea', '__pycache__', '.venv')
$excludeDirPrefix = '基于激光雷达'      # 参赛报告/PPT/视频
$excludeFiles = @('local.properties', 'conout$', 'CONOUT$', '.token', 'token.txt', '.env')
$excludeExt = @('.pyc', '.pyo', '.apk', '.aab', '.iml', '.log')

function Should-Skip([System.IO.FileInfo]$file) {
    if ($excludeFiles -contains $file.Name) { return $true }
    if ($excludeExt -contains $file.Extension.ToLower()) { return $true }
    $rel = $file.FullName.Substring($Root.Length).TrimStart('\', '/').Replace('\', '/')
    foreach ($part in $rel.Split('/')) {
        if ($excludeDirs -contains $part) { return $true }
        if ($part.StartsWith($excludeDirPrefix)) { return $true }
    }
    if ($rel -match '^recovery/usage_.*\.json$') { return $true }
    return $false
}

$files = Get-ChildItem -Path $Root -Recurse -File -Force |
    Where-Object { -not (Should-Skip $_) } |
    Sort-Object FullName

$totalBytes = ($files | Measure-Object Length -Sum).Sum
Write-Host ("待上传 {0} 个文件，共 {1:N2} MB" -f $files.Count, ($totalBytes / 1MB))

if ($DryRun) {
    $files | ForEach-Object {
        $rel = $_.FullName.Substring($Root.Length).TrimStart('\', '/').Replace('\', '/')
        "{0,10:N1} KB  {1}" -f ($_.Length / 1KB), $rel
    }
    Write-Host "`n[DryRun] 未上传。去掉 -DryRun 且设置 GITHUB_TOKEN 后执行。"
    return
}

# ---------------------------------------------------------------- 鉴权

$token = $env:GITHUB_TOKEN
$tokenFromFile = ''

if (-not $token -and $TokenFile) {
    if (-not (Test-Path $TokenFile)) {
        throw "找不到 token 文件: $TokenFile"
    }
    $token = (Get-Content -Path $TokenFile -Raw -Encoding UTF8).Trim()
    $tokenFromFile = $TokenFile
    Write-Host "已从文件读取 token: $TokenFile"
}

if (-not $token -and $Clipboard) {
    try {
        $clip = [string](Get-Clipboard -Raw)
        if ($clip) { $token = $clip.Trim() }
    } catch {
        Write-Host '读取剪贴板失败'
    }
    if ($token) { Write-Host '已从剪贴板读取 token（长度 ' + $token.Length + '，不显示内容）' }
}

if (-not $token -and $PromptToken) {
    $secure = Read-Host -AsSecureString '粘贴 GitHub PAT（输入不回显，粘贴后回车）'
    if ($secure -and $secure.Length -gt 0) {
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
    if (-not $token) {
        Write-Host '交互输入没有读到内容（当前终端可能不支持安全输入），改用文件方式：'
        Write-Host '  1) 把 token 粘贴到 tools\.token 并保存'
        Write-Host '  2) 运行 .\tools\upload_to_github.ps1 -TokenFile .\tools\.token'
    }
}

$token = ($token -as [string])
if ($token) { $token = $token.Trim() }
if (-not $token) {
    throw '缺少 token。用 -TokenFile .\tools\.token、$env:GITHUB_TOKEN，或在支持交互的终端里用 -PromptToken'
}

$headers = @{
    Authorization          = "token $token"
    'User-Agent'           = 'sanbot-restore-uploader'
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}
$api = "https://api.github.com/repos/$Owner/$Repo"

function Invoke-GitHub {
    param([string]$Method, [string]$Uri, $Body)

    $json = $null
    if ($null -ne $Body) {
        $json = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 12 -Compress))
    }
    try {
        if ($null -ne $json) {
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers `
                -Body $json -ContentType 'application/json; charset=utf-8' -TimeoutSec 120
        }
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -TimeoutSec 120
    } catch {
        $detail = ''
        if ($_.Exception.Response) {
            $stream = $_.Exception.Response.GetResponseStream()
            if ($stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                $detail = $reader.ReadToEnd()
            }
        }
        throw ("GitHub API 失败: {0} {1}`n{2}`n{3}" -f $Method, $Uri, $_.Exception.Message, $detail)
    }
}

# ---------------------------------------------------------------- 基线

$baseCommit = $null
$baseTree = $null
try {
    $ref = Invoke-GitHub -Method GET -Uri "$api/git/ref/heads/$Branch"
    $baseCommit = $ref.object.sha
    $commit = Invoke-GitHub -Method GET -Uri "$api/git/commits/$baseCommit"
    $baseTree = $commit.tree.sha
    Write-Host "已有分支 $Branch，基线 commit: $baseCommit"
} catch {
    Write-Host "分支 $Branch 不存在或仓库为空，将创建首个提交"
}

# ---------------------------------------------------------------- 上传 blob

$treeEntries = New-Object System.Collections.ArrayList
$index = 0

foreach ($file in $files) {
    $index++
    $rel = $file.FullName.Substring($Root.Length).TrimStart('\', '/').Replace('\', '/')
    $content = [System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes($file.FullName))
    $blob = Invoke-GitHub -Method POST -Uri "$api/git/blobs" -Body @{
        content  = $content
        encoding = 'base64'
    }
    [void]$treeEntries.Add(@{
        path = $rel
        mode = '100644'
        type = 'blob'
        sha  = $blob.sha
    })
    Write-Host ("  [{0}/{1}] {2}" -f $index, $files.Count, $rel)
}

# ---------------------------------------------------------------- 树 / 提交 / 引用

$treeBody = @{ tree = $treeEntries }
if ($baseTree) { $treeBody.base_tree = $baseTree }
$tree = Invoke-GitHub -Method POST -Uri "$api/git/trees" -Body $treeBody

$commitBody = @{ message = $Message; tree = $tree.sha }
if ($baseCommit) { $commitBody.parents = @($baseCommit) }
$newCommit = Invoke-GitHub -Method POST -Uri "$api/git/commits" -Body $commitBody

if ($baseCommit) {
    Invoke-GitHub -Method PATCH -Uri "$api/git/refs/heads/$Branch" -Body @{
        sha   = $newCommit.sha
        force = $false
    } | Out-Null
} else {
    Invoke-GitHub -Method POST -Uri "$api/git/refs" -Body @{
        ref = "refs/heads/$Branch"
        sha = $newCommit.sha
    } | Out-Null
}

Write-Host ""
Write-Host "完成：https://github.com/$Owner/$Repo/tree/$Branch"
Write-Host "commit: $($newCommit.sha)"

if ($tokenFromFile) {
    try {
        Remove-Item $tokenFromFile -Force
        Write-Host "已删除 token 文件: $tokenFromFile"
    } catch {
        Write-Host "警告：删除 token 文件失败，请手动删除: $tokenFromFile"
    }
}
