<#
  从工作区的 icons/ 生成 Android assets 图标索引。

  用法（在仓库根目录）：
      pwsh -File tools/gen_icon_index.ps1

  输出：
      app/src/main/assets/icons/*.png      图标（原样复制，不做压缩）
      app/src/main/assets/icon-index.json  归一化键 -> {f:默认, l:light, d:dark, n:显示名}

  归一化规则必须与 Kotlin 端 IconNormalizer.normalizeKey 完全一致：
      NFKC -> 仅保留字母/数字 -> 小写
  （实测陷阱：34SP.com.png / ALL-INKL.COM.png 多点号；_light 196 个；_dark 若干；
    ETH Zürich_light.png、Hatena (はてな).png、超长截断名）
#>
$ErrorActionPreference = "Stop"

$root      = Split-Path -Parent $PSScriptRoot
$sourceDir = Join-Path $root "icons"
$assetDir  = Join-Path $root "app\src\main\assets\icons"
$indexPath = Join-Path $root "app\src\main\assets\icon-index.json"

if (-not (Test-Path $sourceDir)) { throw "找不到图标目录：$sourceDir" }
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null

function Get-NormalizedKey([string]$raw) {
    $nfkc = $raw.Normalize([System.Text.NormalizationForm]::FormKC)
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $nfkc.ToCharArray()) {
        if ([char]::IsLetterOrDigit($ch)) {
            [void]$sb.Append([char]::ToLowerInvariant($ch))
        }
    }
    return $sb.ToString()
}

function Get-BaseAndVariant([string]$fileName) {
    $base = [System.IO.Path]::GetFileNameWithoutExtension($fileName)
    if ($base.EndsWith("_light", [System.StringComparison]::OrdinalIgnoreCase)) {
        return @{ Base = $base.Substring(0, $base.Length - 6); Variant = "light" }
    }
    if ($base.EndsWith("_dark", [System.StringComparison]::OrdinalIgnoreCase)) {
        return @{ Base = $base.Substring(0, $base.Length - 5); Variant = "dark" }
    }
    return @{ Base = $base; Variant = "default" }
}

$files = Get-ChildItem -LiteralPath $sourceDir -File -Filter *.png | Sort-Object Name
$entries = [ordered]@{}
$copied = 0

foreach ($f in $files) {
    $bv = Get-BaseAndVariant $f.Name
    $key = Get-NormalizedKey $bv.Base
    if ([string]::IsNullOrEmpty($key)) { continue }

    if (-not $entries.Contains($key)) {
        $entries[$key] = @{ f = ""; l = $null; d = $null; n = $bv.Base }
    }
    $e = $entries[$key]
    switch ($bv.Variant) {
        "light"   { $e.l = $f.Name }
        "dark"    { $e.d = $f.Name }
        "default" { $e.f = $f.Name }
    }

    # 复制到 assets（同名只复制一次）
    $dest = Join-Path $assetDir $f.Name
    if (-not (Test-Path -LiteralPath $dest)) {
        Copy-Item -LiteralPath $f.FullName -Destination $dest
        $copied++
    }
}

# 变体存在但缺少默认图标时，用 light 兜底，保证运行时总能取到一张
$fixed = 0
foreach ($key in $entries.Keys) {
    $e = $entries[$key]
    if ([string]::IsNullOrEmpty($e.f)) {
        if ($e.l) { $e.f = $e.l } elseif ($e.d) { $e.f = $e.d } else { continue }
        $fixed++
    }
}

# 组装 JSON：文件名做 URL 编码，避免空格/中文/& 在 assets 路径与 JSON 中的歧义
$icons = [ordered]@{}
foreach ($key in ($entries.Keys | Sort-Object)) {
    $e = $entries[$key]
    $icons[$key] = [ordered]@{
        f = [Uri]::EscapeDataString($e.f)
        l = if ($e.l) { [Uri]::EscapeDataString($e.l) } else { $null }
        d = if ($e.d) { [Uri]::EscapeDataString($e.d) } else { $null }
        n = $e.n
    }
}

$payload = [ordered]@{ v = 1; icons = $icons }
$jsonText = $payload | ConvertTo-Json -Depth 6 -Compress
[System.IO.File]::WriteAllText($indexPath, $jsonText, (New-Object System.Text.UTF8Encoding($false)))

$sizeMb = [math]::Round(((Get-ChildItem -LiteralPath $sourceDir -File | Measure-Object Length -Sum).Sum / 1MB), 2)
Write-Host "图标总数      : $($files.Count)"
Write-Host "索引条目      : $($entries.Count)"
Write-Host "复制到 assets : $copied"
Write-Host "原始总大小    : $sizeMb MB"
Write-Host "索引文件      : $indexPath"
