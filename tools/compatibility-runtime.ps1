# Resolve only a verified local worker. A bad newer artifact never falls back.
function Resolve-CompatibilityWorkerJar([string]$Root){
 foreach($candidate in @(@('ems-1.2.1-complete-rc2.jar','build-manifest-complete-rc2.json','v1.2.1-complete-rc2'),@('ems-1.2.1-complete.jar','build-manifest-complete.json','v1.2.1-complete-rc1'))){
  $jar=Join-Path $Root ('app/'+$candidate[0]);$manifest=Join-Path $Root ('app/'+$candidate[1])
  if(Test-Path -LiteralPath $jar){
   if(-not(Test-Path -LiteralPath $manifest)){throw 'Local compatibility worker manifest is missing'}
   $m=Get-Content -LiteralPath $manifest -Raw -Encoding UTF8|ConvertFrom-Json
   if($m.buildId -ne $candidate[2] -or $m.jar -ne $candidate[0] -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $m.sha256){throw 'Local compatibility worker identity/hash mismatch; no fallback'}
   return $jar
  }
 }
 return $null
}
