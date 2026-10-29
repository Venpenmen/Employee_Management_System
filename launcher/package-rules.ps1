function Get-RcFiles([string]$Root,[string]$Kind){
 $docs=@('README.md','THIRD_PARTY.md','UPGRADE_v1.1.3_to_v1.2.0.md','DEMO_DATABASE_GUIDE.md','RELEASE_NOTES_v1.2.0.md','RELEASE_VALIDATION_v1.2.0.md')
 $publicDocs=@('安装与升级.md','备份与恢复.md','API与权限.md','IDEA调试说明.md','已知边界.md')
 $retired=@('build-release-worker.ps1','clean-legacy-v112.ps1','test-portable.ps1','test-sync.ps1','test-profile.ps1','test-v112.ps1','test-compatibility-v112.ps1')
 foreach($file in Get-ArtifactFiles $Root -Strict){
  $rel=$file.FullName.Substring($Root.Length+1).Replace('\','/')
  if($rel -match '^(\.release|\.rc-work|demo/|data/|logs/|dist/|backend/target/)'){continue}
  $common=$rel -in $docs -or $rel -match '^[^/]+\.bat$' -or ($rel -match '^docs/' -and $file.Name -in $publicDocs) -or $rel -match '^server/(backup-database|check-network|check-server|initialize-server|restore-private|runtime-common|start-server|stop-server|verify-restore|view-logs|monitor-server|database-job|upgrade-installation)\.ps1$' -or $rel -match '^bootstrap/(schema\.sql|runtime-sha256\.json)$' -or $rel -match '^app/(version\.json|build-manifest\.json|migrations/profile-v1\.1\.1\.sql)$'
  if($common -or ($Kind -eq 'runtime' -and ($rel -match '^runtime/' -or $rel -eq 'app/ems-1.2.0-rc1.jar' -or $rel -eq 'launcher/artifact-common.ps1')) -or ($Kind -eq 'source' -and ($rel -match '^backend/(src/|pom\.xml$)' -or $rel -match '^frontend/' -or $rel -eq '.gitignore' -or ($rel -match '^launcher/[^/]+\.(ps1|cjs|json)$' -and $file.Name -notin $retired)))){$file}
 }
}
