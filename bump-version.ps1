# bump-version.ps1 — 每次编译给 gradle.properties 的 mod_version +0.0.1（PATCH+1）
# 版本号基于 1.9.0（每次编译 PATCH+1：1.9.0 → 1.9.1 → 1.9.2 ...）
# 注意: gradle.properties 带 BOM，必须用 [IO.File]::ReadAllText/WriteAllText + UTF8(BOM) 保编码。
$ErrorActionPreference = 'Stop'

$propsPath = Join-Path $PSScriptRoot 'gradle.properties'
if (-not (Test-Path $propsPath)) {
    Write-Error "gradle.properties not found at $propsPath"
    exit 1
}

$utf8Bom = New-Object System.Text.UTF8Encoding($true)
$content = [IO.File]::ReadAllText($propsPath)

# 只替换 mod_version=<major>.<minor>.<patch> 的版本数字部分，
# 必须保留 "mod_version=" 前缀（Group[1]），行尾(\r\n)不动。
$newContent = [regex]::Replace(
    $content,
    '(?m)^(mod_version\s*=\s*)(\d+)\.(\d+)\.(\d+)',
    {
        param($m)
        $major = [int]$m.Groups[2].Value
        $minor = [int]$m.Groups[3].Value
        $patch = [int]$m.Groups[4].Value + 1
        return "$($m.Groups[1].Value)$major.$minor.$patch"
    }
)

if ($newContent -ceq $content) {
    Write-Warning "mod_version not found / not bumped — check gradle.properties"
    exit 1
}

[IO.File]::WriteAllText($propsPath, $newContent, $utf8Bom)

$oldVer = [regex]::Match($content, '(?m)^mod_version\s*=\s*(\S+)').Groups[1].Value
$newVer = [regex]::Match($newContent, '(?m)^mod_version\s*=\s*(\S+)').Groups[1].Value
Write-Host "mod_version bumped: $oldVer -> $newVer"
