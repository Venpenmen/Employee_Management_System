@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server\stop-server.ps1" 
set "EMS_EXIT=%ERRORLEVEL%"
pause
exit /b %EMS_EXIT%
