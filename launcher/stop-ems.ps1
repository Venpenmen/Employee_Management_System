& "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\..\server\stop-server.ps1" -Mode portable
exit $LASTEXITCODE
