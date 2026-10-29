@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server\monitor-server.ps1" -Mode server
if errorlevel 1 pause
