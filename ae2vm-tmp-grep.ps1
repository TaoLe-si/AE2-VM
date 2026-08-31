$lines = Get-Content 'E:\MC\.minecraft\versions\GTL测试\logs\latest.log' -Tail 3000
$matched = $lines | Select-String -Pattern 'iv_emitter|hv_emitter|=== CRAFT END.*emitter|=== CRAFT START.*emitter'
$out = New-Object System.Text.StringBuilder
foreach ($m in $matched) {
    [void]$out.AppendLine($m.Line.Substring(0, [Math]::Min($m.Line.Length, 500)))
}
[System.IO.File]::WriteAllText('E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-grep.txt', $out.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Host "wrote $($out.Length) chars (count=$($matched.Count))"