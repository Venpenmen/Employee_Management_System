function Get-RcFiles([string]$Root,[string]$Kind){
 $docs=@('CHANGELOG.md','README.md','THIRD_PARTY.md','UPGRADE_v1.1.3_to_v1.2.1.md','RELEASE_NOTES_v1.2.1.md','RELEASE_VALIDATION_v1.2.1.md')
 $publicDocs=@('UPGRADE_TO_v1.2.1.md','RC2_DATABASE_MATRIX.md','RC2_RECOVERY_POINT.json','SECURITY_OPERATIONS.md','RC2_ACCEPTANCE_CHECKLIST.md','BASELINE_MANIFEST.json','ORIGINAL_PROTECTION_REPORT.md','LOCK_MATRIX.md','TEST_REPORT.md','UPGRADE_AND_ROLLBACK.md','V121_DIFFERENCE_MATRIX.md','V121_IMPLEMENTATION_SCOPE.md','DEVELOPMENT_PROGRESS.md','安装与升级.md','备份与恢复.md','API与权限.md','IDEA调试说明.md','已知边界.md')
 $retired=@('build-release-worker.ps1','clean-legacy-v112.ps1','test-portable.ps1','test-sync.ps1','test-profile.ps1','test-v112.ps1','test-compatibility-v112.ps1')
 $publicDocs+=@('DATABASE_COMPATIBILITY_P0_20261028.md','DATABASE_COMPATIBILITY_P0_RESULTS_20261028.md')
 $publicDocs+=@('RC2_UNIFIED_PROGRESS.md','RC2_EVIDENCE_RETENTION_INCIDENT.md','SAKURA_FRP_HTTPS_ADAPTATION.md','NETWORK_MODE_INTERNAL_TEST.md','FINAL_DELIVERY_REPORT.md')
 # Enumerate only public scopes; never traverse data, credentials, Git internals or managed test instances.
 $candidates=New-Object Collections.Generic.List[IO.FileInfo]
 foreach($file in Get-ChildItem -LiteralPath $Root -File -Force){if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked root artifact refused'};$candidates.Add($file)}
 $scopes=@('docs','server','bootstrap','app')
 if($Kind -eq 'runtime'){$scopes+=@('runtime','launcher')}else{$scopes+=@('backend/src','frontend','launcher')}
 foreach($scope in $scopes){foreach($file in Get-ArtifactFiles (Join-Path $Root $scope) -Strict){$candidates.Add($file)}}
 if($Kind -eq 'source'){$candidates.Add((Get-Item -LiteralPath (Join-Path $Root 'backend/pom.xml')))}
 $runtimeAllowed=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
 if($Kind -eq 'runtime'){foreach($record in (Get-Content (Join-Path $Root 'bootstrap/runtime-sha256.json') -Raw -Encoding UTF8|ConvertFrom-Json)){$null=$runtimeAllowed.Add($record.path.Replace('\','/'))}}
 foreach($file in $candidates){
  $rel=$file.FullName.Substring($Root.Length+1).Replace('\','/')
  if($Kind -eq 'runtime' -and $rel -match '^runtime/' -and -not $runtimeAllowed.Contains($rel)){throw "Runtime file not in verified inventory: $rel"}
  if($rel -match '^(\.release|\.rc-work|demo/|data/|logs/|dist/|backend/target/)'){continue}
  $common=$rel -in $docs -or $rel -match '^[^/]+\.bat$' -or ($rel -match '^docs/' -and $file.Name -in $publicDocs) -or $rel -match '^server/(backup-database|check-upgrade|check-network|check-server|initialize-server|restore-private|runtime-common|start-server|stop-server|verify-restore|view-logs|monitor-server|database-job|upgrade-installation|network-mode-gui)\.ps1$' -or $rel -match '^bootstrap/(schema\.sql|runtime-sha256\.json)$' -or $rel -match '^app/(version\.json|build-manifest\.json|migrations/profile-v1\.1\.1\.sql)$'
  if($common -or ($Kind -eq 'runtime' -and ($rel -match '^runtime/' -or $rel -eq 'app/ems-1.2.1-next-rc2.jar' -or $rel -eq 'launcher/artifact-common.ps1')) -or ($Kind -eq 'source' -and ($rel -match '^backend/(src/|pom\.xml$)' -or $rel -match '^frontend/' -or $rel -eq '.gitignore' -or ($rel -match '^launcher/[^/]+\.(ps1|cjs|json)$' -and $file.Name -notin $retired)))){$file}
 }
}
