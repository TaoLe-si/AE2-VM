$ErrorActionPreference = 'Continue'
$jar = 'E:\MC\.minecraft\versions\1.20.1-Forge_47.4.22\mods\[应用能源2] appliedenergistics2-forge-15.4.10.jar'
Add-Type -AssemblyName 'System.IO.Compression.FileSystem'
$z = [System.IO.Compression.ZipFile]::OpenRead($jar)
foreach ($e in $z.Entries) {
    if ($e.FullName -like '*ChildCraftingSimulationState.class' -or
        $e.FullName -like '*RealtimeNetworkCraftingSimulationState*') {
        $path = 'E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-extracted'
        $dir = Split-Path (Join-Path $path $e.FullName) -Parent
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        $dest = Join-Path $path ($e.FullName -replace '/', '\')
        $fs = [System.IO.File]::Create($dest)
        $e.Open().CopyTo($fs)
        $fs.Close()
        Write-Host $e.FullName
    }
}
$z.Dispose()