@echo off
setlocal
cd /d "%~dp0"
if exist "%~dp0.git" (
    echo Source checkout: starting development mode (same database/profile as IDEA).
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\start-development.ps1"
) else (
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\start-ems.ps1"
)
set "ems_exit=%errorlevel%"
if not "%ems_exit%"=="0" (
    echo Startup failed; inspect the output above.
    pause
)
exit /b %ems_exit%
