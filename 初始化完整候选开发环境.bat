@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0server-complete\initialize-server.ps1" -Mode dev
pause
