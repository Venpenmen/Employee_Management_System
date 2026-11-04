(()=>{
const status=document.querySelector('#status'),csrf=document.querySelector('#csrf').value;
const download=document.querySelector('#download');
let pending='',preview=null,timer,downloading=false,busy=false,pollGeneration=0;
const modeButtons=[document.querySelector('#basic-mode'),document.querySelector('#advanced-mode')];
function clearPreview(){pollGeneration++;pending='';preview=null;const f=document.querySelector('#commit');f.hidden=true;f.reset();document.querySelector('#preview-details').open=false;document.querySelector('#preview').textContent='';document.querySelector('#preview-summary').textContent='';}
function setBusy(value){busy=value;for(const b of modeButtons)b.disabled=value;}
for(const [i,b] of modeButtons.entries())b.addEventListener('click',()=>{
 if(busy)return;clearInterval(timer);clearPreview();resetDownload();for(const id of ['export','import'])document.querySelector('#'+id).reset();
 for(const fieldset of document.querySelectorAll('[data-advanced]')){fieldset.hidden=i===0;fieldset.disabled=i===0;}
 document.querySelector('[name=targetVersion]').value=i===0?'current':'1.2.0';
 modeButtons.forEach((button,index)=>button.setAttribute('aria-pressed',String(i===index)));
 document.querySelector('#mode-description').textContent=i===0?'日常备份和整库恢复。确认前不切换当前数据库。':'高级方式用于受控旧版往返。缺少扩展或证明不能宣称无损恢复。';status.textContent='';
});
document.querySelector('#cancel-preview').addEventListener('click',()=>{if(!busy){clearPreview();status.textContent='已取消本次确认，当前数据库未切换。';}});
for(const name of ['file','extension','proof','backupPassword'])document.querySelector('#import [name='+name+']').addEventListener('change',()=>{if(pending){clearPreview();status.textContent='导入条件已改变，请重新隔离校验。';}});

async function post(path,data){
 const r=await fetch('/api/admin/database/'+path,{method:'POST',credentials:'same-origin',headers:{'X-CSRF-Token':csrf,Accept:'application/json'},body:data});
 const j=await r.json();if(!r.ok||j.code!==200)throw Error(j.msg||'请求失败');return j.data;
}
async function grant(form,action,object=''){
 const data=new FormData();data.set('password',form.get('password'));data.set('action',action);data.set('object',object);
 return (await post('reauth',data)).grant;
}
function resetDownload(){download.hidden=true;download.removeAttribute('href');download.dataset.job='';}
async function saveBackup(id){
 if(downloading)return;
 downloading=true;
 try{
  status.textContent='正在准备加密备份 ZIP 下载…';
  const r=await fetch('/api/admin/database/jobs/'+encodeURIComponent(id)+'/download',{credentials:'same-origin',redirect:'manual',headers:{Accept:'application/zip'}});
  if(r.status===401||r.type==='opaqueredirect'||r.redirected)throw Error('登录会话已失效，请重新登录后再下载备份');
  if(r.status===403)throw Error('无权下载：请确认使用本机或可信 HTTPS，以高层账号登录');
  if(!r.ok)throw Error('下载失败：HTTP '+r.status);
  const type=(r.headers.get('content-type')||'').toLowerCase();
  if(type.includes('text/html')||type.includes('application/json'))throw Error('服务器返回的不是备份文件，请重新登录后重试');
  const blob=await r.blob();
  if(!blob.size)throw Error('服务器返回了空备份文件');
  const url=URL.createObjectURL(blob),a=document.createElement('a');
  a.href=url;a.download='EMS-Backup-'+id+'.zip';document.body.appendChild(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(url),60000);
  status.textContent='ZIP 归档已交给浏览器保存（'+blob.size+' 字节），包含加密 .emsbk、清单、校验值和使用说明；请检查浏览器下载记录。';
 }catch(e){status.textContent=e.message}
 finally{downloading=false}
}
download.addEventListener('click',e=>{e.preventDefault();if(download.dataset.job)saveBackup(download.dataset.job)});
function watch(id){
 clearInterval(timer);resetDownload();setBusy(true);const generation=++pollGeneration;
 timer=setInterval(async()=>{
  try{
   const r=await fetch('/api/admin/database/jobs/'+encodeURIComponent(id),{credentials:'same-origin',headers:{Accept:'application/json'}});
   if(generation!==pollGeneration)return;
   if(r.status===401){status.textContent='会话已失效，请重新登录后查看任务结果';clearInterval(timer);setBusy(false);return}
   if(!r.ok){status.textContent='维护中，稍后重新登录查看结果';return}
   const s=(await r.json()).data;if(generation!==pollGeneration)return;
   const d=s.diagnostic;
   status.textContent=s.message+(d?'；阶段：'+d.phase+'；原因代码：'+d.code+(d.sqlState?'；SQLState：'+d.sqlState+' / '+d.vendorCode:'')+'；'+d.recommendation:'');
   if(s.state==='PREVIEW_READY'){pending=id;preview=s.preview;document.querySelector('#commit').hidden=false;const view=EMSDatabaseView.model(preview);document.querySelector('#preview-summary').textContent=view.summary;document.querySelector('#preview').textContent=view.details;document.querySelector('#preview-details').open=false;document.querySelector('#base-choice').hidden=!preview.baseOnlyRequired;document.querySelector('#skip-choice').hidden=!(preview.skippableConflicts>0);for(const [name,enabled] of [['acknowledgeBaseOnly',preview.baseOnlyRequired],['skipConflicts',preview.skippableConflicts>0]]){const input=document.querySelector('#commit [name='+name+']');input.checked=false;input.disabled=!enabled;}clearInterval(timer);setBusy(false)}
   else if(s.state==='SUCCEEDED'){clearInterval(timer);setBusy(false);if(s.action==='export'){download.dataset.job=id;download.hidden=false;download.href='/api/admin/database/jobs/'+encodeURIComponent(id)+'/download';status.textContent='导出成功！正在准备 ZIP 下载（含加密备份、校验文件和说明）。若浏览器未自动保存，请点击“下载备份 ZIP”。';saveBackup(id)}}
   else if(['FAILED','ROLLED_BACK','RECOVERY_REQUIRED','CONFLICTS_BLOCKING'].includes(s.state)){clearInterval(timer);setBusy(false);if(s.preview){const v=EMSDatabaseView.model(s.preview);status.textContent+='；'+v.summary;}}
  }catch{status.textContent='维护切换中，请稍后重新登录'}
 },1000);
}
for(const name of ['export','import','commit'])document.querySelector('#'+name).addEventListener('submit',async event=>{
 event.preventDefault();const form=event.currentTarget,button=form.querySelector('button');if(button.disabled||busy)return;
 const data=new FormData(form);if(name==='export'&&!data.has('targetVersion'))data.set('targetVersion','current');
 if(name==='export'&&data.get('backupPassword')!==data.get('confirm')){status.textContent='两次备份口令不一致';return}
 if(name!=='commit'){document.querySelector('#commit').hidden=true;pending='';preview=null;}
 if(name==='commit'&&(!pending||!preview||preview.blocking||(preview.expiresAt&&Date.now()>=preview.expiresAt))){clearPreview();status.textContent='预览无效或已过期，请重新上传并校验';return}
 if(name==='commit'&&preview?.baseOnlyRequired&&!data.has('acknowledgeBaseOnly')){status.textContent='请明确确认仅基础升级，或取消操作';return}
 if(name==='commit'&&preview?.skippableConflicts>0&&!data.has('skipConflicts')){status.textContent='请明确确认跳过所列冲突扩展，或取消操作';return}
 if(name==='commit'&&!confirm('将整体替换数据库并注销所有会话，确认继续？'))return;
 button.disabled=true;setBusy(true);
 try{
  const object=name==='commit'?(preview?.receiptDigest?pending+':'+preview.receiptDigest+':'+(data.has('acknowledgeBaseOnly')?'BASE':'FULL')+':'+(data.has('skipConflicts')?'SKIP':'ALL'):pending):'';
  data.set('grant',await grant(data,name==='export'?'export':name==='import'?'inspect':'commit',object));
  data.delete('password');data.delete('confirm');
  const result=await post(name==='commit'?'import/'+pending+'/confirm':name,data);
  form.reset();status.textContent='任务已提交';watch(result.task);
 }catch(e){status.textContent=e.message;setBusy(false)}finally{button.disabled=false}
});
})();
