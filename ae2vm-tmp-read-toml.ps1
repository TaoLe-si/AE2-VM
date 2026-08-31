$jars = @(
  'e:\Applied Energistics 2 Acceleration\VM-GTL\build\libs\ae2vm-nodetect-1.12.54_forge_1.20.1_gtl.jar',
  'e:\Applied Energistics 2 Acceleration\VM-GTL\build\libs\ae2vm-1.12.54_forge_1.20.1_gtl.jar'
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($path in $jars) {
  Write-Output "===== $path ====="
  $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
  $e = $zip.GetEntry('META-INF/mods.toml')
  $sr = New-Object System.IO.StreamReader($e.Open())
  Write-Output $sr.ReadToEnd()
  $sr.Close()
  $zip.Dispose()
}
