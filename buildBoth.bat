@echo off
setlocal
cd /d "%~dp0"

REM ============================================================
REM buildBoth.bat — 每次编译出两个版本：
REM   1) ae2vm-<ver>.jar           针对检测版（blockedMode=crash，检测到该作者 mod 游戏闪退）
REM   2) ae2vm-nodetect-<ver>.jar  无针对检测版（blockedMode=warn，只警告不闪退）
REM 注意：故意闪退的是【游戏运行时】（throw RuntimeException），
REM       编译过程本身永不失败。两个 jar 共用 modId=ae2vm，
REM       启动游戏前请删掉不用那个，否则会 duplicate modId 报错。
REM ============================================================

echo === [1/3] Bump version +0.0.1 ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bump-version.ps1"
if errorlevel 1 goto :error

echo.
echo === [2/3] Build CRASH variant (ae2vm / 针对检测) ===
call gradlew.bat -PblockedMode=crash jar copyJarToMods --no-daemon
if errorlevel 1 goto :error

echo.
echo === [3/3] Build WARN variant (ae2vm-nodetect / 无针对检测) ===
call gradlew.bat -PblockedMode=warn jar copyJarToMods --no-daemon
if errorlevel 1 goto :error

echo.
echo ============================================================
echo  Build OK — 两个 jar 已复制到 mods 文件夹：
echo   - ae2vm-<version>.jar            （针对检测，会闪退）
echo   - ae2vm-nodetect-<version>.jar   （无针对检测，只警告）
echo  启动游戏前只保留其中一个！
echo ============================================================
goto :eof

:error
echo.
echo Build FAILED — 这不是"故意闪退"。故意闪退发生在游戏运行时，不是编译器。
exit /b 1
