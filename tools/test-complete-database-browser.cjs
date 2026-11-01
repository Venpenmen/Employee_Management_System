const fs=require('node:fs'),path=require('node:path'),{spawn,execFileSync}=require('node:child_process');
const [base,out,browserExe,profile]=process.argv.slice(2),password=process.env.EMS_TEST_PASSWORD;
const checks=[],pending=new Map();let chrome,ws,seq=0,cancelled=0;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function check(ok,msg){if(!ok)throw Error(msg);checks.push(msg);console.log('PASS: '+msg)}
function command(method,params={},sessionId){return new Promise((resolve,reject)=>{const id=++seq;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timed out'));},15000);pending.set(id,{resolve,reject,timer});ws.send(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})}));})}
async function evaluate(id,expression){const r=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true},id);if(r.exceptionDetails)throw Error('Browser evaluation failed');return r.result.value}
async function until(test,name){for(let i=0;i<150;i++){if(await test())return;await sleep(100)}throw Error('Timed out: '+name)}
async function page(contextId,route){const t=await command('Target.createTarget',{url:'about:blank',browserContextId:contextId});const attached=await command('Target.attachToTarget',{targetId:t.targetId,flatten:true});await command('Page.enable',{},attached.sessionId);await navigate(attached.sessionId,route);return attached.sessionId}
async function navigate(id,route){await command('Page.navigate',{url:base+route},id);await until(()=>evaluate(id,"document.readyState==='complete'&&location.href.startsWith("+JSON.stringify(base)+")&&"+(route==='/profile'?"(location.pathname==='/profile'||location.pathname==='/login')":"location.pathname==="+JSON.stringify(route.split('?')[0]))),'page ready')}
async function login(id){
 await navigate(id,'/login');
 await evaluate(id,"(()=>{document.querySelector('[name=employeeNo]').value='260003';document.querySelector('[name=password]').value="+JSON.stringify(password)+";document.querySelector('form').requestSubmit()})()");
 await until(()=>evaluate(id,"location.pathname==='/dashboard'&&document.readyState==='complete'"),'login');
}
(async()=>{
 fs.mkdirSync(profile,{recursive:true});
 chrome=spawn(browserExe,['--headless=new','--remote-debugging-port=0','--user-data-dir='+profile,'--disable-background-networking','--disable-component-update','--disable-sync','--no-proxy-server','--no-first-run','about:blank'],{windowsHide:true,stdio:'ignore'});
 const ps=path.join(process.env.SystemRoot,'System32','WindowsPowerShell','v1.0','powershell.exe');
 const identity=JSON.parse(execFileSync(ps,['-NoProfile','-Command',"$p=Get-Process -Id "+chrome.pid+";@{id=$p.Id;ticks=$p.StartTime.ToUniversalTime().Ticks.ToString();exe=$p.Path;purpose='browser'}|ConvertTo-Json -Compress"],{encoding:'utf8',windowsHide:true}));
 fs.writeFileSync(path.join(path.dirname(out),'process-session-browser.json'),JSON.stringify(identity));
 await until(()=>fs.existsSync(path.join(profile,'DevToolsActivePort')),'debug port');
 const lines=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').trim().split('\n');
 ws=new WebSocket('ws://127.0.0.1:'+lines[0]+lines[1]);
 await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject});
 ws.onmessage=event=>{const m=JSON.parse(event.data);if(m.method==='Page.javascriptDialogOpening'){cancelled++;command('Page.handleJavaScriptDialog',{accept:false},m.sessionId).catch(()=>{});}if(m.id){const p=pending.get(m.id);if(p){clearTimeout(p.timer);pending.delete(m.id);m.error?p.reject(Error('CDP command failed')):p.resolve(m.result)}}};

 const context=(await command('Target.createBrowserContext')).browserContextId;
 const id=await page(context,'/login');
 await evaluate(id,"(()=>{document.querySelector('[name=employeeNo]').value='260001';document.querySelector('[name=password]').value="+JSON.stringify(password)+";document.querySelector('form').requestSubmit()})()");
 await until(()=>evaluate(id,"location.pathname==='/dashboard'&&document.readyState==='complete'"),'executive login');
 await navigate(id,'/admin/database');
 const downloads=path.join(path.dirname(out),'browser-downloads');fs.mkdirSync(downloads,{recursive:true});
 await command('Browser.setDownloadBehavior',{behavior:'allow',browserContextId:context,downloadPath:downloads,eventsEnabled:true});
 check(await evaluate(id,"document.querySelector('[name=targetVersion]').value==='current'"),'Browser defaults to standard backup');
 await evaluate(id,"(()=>{const f=document.querySelector('#export');f.querySelector('[name=targetVersion]').value='1.2.0';f.querySelector('[name=password]').value="+JSON.stringify(password)+";f.querySelector('[name=backupPassword]').value='BrowserCompleteBackup91!';f.querySelector('[name=confirm]').value='BrowserCompleteBackup91!';f.requestSubmit()})()");
 await until(()=>evaluate(id,"!document.querySelector('#download').hidden"),'compatibility export ready');
 await until(()=>Promise.resolve(fs.readdirSync(downloads).some(n=>n.endsWith('.zip'))),'encrypted pair download');
 check(true,'Actual browser form reauthenticates, exports and downloads compatibility ZIP');
 const archive=fs.readFileSync(path.join(downloads,fs.readdirSync(downloads).find(n=>n.endsWith('.zip'))));
 const zlib=require('node:zlib'),files=new Map();for(let i=0;i<archive.length-46;i++){if(archive.readUInt32LE(i)!==0x02014b50)continue;let method=archive.readUInt16LE(i+10),size=archive.readUInt32LE(i+20),nl=archive.readUInt16LE(i+28),el=archive.readUInt16LE(i+30),cl=archive.readUInt16LE(i+32),offset=archive.readUInt32LE(i+42),name=archive.subarray(i+46,i+46+nl).toString(),begin=offset+30+archive.readUInt16LE(offset+26)+archive.readUInt16LE(offset+28),value=archive.subarray(begin,begin+size);files.set(name,method===8?zlib.inflateRawSync(value):value);i+=45+nl+el+cl;}
 check(files.has('base-v120.emsbk')&&files.has('extensions.emsbk'),'Browser download contains exact companion pair');
 const baseFile=path.join(downloads,'base-v120.emsbk'),extensionFile=path.join(downloads,'extensions.emsbk');fs.writeFileSync(baseFile,files.get('base-v120.emsbk'));fs.writeFileSync(extensionFile,files.get('extensions.emsbk'));
 async function file(selector,filename){const doc=await command('DOM.getDocument',{},id);const node=await command('DOM.querySelector',{nodeId:doc.root.nodeId,selector},id);await command('DOM.setFileInputFiles',{nodeId:node.nodeId,files:[filename]},id);}
 await file('#import [name=file]',baseFile);await file('#import [name=extension]',extensionFile);
 async function submitImport(){await evaluate(id,"(()=>{const f=document.querySelector('#import');f.querySelector('[name=password]').value="+JSON.stringify(password)+";f.querySelector('[name=backupPassword]').value='BrowserCompleteBackup91!';f.requestSubmit()})()");await until(()=>evaluate(id,"!document.querySelector('#commit').hidden"),'browser isolated preview');}
 await submitImport();check(await evaluate(id,"JSON.parse(document.querySelector('#preview').textContent).recoveryMode==='ORIGINAL_PAIR'"),'Browser shows actual paired preview and extension mapping');
 check(await evaluate(id,"document.querySelector('#base-choice').hidden&&document.querySelector('#skip-choice').hidden"),'Browser does not request irrelevant loss/skip choices');
 await evaluate(id,"(()=>{const f=document.querySelector('#commit');f.querySelector('[name=password]').value="+JSON.stringify(password)+";f.querySelector('[name=restoredEmployeeNo]').value='260001';f.querySelector('[name=restoredPassword]').value="+JSON.stringify(password)+";f.requestSubmit()})()");
 await until(()=>Promise.resolve(cancelled>0),'actual cancel dialog');check(await evaluate(id,"!document.querySelector('#commit button').disabled"),'Cancelling actual confirmation does not enter saving state');
 check(await evaluate(id,"fetch('/api/me').then(r=>r.status)")===200,'Cancelled confirmation keeps current login and active database');
 await evaluate(id,"document.querySelector('#import [name=extension]').value=''");await file('#import [name=file]',baseFile);await submitImport();
 check(await evaluate(id,"!document.querySelector('#base-choice').hidden&&!document.querySelector('[name=acknowledgeBaseOnly]').checked"),'Browser requires unchecked explicit base-only acknowledgement');
 await evaluate(id,"(()=>{const f=document.querySelector('#commit');f.querySelector('[name=password]').value="+JSON.stringify(password)+";f.querySelector('[name=restoredEmployeeNo]').value='260001';f.querySelector('[name=restoredPassword]').value="+JSON.stringify(password)+";f.requestSubmit()})()");
 check(await evaluate(id,"document.querySelector('#status').textContent.includes('明确确认')&&!document.querySelector('#commit button').disabled"),'Missing acknowledgement is stopped before reauth/submission');
 await evaluate(id,"document.querySelectorAll('input[type=password]').forEach(i=>i.value='')");
 const shot=await command('Page.captureScreenshot',{format:'png'},id);fs.writeFileSync(path.join(path.dirname(out),'complete-database-browser.png'),Buffer.from(shot.data,'base64'));
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks,scope:'actual Chrome UI; cancel and acknowledgement; no active switch'},null,2));
})().catch(e=>{console.error(e.message);process.exitCode=1}).finally(async()=>{
 if(ws&&ws.readyState===WebSocket.OPEN){try{await command('Browser.close')}catch{}ws.close()}
 if(chrome){for(let i=0;i<80&&chrome.exitCode===null;i++)await sleep(100);if(chrome.exitCode===null){chrome.kill();process.exitCode=1}}
 for(const p of pending.values()){clearTimeout(p.timer)}pending.clear();
});
