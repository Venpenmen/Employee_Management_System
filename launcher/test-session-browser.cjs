const fs=require('node:fs'),path=require('node:path'),{spawn,execFileSync}=require('node:child_process');
const [base,out,browserExe,profile]=process.argv.slice(2),password=process.env.EMS_TEST_PASSWORD;
const checks=[],pending=new Map();let chrome,ws,seq=0;
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
 chrome=spawn(browserExe,['--headless=new','--remote-debugging-port=0','--user-data-dir='+profile,'--disable-background-networking','--no-first-run','about:blank'],{windowsHide:true,stdio:'ignore'});
 const ps=path.join(process.env.SystemRoot,'System32','WindowsPowerShell','v1.0','powershell.exe');
 const identity=JSON.parse(execFileSync(ps,['-NoProfile','-Command',"$p=Get-Process -Id "+chrome.pid+";@{id=$p.Id;ticks=$p.StartTime.ToUniversalTime().Ticks.ToString();exe=$p.Path;purpose='browser'}|ConvertTo-Json -Compress"],{encoding:'utf8',windowsHide:true}));
 fs.writeFileSync(path.join(path.dirname(out),'process-session-browser.json'),JSON.stringify(identity));
 await until(()=>fs.existsSync(path.join(profile,'DevToolsActivePort')),'debug port');
 const lines=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').trim().split('\n');
 ws=new WebSocket('ws://127.0.0.1:'+lines[0]+lines[1]);
 await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject});
 ws.onmessage=event=>{const m=JSON.parse(event.data);if(m.id){const p=pending.get(m.id);if(p){clearTimeout(p.timer);pending.delete(m.id);m.error?p.reject(Error('CDP command failed')):p.resolve(m.result)}}};
 const ca=(await command('Target.createBrowserContext')).browserContextId,cb=(await command('Target.createBrowserContext')).browserContextId;
 const a=await page(ca,'/login'),b=await page(cb,'/login');await login(a);await login(b);
 await navigate(a,'/profile');
 await until(()=>evaluate(a,"location.pathname==='/login'&&document.body.innerText.includes('账号已在其他设备登录')"),'replacement message');
 check(true,'Two independent Chrome contexts: second login kicks first context');
 const shot=await command('Page.captureScreenshot',{format:'png'},a);fs.writeFileSync(path.join(path.dirname(out),'session-replaced-browser.png'),Buffer.from(shot.data,'base64'));
 check(await evaluate(a,"fetch('/api/me').then(r=>r.status)")===401,'Old Chrome context API401');
 await navigate(b,'/profile');check(await evaluate(b,"location.pathname==='/profile'"),'Winning Chrome context remains authenticated');
 const tab=await page(cb,'/api/me');check(await evaluate(tab,"document.body.innerText.includes('260003')"),'Second tab in same context shares authenticated session');
 await evaluate(b,"document.querySelector('form[action=\"/logout\"]').requestSubmit()");
 await until(()=>evaluate(b,"location.pathname==='/login'&&document.readyState==='complete'"),'logout');
 check(await evaluate(tab,"fetch('/api/me').then(r=>r.status)")===401,'Logout invalidates all tabs sharing that session');

 const executiveContext=(await command('Target.createBrowserContext')).browserContextId;
 const executive=await page(executiveContext,'/login');
 await evaluate(executive,"(()=>{document.querySelector('[name=employeeNo]').value='260001';document.querySelector('[name=password]').value="+JSON.stringify(password)+";document.querySelector('form').requestSubmit()})()");
 await until(()=>evaluate(executive,"location.pathname==='/dashboard'&&document.readyState==='complete'"),'executive login');
 await navigate(executive,'/admin/database');
 check(await evaluate(executive,"document.querySelector('#export')!==null&&document.querySelector('#import')!==null"),'Database management page renders in actual Chrome');
 await evaluate(executive,"(()=>{const f=document.querySelector('#export');f.querySelector('[name=password]').value="+JSON.stringify(password)+";f.querySelector('[name=backupPassword]').value='BrowserSyntheticBackup123!';f.querySelector('[name=confirm]').value='BrowserSyntheticBackup123!';f.requestSubmit()})()");
 await until(()=>evaluate(executive,"!document.querySelector('#download').hidden"),'browser encrypted export');
 check(await evaluate(executive,"document.querySelector('#status').innerText.includes('ZIP')"),'Browser form performs step-up and encrypted export');
 const databaseShot=await command('Page.captureScreenshot',{format:'png'},executive);
 fs.writeFileSync(path.join(path.dirname(out),'database-management-browser.png'),Buffer.from(databaseShot.data,'base64'));
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks},null,2));
})().catch(e=>{console.error(e.message);process.exitCode=1}).finally(async()=>{
 if(ws&&ws.readyState===WebSocket.OPEN){try{await command('Browser.close')}catch{}ws.close()}
 if(chrome){for(let i=0;i<80&&chrome.exitCode===null;i++)await sleep(100);if(chrome.exitCode===null){chrome.kill();process.exitCode=1}}
 for(const p of pending.values()){clearTimeout(p.timer)}pending.clear();
});
