@echo off
setlocal
cd /d "%~dp0"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\stop-ems.ps1"
set "ems_exit=%errorlevel%"
if not "%ems_exit%"=="0" echo Stop failed. See logs\launcher.log.
echo.
if /i not "%~1"=="--no-pause" pause
exit /b %ems_exit%
