<#
  从 Android assets 图标目录生成图标索引。

  用法（在仓库根目录）：
      pwsh -File tools/gen_icon_index.ps1

  源与输出（图标已统一为有损 WebP q85，assets/icons 即唯一真源，不再从别处复制）：
      app/src/main/assets/icons/*.webp     图标（就地扫描，不复制、不再压缩）
      app/src/main/assets/icon-index.json  归一化键 -> {f:默认, l:light, d:dark, n:显示名}

  归一化规则必须与 Kotlin 端 IconNormalizer.normalizeKey 完全一致：
      NFKC -> 仅保留字母/数字 -> 小写
  （实测陷阱：34SP.com.webp / ALL-INKL.COM.webp 多点号；_light 196 个；_dark 若干；
    ETH Zürich_light.webp、Hatena (はてな).webp、超长截断名）
#>
$ErrorActionPreference = "Stop"

$root      = Split-Path -Parent $PSScriptRoot
$assetDir  = Join-Path $root "app\src\main\assets\icons"
$indexPath = Join-Path $root "app\src\main\assets\icon-index.json"

if (-not (Test-Path $assetDir)) { throw "找不到图标目录：$assetDir" }

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

$files = Get-ChildItem -LiteralPath $assetDir -File -Filter *.webp | Sort-Object Name
$entries = [ordered]@{}

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

$sizeMb = [math]::Round(((Get-ChildItem -LiteralPath $assetDir -File | Measure-Object Length -Sum).Sum / 1MB), 2)
Write-Host "图标总数      : $($files.Count)"
Write-Host "索引条目      : $($entries.Count)"
Write-Host "兜底修正      : $fixed"
Write-Host "assets 总大小 : $sizeMb MB"
Write-Host "索引文件      : $indexPath"
