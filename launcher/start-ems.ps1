param([switch]$NoBrowser)
& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\..\server\start-server.ps1" -Mode portable -NoBrowser:$NoBrowser
exit $LASTEXITCODE
