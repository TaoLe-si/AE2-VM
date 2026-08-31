$lines = Get-Content 'E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-cpu-decomp.txt' -Raw
$sb = New-Object System.Text.StringBuilder
$idx = $lines.IndexOf('public void executeCrafting')
if ($idx -lt 0) { $idx = $lines.IndexOf('public boolean executeCrafting') }
if ($idx -lt 0) { $idx = $lines.IndexOf('public void tryExecutePattern') }
if ($idx -lt 0) { $idx = $lines.IndexOf('extractForProcessingPattern') }
if ($idx -lt 0) { $idx = $lines.IndexOf('public boolean tryPushPattern') }
$start = [Math]::Max(0, $idx - 50)
$end = [Math]::Min($lines.Length, $idx + 5000)
[void]$sb.AppendLine($lines.Substring($start, $end - $start))
[System.IO.File]::WriteAllText('E:\Applied Energistics 2 Acceleration\VM-GTL\ae2vm-tmp-grep.txt', $sb.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Host "wrote $($sb.Length) chars (idx=$idx)"