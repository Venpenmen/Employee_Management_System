const fs=require('node:fs'),path=require('node:path'),{spawn,execFileSync}=require('node:child_process');
const [base,out,browserExe,profile]=process.argv.slice(2),password=process.env.EMS_TEST_PASSWORD;
const checks=[],pending=new Map();let chrome,ws,seq=0;const sseReadySessions=new Set();
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function check(ok,msg){if(!ok)throw Error(msg);checks.push(msg);console.log('PASS: '+msg)}
function command(method,params={},sessionId){return new Promise((resolve,reject)=>{const id=++seq;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timed out'));},15000);pending.set(id,{resolve,reject,timer});ws.send(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})}));})}
async function evaluate(id,expression){const r=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true},id);if(r.exceptionDetails)throw Error('Browser evaluation failed');return r.result.value}
async function until(test,name){for(let i=0;i<150;i++){if(await test())return;await sleep(100)}throw Error('Timed out: '+name)}
async function page(contextId,route){const t=await command('Target.createTarget',{url:'about:blank',browserContextId:contextId});const attached=await command('Target.attachToTarget',{targetId:t.targetId,flatten:true});await command('Page.enable',{},attached.sessionId);await command('Network.enable',{},attached.sessionId);await navigate(attached.sessionId,route);return attached.sessionId}
async function navigate(id,route){await command('Page.navigate',{url:base+route},id);await until(()=>evaluate(id,"document.readyState==='complete'&&location.href.startsWith("+JSON.stringify(base)+")&&"+(route==='/profile'?"(location.pathname==='/profile'||location.pathname==='/login')":"location.pathname==="+JSON.stringify(route.split('?')[0]))),'page ready')}
async function login(id){
 await navigate(id,'/login');
 await evaluate(id,"(()=>{document.querySelector('[name=employeeNo]').value='260003';document.querySelector('[name=password]').value="+JSON.stringify(password)+";document.querySelector('form').requestSubmit()})()");
 await until(()=>evaluate(id,"location.pathname==='/dashboard'&&document.readyState==='complete'"),'login');
}
async function signin(id,no,pw){await navigate(id,'/login');await evaluate(id,"(()=>{document.querySelector('[name=employeeNo]').value="+JSON.stringify(no)+";document.querySelector('[name=password]').value="+JSON.stringify(pw)+";document.querySelector('form').requestSubmit()})()");await until(()=>evaluate(id,"document.readyState==='complete'&&(location.pathname==='/dashboard'||location.pathname==='/security/temporary-password')"),'sign in');}
async function fillSubmit(id,selector,fields){await evaluate(id,"(()=>{const f=document.querySelector("+JSON.stringify(selector)+");const fields="+JSON.stringify(fields)+";for(const [k,v] of Object.entries(fields))f.querySelector('[name='+k+']').value=v;f.requestSubmit(f.querySelector('button'))})()");}
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
 ws.onmessage=event=>{const m=JSON.parse(event.data);if(m.method==='Network.responseReceived'&&m.params.response.url.endsWith('/api/security/events')&&m.params.response.status===200)sseReadySessions.add(m.sessionId);if(m.id){const p=pending.get(m.id);if(p){clearTimeout(p.timer);pending.delete(m.id);m.error?p.reject(Error('CDP command failed')):p.resolve(m.result)}}};
 const ca=(await command('Target.createBrowserContext')).browserContextId,cb=(await command('Target.createBrowserContext')).browserContextId;
 const a=await page(ca,'/login'),b=await page(cb,'/login');await signin(a,'260001',password);sseReadySessions.delete(a);await navigate(a,'/security');await until(()=>Promise.resolve(sseReadySessions.has(a)),'SSE ready');await signin(b,'260001',password);
 await until(()=>evaluate(a,"!!document.querySelector('dialog[aria-label=\"登录状态变化\"]')?.open"),'live replacement modal without navigation');
 check(await evaluate(a,"location.pathname==='/security'"),'Old browser receives replacement popup without refresh/navigation');
 check(await evaluate(a,"document.querySelector('dialog[aria-label=\"登录状态变化\"]').innerText.includes('其他设备')"),'Popup contains replacement reason');
 check(await evaluate(a,"[...document.querySelectorAll('form button')].every(b=>b.disabled)"),'Old page business controls disabled');
 const screenshot=await command('Page.captureScreenshot',{format:'png'},a);fs.writeFileSync(path.join(path.dirname(out),'rc2-live-session-popup.png'),Buffer.from(screenshot.data,'base64'));
 await navigate(b,'/security');const secondary='Secondary9'+password,newSecondary=secondary+'Browser9';
 await fillSubmit(b,'form[data-endpoint="/api/security/secondary"]',{oldSecondary:secondary,password:newSecondary,confirmPassword:newSecondary});await until(()=>evaluate(b,"document.querySelector('#security-message').innerText.includes('操作成功')"),'secondary change');check(true,'Secondary-password browser form works');
 const cc=(await command('Target.createBrowserContext')).browserContextId,c=await page(cc,'/login');await signin(c,'260003','NewFormal9'+password);await navigate(c,'/security');
 await fillSubmit(c,'form[data-endpoint="/api/security/requests"]',{reason:'虚构浏览器密码申请'});await until(()=>evaluate(c,"document.querySelector('#security-message').innerText.includes('操作成功')"),'request submit');check(true,'Employee browser submits password request');
 await navigate(b,'/security');await evaluate(b,"document.querySelector('form[data-endpoint$=\"/approve\"] button[value=\"true\"]').click()");await until(()=>evaluate(b,"document.querySelector('#security-secret').open"),'approval secret');
 check(await evaluate(b,"document.querySelector('#secret-value').value.length===43"),'Approval shows one-time reset code');
 await evaluate(b,"document.querySelector('#security-secret [data-close]').click()");check(await evaluate(b,"document.querySelector('#secret-value').value===''"),'One-time code cleared after dialog closes');
 await navigate(b,'/security');await evaluate(b,"document.querySelector('form[data-endpoint$=\"/reissue\"] button').click()");await until(()=>evaluate(b,"document.querySelector('#security-secret').open"),'reissue code');check(await evaluate(b,"document.querySelector('#secret-value').value.length===43"),'Browser supports verified reissue without querying old code');await evaluate(b,"document.querySelector('#security-secret [data-close]').click()");
 const target=await evaluate(b,"(()=>{const row=[...document.querySelectorAll('tr')].find(r=>r.innerText.includes('260004')&&r.querySelector('[data-sensitive]'));return row.querySelector('[data-sensitive=RESET]').dataset.target})()");
 await evaluate(b,"document.querySelector('[data-sensitive=RESET][data-target=\""+target+"\"]').click()");check(await evaluate(b,"document.querySelector('#security-sensitive').open"),'Sensitive operation opens independent secondary-password dialog');
 await fillSubmit(b,'#sensitive-form',{secondary:newSecondary,reason:''});await until(()=>evaluate(b,"document.querySelector('#security-secret').open"),'forced password');const temporary=await evaluate(b,"document.querySelector('#secret-value').value");check(temporary.length>40,'Forced reset displays secure temporary password only once');await evaluate(b,"document.querySelector('#security-secret [data-close]').click()");
 const cd=(await command('Target.createBrowserContext')).browserContextId,d=await page(cd,'/login');await signin(d,'260004',temporary);check(await evaluate(d,"location.pathname==='/security/temporary-password'"),'Browser temporary login enters restricted page');
 check(await evaluate(d,"fetch('/api/dictionaries/departments').then(r=>r.status)")===403,'Restricted browser denied business API');
 const formal='BrowserFormal9'+password;await fillSubmit(d,'form.security-form',{password:formal,confirmPassword:formal});await until(()=>evaluate(d,"location.pathname==='/login'"),'formal password logout');await signin(d,'260004',formal);check(true,'Browser temporary-password completion requires fresh login');
 for(let i=0;i<6;i++){const context=(await command('Target.createBrowserContext')).browserContextId,id=await page(context,'/login');if(i<5)await signin(id,'260099',password);else{await fillSubmit(id,'form',{employeeNo:'260099',password});await until(()=>evaluate(id,"location.pathname==='/login'&&document.body.innerText.includes('冻结')"),'auto freeze');}}
 await navigate(b,'/security');const frozenTarget=await evaluate(b,"(()=>{const row=[...document.querySelectorAll('tr')].find(r=>r.innerText.includes('260099')&&r.querySelector('[data-sensitive=UNFREEZE]'));return row?.querySelector('[data-sensitive=UNFREEZE]').dataset.target})()");check(!!frozenTarget,'Executive browser sees automatically frozen target');
 await evaluate(b,"document.querySelector('[data-sensitive=UNFREEZE][data-target=\""+frozenTarget+"\"]').click()");await fillSubmit(b,'#sensitive-form',{secondary:newSecondary,reason:'虚构浏览器线下核验解除'});await until(()=>evaluate(b,"document.querySelector('#security-message').innerText.includes('操作完成')"),'unfreeze');check(true,'Executive browser unfreezes with secondary password and reason');
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks},null,2));
})().catch(e=>{console.error(e.message);process.exitCode=1}).finally(async()=>{
 if(ws&&ws.readyState===WebSocket.OPEN){try{await command('Browser.close')}catch{}ws.close()}
 if(chrome){for(let i=0;i<80&&chrome.exitCode===null;i++)await sleep(100);if(chrome.exitCode===null){chrome.kill();process.exitCode=1}}
 for(const p of pending.values()){clearTimeout(p.timer)}pending.clear();
});
