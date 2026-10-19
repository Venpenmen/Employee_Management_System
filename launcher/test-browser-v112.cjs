// Browser acceptance through the Chrome DevTools Protocol, no npm dependencies.
const fs=require('node:fs'),path=require('node:path'),{spawn,execFileSync}=require('node:child_process');
const [base,employeeNo,password,output,browserExe,profilePath]=process.argv.slice(2);
if(!browserExe||!profilePath)throw Error("Use the managed test-portable.ps1 entry point");
const checks=[],dialogs=[],pending=new Map();let seq=0,ws,chrome,acceptDialogs=true,pausedPost=null,postCount=0,holdPost=false,pendingReport=null;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const assert=(ok,msg)=>{if(!ok)throw Error(msg);checks.push(msg);console.log('PASS: '+msg);};
const command=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout: '+method));},15000);pending.set(id,{resolve,reject,timer});ws.send(JSON.stringify({id,method,params}));});
const evaluate=async expression=>{const result=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result.value;};
async function until(fn,message){for(let i=0;i<200;i++){if(await fn())return;await sleep(100);}throw Error('Timed out: '+message);}
async function navigate(route){await command('Page.navigate',{url:base+route});await until(()=>evaluate("document.readyState!=='loading' && location.pathname.split(';')[0]==="+JSON.stringify(route.split('?')[0])),'navigation '+route);}
async function click(selector){const rect=await evaluate("(()=>{const e=document.querySelector("+JSON.stringify(selector)+");if(!e)return null;e.scrollIntoView();const r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()");if(!rect)throw Error('Missing '+selector);await command('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...rect});await command('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...rect});}
async function set(selector,value){await evaluate("(()=>{const e=document.querySelector("+JSON.stringify(selector)+");e.value="+JSON.stringify(value)+";e.dispatchEvent(new Event('input',{bubbles:true}));})()");}
const profile=profilePath;
async function main(){
 fs.mkdirSync(profile,{recursive:true});
 chrome=spawn(browserExe,['--headless=new','--remote-debugging-port=0','--user-data-dir='+profile,'--no-first-run','--no-default-browser-check','--disable-background-networking','--window-size=1440,1100','about:blank'],{windowsHide:true,stdio:'ignore'});
 const ps=path.join(process.env.SystemRoot,'System32','WindowsPowerShell','v1.0','powershell.exe');
 const identity=JSON.parse(execFileSync(ps,['-NoProfile','-Command',"$p=Get-Process -Id "+chrome.pid+";@{id=$p.Id;ticks=$p.StartTime.ToUniversalTime().Ticks.ToString();exe=$p.Path;purpose='browser'}|ConvertTo-Json -Compress"],{encoding:'utf8',windowsHide:true}).trim());
 fs.writeFileSync(path.join(path.dirname(output),'process-browser.json'),JSON.stringify(identity),'utf8');
 await until(()=>fs.existsSync(path.join(profile,'DevToolsActivePort')),'browser debug port');
 const port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];
 const tabs=await (await fetch('http://127.0.0.1:'+port+'/json/list')).json();
 ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl);
 await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
 ws.onmessage=event=>{
  const m=JSON.parse(event.data);
  if(m.id){const p=pending.get(m.id);if(p){clearTimeout(p.timer);pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}return;}
  if(m.method==='Runtime.consoleAPICalled' && m.params.args[0]?.value?.startsWith('__EMS_PENDING__'))pendingReport=JSON.parse(m.params.args[0].value.slice(15));
  if(m.method==='Network.requestWillBeSent' && m.params.request.method==='POST' && m.params.request.url===base+'/profile')postCount++;
  if(m.method==='Page.javascriptDialogOpening'){dialogs.push(m.params.type);command('Page.handleJavaScriptDialog',{accept:acceptDialogs}).catch(()=>{});}
  if(m.method==='Fetch.requestPaused'){
   if(holdPost&&m.params.request.method==='POST'){pausedPost=m.params.requestId;postCount++;}
   else command('Fetch.continueRequest',{requestId:m.params.requestId}).catch(()=>{});
  }
 };
 await command('Page.enable');await command('Runtime.enable');await command('Network.enable');
 await navigate('/login');
 await set('[name=employeeNo]',employeeNo);await set('[name=password]',password);await click('button.primary');
 await until(()=>evaluate("location.pathname.split(';')[0]==='/dashboard' && document.readyState==='complete'"),'login');
 assert(await evaluate("document.body.innerText.includes('个人档案') && !document.body.innerText.includes('基础工资')"),'Dashboard personal shortcuts and salary privacy render in Chrome');
 await navigate('/profile/edit');
 assert(await evaluate("document.querySelector('.field-counter').textContent.includes('/ 1000')"),'Biography shows current length and limit');
 await click('[name=address]');await set('[name=address]','浏览器未保存输入');
 const before=dialogs.length;
 await navigate('/profile');
 assert(dialogs.slice(before).includes('beforeunload'),'Real navigation from a dirty profile triggers browser leave warning');
 await navigate('/profile/education/new');
 await set('[name=endMonth]','2024-01');await click('input[type=checkbox][name=currentEntry]');
 assert(await evaluate("document.querySelector('[name=endMonth]').disabled && document.querySelector('[name=endMonth]').value===''"),'Current-study toggle clears and disables end month');
 await click('input[type=checkbox][name=currentEntry]');
 assert(await evaluate("!document.querySelector('[name=endMonth]').disabled && document.querySelector('[name=endMonth]').required"),'Leaving current-study state restores required end month');
 await navigate('/profile/experience/new');await click('input[type=checkbox][name=currentEntry]');
 assert(await evaluate("document.querySelector('[name=endMonth]').disabled"),'Current-employment uses the same end-month behavior');
 await set('[name=description]','描述');assert(await evaluate("document.querySelector('.field-counter').textContent==='2 / 1000 字'"),'Work-description counter updates');
 await navigate('/profile/skills/new');await set('[name=notes]','技能说明');
 assert(await evaluate("document.querySelector('.field-counter').textContent==='4 / 500 字'"),'Skill-notes counter updates');
 // Invalid browser validation does not switch buttons to pending.
 await click('button.primary');
 assert(await evaluate("!document.querySelector('button.primary').disabled && !document.querySelector('form[data-profile-edit]').hasAttribute('aria-busy')"),'Invalid native form validation leaves save controls enabled');
 await navigate('/profile/edit');
 await set('[name=phone]','bad');await set('[name=address]','保留服务端错误输入');
 await evaluate("document.querySelector('form[data-profile-edit]').noValidate=true");
 const previousDialogs=dialogs.length;await click('button.primary');
 await until(()=>evaluate("document.querySelector('[data-unsaved]')?.dataset.unsaved==='true'"),'server-side validation');
 assert(await evaluate("document.querySelector('[name=address]').value==='保留服务端错误输入' && !document.querySelector('button.primary').disabled"),'Server validation retains input and restores save controls');
 assert(dialogs.length===previousDialogs,'Normal form submission suppresses leave warning');
 const invalidDialogs=dialogs.length;await navigate('/profile');
 assert(dialogs.slice(invalidDialogs).includes('beforeunload'),'Server-rejected form remains unsaved before any further edits');
 // Create a genuine optimistic-lock conflict without navigating the old form.
 await navigate('/profile/edit');
 await evaluate("(async()=>{const f=document.querySelector('form[data-profile-edit]'),body=new FormData(f);body.set('biography','浏览器另页已保存');await fetch('/profile',{method:'POST',body});})()");
 await set('[name=biography]','浏览器旧页未保存');await click('button.primary');
 await until(()=>evaluate("document.querySelector('[data-unsaved]')?.dataset.unsaved==='true'"),'version conflict');
 assert(await evaluate("document.body.innerText.includes('其他页面') && document.querySelector('[name=biography]').value==='浏览器旧页未保存' && !!document.querySelector('[data-discard-reload]')"),'Version conflict preserves input and offers explicit reload');
 await click('[data-discard-reload]');
 await until(()=>evaluate("document.querySelector('[data-unsaved]')?.dataset.unsaved==='false'"),'explicit reload');
 assert(await evaluate("document.querySelector('[name=biography]').value==='浏览器另页已保存'"),'Confirmed reload discards old input and fetches saved version');
 await set('[name=biography]','浏览器正常保存');
 postCount=0;
 await evaluate("(()=>{const f=document.querySelector('form[data-profile-edit]');f.addEventListener('submit',function probe(){f.removeEventListener('submit',probe);const b=f.querySelector('button.primary'),disabled=b.disabled,label=b.textContent;const accepted=f.dispatchEvent(new SubmitEvent('submit',{bubbles:true,cancelable:true}));console.log('__EMS_PENDING__'+JSON.stringify({disabled,label,accepted}));});})()");
 await click('button.primary');await until(()=>!!pendingReport,'pending save observation');
 assert(pendingReport.disabled && pendingReport.label.includes('正在保存'),'Pending submission disables save button and shows progress');
 assert(!pendingReport.accepted,'Repeated submit is canceled while a save is pending');
 await until(()=>evaluate("location.pathname==='/profile' && !!document.querySelector('#skills')"),'save redirect');
 assert(postCount===1,'Repeated submit sends only one real POST');
 // Use actual browser history after a successful submit.
 await evaluate("history.back()");
 await until(()=>evaluate("location.pathname==='/profile/edit' && document.readyState!=='loading'"),'history back');
 assert(await evaluate("!document.querySelector('button.primary').disabled"),'History pageshow restores save controls');
 for(const [kind,field] of [['education','school'],['experience','company'],['skills','name'],['certificates','name']]){
  await navigate('/profile/'+kind+'/new');await click('[name='+field+']');await set('[name='+field+']','明细未保存');
  const n=dialogs.length;await navigate('/profile');
  assert(dialogs.slice(n).includes('beforeunload'),'Unsaved '+kind+' form triggers actual browser warning');
 }
 await navigate('/profile');
 const deleteSelector='form[action*="/skills/"][action$="/delete"] button';
 assert(await evaluate("!!document.querySelector("+JSON.stringify(deleteSelector)+")"),'Fixture has an owned skill for delete confirmation');
 acceptDialogs=false;await click(deleteSelector);await sleep(150);
 assert(await evaluate("location.pathname==='/profile' && !document.querySelector("+JSON.stringify(deleteSelector)+").disabled && !document.querySelector("+JSON.stringify(deleteSelector)+").closest('form').hasAttribute('aria-busy')"),'Canceling delete leaves buttons enabled and sends no deletion');
 acceptDialogs=true;
 const shot=await command('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(path.dirname(output),'v112-profile-browser.png'),Buffer.from(shot.data,'base64'));
 fs.writeFileSync(output,checks.join('\r\n'),'utf8');
}
main().then(async()=>{console.log('All browser checks passed.');}).catch(async e=>{console.error(e.stack);try{console.error(await evaluate('JSON.stringify({url:location.href,ready:document.readyState,text:document.body.innerText.slice(0,1600)})'));const shot=await command('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(path.dirname(output),'browser-failure.png'),Buffer.from(shot.data,'base64'));}catch{}process.exitCode=1;}).finally(async()=>{
 if(ws&&ws.readyState===WebSocket.OPEN){try{await command('Browser.close');}catch{}ws.close();}
 if(chrome){for(let i=0;i<100&&chrome.exitCode===null;i++)await sleep(100);if(chrome.exitCode===null){chrome.kill();for(let i=0;i<50&&chrome.exitCode===null;i++)await sleep(100);}if(chrome.exitCode===null&&process.exitCode!==1)process.exitCode=2;}
 for(const p of pending.values()){clearTimeout(p.timer);p.reject(Error('Browser closed'));}pending.clear();
});
