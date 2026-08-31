Add-Type -AssemblyName 'System.IO.Compression.FileSystem'
$jar = 'E:\MC\.minecraft\versions\GTL测试\mods\appliedenergistics2-forge-15.4.10.jar'
$out = 'E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-cpu-classes'
$z = [System.IO.Compression.ZipFile]::OpenRead($jar)
foreach ($e in $z.Entries) {
    if ($e.FullName -like '*CraftingCpuHelper*' -or $e.FullName -like '*CraftingCpuLogic*') {
        $dest = Join-Path $out ($e.FullName -replace '/', '\')
        $dir = Split-Path $dest -Parent
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        $fs = [System.IO.File]::Create($dest)
        $e.Open().CopyTo($fs)
        $fs.Close()
        Write-Host $e.FullName
    }
}
$z.Dispose()