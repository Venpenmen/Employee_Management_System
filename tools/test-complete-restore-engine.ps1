param()
$ErrorActionPreference='Stop'
if(-not $env:EMS_DEVELOPMENT_TASK_ID){throw 'Managed task required'}
# LineageTracker deliberately binds the controlled legacy layout. This SQL test
# exercises that layout with current test/classes, not the complete runtime layout.
& "$PSScriptRoot/test-compatibility-catalog.ps1" -RestoreEngine
exit $LASTEXITCODE
