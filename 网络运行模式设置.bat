@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0server\network-mode-gui.ps1"
if errorlevel 1 pause
