@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server\view-logs.ps1" 
set "EMS_EXIT=%ERRORLEVEL%"
pause
exit /b %EMS_EXIT%
