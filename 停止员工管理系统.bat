@echo off
setlocal
cd /d "%~dp0"
if exist "%~dp0.git" (
    echo Development mode: stop the running server with Ctrl+C in its launch terminal, or use the IDEA Stop button.
    if /i not "%~1"=="--no-pause" pause
    exit /b 0
)
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\stop-ems.ps1"
set "ems_exit=%errorlevel%"
if not "%ems_exit%"=="0" echo Stop failed. See logs\launcher.log.
if /i not "%~1"=="--no-pause" pause
exit /b %ems_exit%
