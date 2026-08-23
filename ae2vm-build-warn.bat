@echo off
cd /d "%~dp0"
set GRADLE_OPTS=-Dfile.encoding=UTF-8
echo === BUILD ae2vm WARN variant (no-detect) ===
call gradlew.bat -PblockedMode=warn jar copyJarToMods --no-daemon > build-out.txt 2>&1
echo EXIT=%ERRORLEVEL% >> build-out.txt
echo Done. build-out.txt contains the log.
exit /b %ERRORLEVEL%