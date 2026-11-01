function Copy-CompleteFixture($Run,[string]$Fixture,[string]$RuntimeRoot=$Run.root){
 $project=Assert-ArtifactPath $RuntimeRoot $RuntimeRoot -AllowRoot
 if(Test-Path -LiteralPath $Fixture){throw 'Refuse existing fixture'}
 $null=Assert-ArtifactPath $Run.path $Fixture
 [IO.Directory]::CreateDirectory($Fixture)|Out-Null
 $files=@('app/ems-1.2.1-complete.jar','app/version-complete.json','app/build-manifest-complete.json','app/migrations/profile-v1.1.1.sql','bootstrap/schema.sql','bootstrap/runtime-sha256.json','launcher/artifact-common.ps1')
 foreach($entry in (Get-Content -LiteralPath "$project/bootstrap/runtime-sha256.json" -Raw -Encoding UTF8|ConvertFrom-Json)){
  if($entry.path -notmatch '^runtime[/\\]' -or (Get-FileHash -LiteralPath (Join-Path $project $entry.path) -Algorithm SHA256).Hash -ne $entry.sha256){throw 'Immutable runtime inventory invalid'}
  $files+=$entry.path
 }
 foreach($file in Get-ArtifactFiles "$project/server-complete" -Strict){if($file.Extension -eq '.ps1'){$files+=$file.FullName.Substring($project.Length+1)}}
 foreach($name in $files){$source=Assert-ArtifactPath $project (Join-Path $project $name);$dest=Assert-ArtifactPath $Fixture (Join-Path $Fixture $name);[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))|Out-Null;Copy-Item -LiteralPath $source -Destination $dest}
 Write-ArtifactJson "$Fixture/test-fixture.json" @{run=$Run.id;version='1.2.1';buildId='v1.2.1-complete-rc1'}
 Register-ArtifactInstance $Run $Fixture -RuntimeDirectory server-complete
}
