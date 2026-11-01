param([string]$PythonExe,[string]$ProjectRoot=(Split-Path $PSScriptRoot))
$ErrorActionPreference='Stop'
if(-not $PythonExe){$command=Get-Command python -ErrorAction SilentlyContinue;if(-not $command -or $command.Source -match '\\WindowsApps\\'){throw 'Provide an existing local Python with -PythonExe; no automatic installation or Store alias'};$PythonExe=$command.Source}
& $PythonExe (Join-Path $PSScriptRoot 'continuation.py') resume --root $ProjectRoot
exit $LASTEXITCODE
