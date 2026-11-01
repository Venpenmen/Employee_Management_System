@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0server-complete\network-mode-gui.ps1"
if errorlevel 1 pause
