@echo off
setlocal
cd /d "%~dp0"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\start-ems.ps1"
set "ems_exit=%errorlevel%"
if not "%ems_exit%"=="0" (
    echo.
    echo Startup failed. See logs\launcher.log and logs\application-error.log.
    pause
)
exit /b %ems_exit%
