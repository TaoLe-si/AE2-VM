$lines = Get-Content 'E:\MC\.minecraft\versions\GTL测试\logs\latest.log'
$matched = $lines | Select-String -Pattern '12:24:5[0-9]|12:25:0[0-9]|12:25:1[0-9]|12:25:2[0-9]|12:25:3[0-9]|12:26|12:27|12:28|12:29|12:30' |
    Where-Object { $_.Line -match 'iv_emitter|iv_16a|iv_wireless|stuck|Cannot|cancel|busy' } |
    Select-Object -First 30
$out = New-Object System.Text.StringBuilder
foreach ($m in $matched) {
    [void]$out.AppendLine($m.Line.Substring(0, [Math]::Min($m.Line.Length, 500)))
}
[System.IO.File]::WriteAllText('E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-grep.txt', $out.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Host "wrote $($out.Length) chars (count=$($matched.Count))"