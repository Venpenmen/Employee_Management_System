
const fs=require('node:fs'),path=require('node:path');
const [base,out,fixture]=process.argv.slice(2),password=process.env.EMS_TEST_PASSWORD;
const zlib=require('node:zlib');
function zipEntries(buf){const files=new Map();for(let i=0;i<buf.length-46;i++){if(buf.readUInt32LE(i)!==0x02014b50)continue;const method=buf.readUInt16LE(i+10),compressed=buf.readUInt32LE(i+20),nameLen=buf.readUInt16LE(i+28),extraLen=buf.readUInt16LE(i+30),commentLen=buf.readUInt16LE(i+32),local=buf.readUInt32LE(i+42),name=buf.subarray(i+46,i+46+nameLen).toString('utf8');if(local+30>buf.length||buf.readUInt32LE(local)!==0x04034b50)throw Error('Invalid ZIP local entry');const start=local+30+buf.readUInt16LE(local+26)+buf.readUInt16LE(local+28),payload=buf.subarray(start,start+compressed);files.set(name,method===8?zlib.inflateRawSync(payload):method===0?payload:(()=>{throw Error('Unsupported ZIP compression')})());i+=45+nameLen+extraLen+commentLen;}return files}
const checks=[];function check(ok,msg){if(!ok)throw Error(msg);checks.push(msg);console.log('PASS: '+msg)}
class Client{
 constructor(){this.jar={};this.token=''}
 async req(route,method='GET',fields,token=true){
  const headers={Cookie:Object.entries(this.jar).map(([k,v])=>k+'='+v).join('; ')};
  let body;
  if(fields){headers['Content-Type']='application/x-www-form-urlencoded';body=new URLSearchParams(fields);if(token)body.set('_csrf',this.token)}
  const response=await fetch(base+route,{method,headers,body,redirect:'manual'});
  for(const value of response.headers.getSetCookie()){let pair=value.split(';')[0],at=pair.indexOf('=');this.jar[pair.slice(0,at)]=pair.slice(at+1)}
  const text=await response.text();
  for(const tag of text.matchAll(/<input\b[^>]*>/g)){if(/\bname="_csrf"/.test(tag[0])){const m=tag[0].match(/\bvalue="([^"]*)"/);if(m)this.token=m[1]}}
  return {status:response.status,text,location:response.headers.get('location')?new URL(response.headers.get('location'),base).pathname:null,headers:response.headers};
 }
 async login(no){await this.req('/login');let before=this.jar.EMS120U_SERVER;let r=await this.req('/login','POST',{employeeNo:no,password});check(r.status===302,'Login '+no);check(this.jar.EMS120U_SERVER!==before,'Session ID rotated '+no);return this}
}


async function api(c,route,fields){let r=await c.req('/api/admin/database/'+route,'POST',fields);return {r,data:r.status===200?JSON.parse(r.text).data:null}}
async function step(c,action,object=''){let result=await api(c,'reauth',{password,action,object});check(result.r.status===200,'Executive reauthentication '+action);return result.data.grant}
async function wait(c,id,expected){for(let i=0;i<180;i++){let r=await c.req('/api/admin/database/jobs/'+id);if(r.status===200){let j=JSON.parse(r.text).data;if(j.state===expected)return j;if(['FAILED','ROLLED_BACK','RECOVERY_REQUIRED'].includes(j.state)&&j.state!==expected){for(const file of ['restore.log','export.log']){let f=path.join(fixture,'data/server/database-jobs',id,file);if(fs.existsSync(f))fs.copyFileSync(f,path.join(path.dirname(out),'logs','database-'+file))}throw Error('Database job '+j.state)}}await new Promise(r=>setTimeout(r,1000))}throw Error('Database job timed out')}
const backupPassword='SyntheticBackupPassphrase123!';
(async()=>{
 if(!fs.existsSync(path.join(fixture,'test-fixture.json')))throw Error('Synthetic fixture required');
 let admin=await new Client().login('260001'),employee=await new Client().login('260003'),manager=await new Client().login('260002');
 check((await employee.req('/admin/database')).status===403,'Employee cannot open database management');
 check((await manager.req('/admin/database')).status===403,'Manager cannot open database management');
 check((await employee.req('/api/admin/database/reauth','POST',{password,action:'export'})).status===403,'Employee direct database API denied');
 check((await admin.req('/api/admin/database/export','POST',{grant:'forged',backupPassword})).status===403,'Forged step-up grant denied');
 check((await admin.req('/api/admin/database/export','POST',{},false)).status===403,'Database export enforces CSRF');
 check((await api(admin,'reauth',{password:'IncorrectPassword123',action:'export'})).r.status===400,'Incorrect secondary password refused');
 check((await admin.req('/api/me')).status===200,'Failed step-up does not replace executive login');
 let grant=await step(admin,'export'),exp=await api(admin,'export',{grant,backupPassword});check(exp.r.status===200,'Encrypted export queued');let exportId=exp.data.task;
 check((await api(admin,'export',{grant,backupPassword})).r.status===403,'Consumed export grant cannot be replayed');
 await wait(admin,exportId,'SUCCEEDED');
 let download=await fetch(base+'/api/admin/database/jobs/'+exportId+'/download',{headers:{Cookie:Object.entries(admin.jar).map(([k,v])=>k+'='+v).join('; ')},redirect:'manual'});
 check(download.status===200,'Encrypted backup download succeeds');let bytes=Buffer.from(await download.arrayBuffer());
 check(bytes.subarray(0,4).toString('binary')==='PK\x03\x04','Download is a ZIP archive');const entries=zipEntries(bytes);check(['backup.emsbk','manifest.json','checksums.sha256','README.txt'].every(n=>entries.has(n)),'Backup ZIP includes encrypted archive and verification files');bytes=entries.get('backup.emsbk');check(bytes.subarray(0,8).toString()==='EMSBK001'&&!bytes.includes(Buffer.from('测试高层')),'Export has encrypted versioned format and no plaintext names');
 async function preview(pass){
  let nonce=await step(admin,'inspect'),body=new FormData();body.set('_csrf',admin.token);body.set('grant',nonce);body.set('backupPassword',pass);body.set('file',new Blob([bytes]),'synthetic.emsbk');
  let r=await fetch(base+'/api/admin/database/import',{method:'POST',headers:{Cookie:Object.entries(admin.jar).map(([k,v])=>k+'='+v).join('; ')},body,redirect:'manual'});check(r.status===200,'Import preview queued');return (await r.json()).data.task;
 }
 let wrong=await preview('IncorrectBackupPassphrase123');await wait(admin,wrong,'FAILED');check((await admin.req('/api/me')).status===200,'Wrong decrypt key leaves current database and login valid');
 let restored=await preview(backupPassword);await wait(admin,restored,'PREVIEW_READY');check((await admin.req('/api/me')).status===200,'Isolated preview does not switch current database');
 let nonce=await step(admin,'commit',restored),confirmation=await api(admin,'import/'+restored+'/confirm',{grant:nonce,restoredEmployeeNo:'260001',restoredPassword:password});check(confirmation.r.status===200,'Validated import confirmation accepted');
 for(let i=0;i<120;i++){try{let s=JSON.parse(fs.readFileSync(path.join(fixture,'data/server/database-jobs',restored,'status.json'),'utf8'));if(s.state==='SUCCEEDED'&&!fs.existsSync(path.join(fixture,'data/server/maintenance.flag')))break;if(['FAILED','ROLLED_BACK','RECOVERY_REQUIRED'].includes(s.state))throw Error('Switch '+s.state);if(i===119)throw Error('Switch timeout')}catch(e){if(!e.message.includes('ENOENT'))throw e}await new Promise(r=>setTimeout(r,1000))}
 check((await admin.req('/api/me')).status===401,'Import invalidates pre-switch executive session');
 check((await employee.req('/api/me')).status===401,'Import invalidates pre-switch employee session');
 admin=await new Client().login('260001');check((await admin.req('/profile')).status===200,'Restored BCrypt account can log in and view profile');
 let stamp=fs.readFileSync(path.join(fixture,'data/server/active-schema'),'utf8').trim();check(/^ems_up_server_v120_g[a-f0-9]{12}$/.test(stamp),'Controlled switch uses a distinct generation');
 const backups=fs.readdirSync(path.join(fixture,'data/server/backups'));check(backups.length>0,'Pre-import encrypted automatic backup exists');
 // A second isolated import injects a post-pointer failure and must roll back safely.
 const second=await preview(backupPassword);await wait(admin,second,'PREVIEW_READY');
 fs.writeFileSync(path.join(fixture,'data/server/inject-switch-failure'),'owned synthetic fault');
 nonce=await step(admin,'commit',second);await api(admin,'import/'+second+'/confirm',{grant:nonce,restoredEmployeeNo:'260001',restoredPassword:password});
 for(let i=0;i<120;i++){let j=JSON.parse(fs.readFileSync(path.join(fixture,'data/server/database-jobs',second,'status.json'),'utf8'));if(j.state==='ROLLED_BACK'&&!fs.existsSync(path.join(fixture,'data/server/maintenance.flag')))break;if(j.state==='RECOVERY_REQUIRED'||i===119)throw Error('Rollback failed');await new Promise(r=>setTimeout(r,1000))}
 fs.unlinkSync(path.join(fixture,'data/server/inject-switch-failure'));
 check(fs.readFileSync(path.join(fixture,'data/server/active-schema'),'utf8').trim()===stamp,'Injected switch failure restores previous database pointer');
 admin=await new Client().login('260001');check((await admin.req('/profile')).status===200,'Application works after controlled rollback');

 // Reproduce a pre-fix interrupted terminal-journal write, using this owned fixture only.
 const staleJournal=path.join(fixture,'data/server/database-jobs',second,'switch-journal.json');
 const stale=JSON.parse(fs.readFileSync(staleJournal,'utf8'));stale.phase='POINTER_SWITCHED';fs.writeFileSync(staleJournal,JSON.stringify(stale));

 const newer=await preview(backupPassword);await wait(admin,newer,'PREVIEW_READY');
 nonce=await step(admin,'commit',newer);await api(admin,'import/'+newer+'/confirm',{grant:nonce,restoredEmployeeNo:'260001',restoredPassword:password});
 for(let i=0;i<120;i++){let j=JSON.parse(fs.readFileSync(path.join(fixture,'data/server/database-jobs',newer,'status.json'),'utf8'));if(j.state==='SUCCEEDED'&&!fs.existsSync(path.join(fixture,'data/server/maintenance.flag')))break;if(['FAILED','ROLLED_BACK','RECOVERY_REQUIRED'].includes(j.state)||i===119)throw Error('Post-rollback switch failed');await new Promise(r=>setTimeout(r,1000))}
 stamp=JSON.parse(fs.readFileSync(path.join(fixture,'data/server/database-jobs',newer,'result.json'),'utf8')).target;
 check(fs.readFileSync(path.join(fixture,'data/server/active-schema'),'utf8').trim()===stamp,'Old rollback journal cannot overwrite a newer committed generation');
 admin=await new Client().login('260001');

 const interrupted=await preview(backupPassword);await wait(admin,interrupted,'PREVIEW_READY');
 fs.writeFileSync(path.join(fixture,'data/server/pause-switch-test'),'owned synthetic interruption');
 nonce=await step(admin,'commit',interrupted);await api(admin,'import/'+interrupted+'/confirm',{grant:nonce,restoredEmployeeNo:'260001',restoredPassword:password});
 let checkpoint=false;for(let i=0;i<120;i++){if(fs.existsSync(path.join(fixture,'data/server/database-jobs',interrupted,'broker-paused'))){checkpoint=true;break}await new Promise(r=>setTimeout(r,1000))}
 check(checkpoint,'Owned broker reached persistent pre-finalization checkpoint');
 const ps=path.join(process.env.SystemRoot,'System32/WindowsPowerShell/v1.0/powershell.exe');
 const {execFileSync}=require('node:child_process');
 try{execFileSync(ps,['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(__dirname,'interrupt-database-test.ps1'),'-Fixture',fixture,'-JobId',interrupted],{windowsHide:true,stdio:'pipe',timeout:30000})}
 finally{const pause=path.join(fixture,'data/server/pause-switch-test');if(fs.existsSync(pause))fs.unlinkSync(pause)}
 execFileSync(ps,['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(fixture,'server/start-server.ps1'),'-Address','127.0.0.1','-Port',new URL(base).port,'-NoBrowser'],{windowsHide:true,stdio:'ignore',timeout:120000});
 check(fs.readFileSync(path.join(fixture,'data/server/active-schema'),'utf8').trim()===stamp,'Startup recovers original pointer after actual broker termination');
 check(JSON.parse(fs.readFileSync(path.join(fixture,'data/server/database-jobs',interrupted,'status.json'),'utf8')).state==='ROLLED_BACK','Interrupted operation has explicit recovered result');
 admin=await new Client().login('260001');check((await admin.req('/profile')).status===200,'Recovered application works after actual broker interruption');
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks},null,2));
})().catch(e=>{console.error(e.stack);process.exitCode=1});
