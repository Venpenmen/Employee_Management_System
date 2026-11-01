"""Offline pure PS policy/config fixtures; never sources runtime-common or connects a DB."""
import unittest,subprocess,tempfile,json,os
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
PS=Path(os.environ.get('EMS_PWSH_EXE',r'C:/Users/SheSh/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe'))
class Scripts(unittest.TestCase):
 def ps(self,code,ok=True):
  self.assertTrue(PS.is_file(),'Existing local PS7 required; no download')
  result=subprocess.run([str(PS),'-NoProfile','-Command',"$ErrorActionPreference='Stop';"+code],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,encoding='utf-8',errors='replace')
  if ok:self.assertEqual(0,result.returncode,result.stderr)
  else:self.assertNotEqual(0,result.returncode)
  return result.stdout
 def policy(self,expr):return self.ps(". ./server-complete-rc2/network-policy.ps1;"+expr)
 def test_all_scripts_parse(self):
  self.ps("Get-ChildItem ./server-complete-rc2 -Filter *.ps1 | ForEach-Object {$tokens=$null;$errors=$null;[void][Management.Automation.Language.Parser]::ParseFile($_.FullName,[ref]$tokens,[ref]$errors);if($errors){throw ($errors|Out-String)}}")
 def test_default_safe_needs_no_certificate(self):
  data=json.loads(self.policy("Resolve-Rc2Network $null 'server'|ConvertTo-Json"));self.assertEqual('safe',data['mode']);self.assertFalse(data['httpsEnabled'])
 def test_compat_requires_explicit_ack(self):
  self.ps(". ./server-complete-rc2/network-policy.ps1;Resolve-Rc2Network ([pscustomobject]@{format=1;mode='compat';generation='test';testOrigin='http://ems-test.invalid'}) 'server'",False)
 def test_compat_requires_exact_remote_http_origin(self):
  self.policy("foreach($origin in @('','https://ems-test.invalid','http://127.0.0.1:8483','http://localhost:8483','http://ems-test.invalid/path','http://u:p@ems-test.invalid','http://ems-test.invalid/?x=1')){$refused=$false;try{Resolve-Rc2Network ([pscustomobject]@{format=1;mode='compat';generation='test';compatAcknowledged=$true;testOrigin=$origin}) 'server'|Out-Null}catch{$refused=$true};if(-not $refused){throw 'Unsafe Origin accepted'}}")
 def test_valid_compat_and_dev_rejection(self):
  self.policy("$v=[pscustomobject]@{format=1;mode='compat';generation='test';compatAcknowledged=$true;testOrigin='http://ems-test.invalid:9999'};$p=Resolve-Rc2Network $v 'server';if($p.mode -ne 'compat'){throw 'Mode lost'};foreach($m in @('dev','portable')){$bad=$false;try{Resolve-Rc2Network $v $m|Out-Null}catch{$bad=$true};if(-not $bad){throw 'Nonserver compatibility accepted'}}")
 def test_unknown_keys_and_types_rejected(self):
  self.policy("foreach($v in @([pscustomobject]@{format=1;mode='safe';generation='test';surprise=1},[pscustomobject]@{format=1;mode='safe';generation='test';httpsEnabled='false'},[pscustomobject]@{format=1;mode='safe';generation='test';httpsPort='8484'})){$bad=$false;try{Resolve-Rc2Network $v 'server'|Out-Null}catch{$bad=$true};if(-not $bad){throw 'Invalid config accepted'}}")
 def test_remote_material_failure_is_not_http_downgrade(self):
  data=json.loads(self.policy("Resolve-Rc2Network ([pscustomobject]@{format=1;mode='safe';generation='test';httpsEnabled=$true;secureOrigin='https://ems-test.invalid'}) 'server'|ConvertTo-Json"));self.assertTrue(data['httpsEnabled']);self.assertEqual('safe',data['mode'])
 def test_atomic_save_rotation_and_live_lease_refusal(self):
  with tempfile.TemporaryDirectory(prefix='rc2-policy-',dir=ROOT/'.codex-continuation'/'test-work') as temporary:
   root=Path(temporary);(root/'server-complete-rc2').mkdir();(root/'data').mkdir()
   path=str(root).replace("'","''")
   self.policy("$script:EmsRoot='"+path+"';$script:DataDir=Join-Path $script:EmsRoot 'data';function Enter-EmsLock{[IO.File]::Open((Join-Path $script:DataDir 'test.lock'),'OpenOrCreate','ReadWrite','None')};function Read-EmsState{return $null};function Assert-ArtifactPath($root,$path){return $path};function Write-Utf8($path,$text){[IO.File]::WriteAllText($path,$text)};$v=Resolve-Rc2Network $null 'server';Save-Rc2Network $v;$file=Join-Path $script:EmsRoot 'server-complete-rc2/network-mode.json';$one=[IO.File]::ReadAllText($file);Save-Rc2Network $v;$two=[IO.File]::ReadAllText($file);if($one -eq $two){throw 'Generation not rotated'};$lease=[IO.File]::Open((Join-Path $script:DataDir 'application.lease'),'OpenOrCreate','ReadWrite','None');try{$bad=$false;try{Save-Rc2Network $v}catch{$bad=$true};if(-not $bad){throw 'Live lease overwritten'};if([IO.File]::ReadAllText($file) -ne $two){throw 'Failed save changed config'}}finally{$lease.Dispose()}")
 def test_direct_idea_lease_prevents_database_stop_and_ready_removal(self):
  with tempfile.TemporaryDirectory(prefix='rc2-lease-',dir=ROOT/'.codex-continuation'/'test-work') as temporary:
   directory=str(Path(temporary)).replace("'","''")
   self.ps("$script:DataDir='"+directory+"';$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path (Get-Location) 'server-complete-rc2/runtime-common.ps1'),[ref]$tokens,[ref]$errors);foreach($f in $ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in @('Stop-EmsApplication','Stop-EmsDatabase')},$true)){Invoke-Expression $f.Extent.Text};function Get-OwnedProcess($identity){return $null};function Save-EmsState($state){$script:saved=$true};$script:saved=$false;$state=[pscustomobject]@{app=$null;database=$null};[IO.File]::WriteAllText((Join-Path $script:DataDir 'ready'),'synthetic-sentinel');$lease=[IO.File]::Open((Join-Path $script:DataDir 'application.lease'),'OpenOrCreate','ReadWrite','None');try{foreach($name in @('Stop-EmsApplication','Stop-EmsDatabase')){$bad=$false;try{& $name $state}catch [IO.IOException]{$bad=$true};if(-not $bad){throw 'Direct IDEA lease ignored'}};if($script:saved -or [IO.File]::ReadAllText((Join-Path $script:DataDir 'ready')) -ne 'synthetic-sentinel'){throw 'Held IDEA resources changed'}}finally{$lease.Dispose()};Stop-EmsDatabase $state;if(-not $script:saved){throw 'Free owned lease did not allow ordinary state cleanup'}")
 def test_worker_prefers_verified_rc2_and_refuses_bad_newer_artifact(self):
  with tempfile.TemporaryDirectory(prefix='rc2-worker-',dir=ROOT/'.codex-continuation'/'test-work') as temporary:
   directory=str(Path(temporary)).replace("'","''")
   self.ps(". ./tools/compatibility-runtime.ps1;$root='"+directory+"';[IO.Directory]::CreateDirectory((Join-Path $root 'app'))|Out-Null;if(Resolve-CompatibilityWorkerJar $root){throw 'Unexpected worker'};foreach($suffix in @('','-rc2')){$name='ems-1.2.1-complete'+$suffix+'.jar';$file=Join-Path $root ('app/'+$name);[IO.File]::WriteAllText($file,'synthetic-'+$suffix);$m=@{buildId=$(if($suffix){'v1.2.1-complete-rc2'}else{'v1.2.1-complete-rc1'});jar=$name;sha256=(Get-FileHash $file).Hash};[IO.File]::WriteAllText((Join-Path $root ('app/build-manifest-complete'+$suffix+'.json')),($m|ConvertTo-Json))};if(-not (Resolve-CompatibilityWorkerJar $root).EndsWith('complete-rc2.jar')){throw 'Wrong selected worker'};[IO.File]::AppendAllText((Join-Path $root 'app/ems-1.2.1-complete-rc2.jar'),'tamper');$bad=$false;try{Resolve-CompatibilityWorkerJar $root|Out-Null}catch{$bad=$true};if(-not $bad){throw 'Bad RC2 fell back to old binary'}")
 def test_console_code_refuses_redirected_output(self):
  self.ps("& ./server-complete-rc2/local-entry-code.ps1",False)
if __name__=='__main__':unittest.main()
