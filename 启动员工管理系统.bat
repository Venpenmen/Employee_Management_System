@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\start-ems.ps1"
if errorlevel 1 (
    echo.
    echo Startup failed. Check logs\launcher.log and logs\application-error.log.
    pause
)
endlocal
