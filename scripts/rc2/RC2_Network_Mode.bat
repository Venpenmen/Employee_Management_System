@echo off
set "EMS_PROJECT_ROOT=%~dp0..\..\"
setlocal
cd /d "%EMS_PROJECT_ROOT%"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%EMS_PROJECT_ROOT%server-complete-rc2\network-mode-gui.ps1" 
set "EMS_RC2_EXIT=%ERRORLEVEL%"
pause
exit /b %EMS_RC2_EXIT%
