@echo off
cd /d "%~dp0"
set GRADLE_OPTS=-Dfile.encoding=UTF-8
echo === RUN gradlew test ===
call gradlew.bat cleanTest test --no-daemon > build-test-out.txt 2>&1
echo EXIT=%ERRORLEVEL% >> build-test-out.txt
echo Done. build-test-out.txt contains the log.
exit /b %ERRORLEVEL%