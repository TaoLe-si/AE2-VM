# kill-gradle.ps1 — 杀掉挂起的 Gradle wrapper java 进程（未走代理卡在下载上）
$ErrorActionPreference = 'SilentlyContinue'

$targets = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'GradleWrapperMain' }

if ($targets) {
    $count = 0
    foreach ($t in $targets) {
        Write-Host ("Killing java PID " + $t.ProcessId)
        Stop-Process -Id $t.ProcessId -Force -ErrorAction SilentlyContinue
        $count++
    }
    Write-Host "Killed $count GradleWrapperMain process(es)"
} else {
    Write-Host "No GradleWrapperMain java process found. All java processes:"
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
        Write-Host ("java PID " + $_.ProcessId + " : " + $_.CommandLine)
    }
}
