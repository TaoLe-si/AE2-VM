$lines = Get-Content 'E:\MC\.minecraft\versions\GTL测试\logs\latest.log'
$matched = $lines | Select-String -Pattern '12:1[5-9]|12:2[0-9]|12:3[0-9]' |
    Where-Object { $_.Line -match 'AE2-VM|emitter|wireless|hv_universal' } |
    Select-Object -First 30
$out = New-Object System.Text.StringBuilder
foreach ($m in $matched) {
    [void]$out.AppendLine($m.Line.Substring(0, [Math]::Min($m.Line.Length, 500)))
}
[System.IO.File]::WriteAllText('E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-grep.txt', $out.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Host "wrote $($out.Length) chars (count=$($matched.Count))"