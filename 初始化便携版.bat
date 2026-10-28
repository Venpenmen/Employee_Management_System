@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server\initialize-server.ps1" -Mode portable
set "EMS_EXIT=%ERRORLEVEL%"
pause
exit /b %EMS_EXIT%
