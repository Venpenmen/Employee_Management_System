@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server\check-upgrade.ps1"
pause
