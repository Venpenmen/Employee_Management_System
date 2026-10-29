(()=>{
const status=document.querySelector('#status'),csrf=document.querySelector('#csrf').value;
const download=document.querySelector('#download');
let pending='',timer,downloading=false;
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
 clearInterval(timer);resetDownload();
 timer=setInterval(async()=>{
  try{
   const r=await fetch('/api/admin/database/jobs/'+encodeURIComponent(id),{credentials:'same-origin',headers:{Accept:'application/json'}});
   if(r.status===401){status.textContent='会话已失效，请重新登录后查看任务结果';clearInterval(timer);return}
   if(!r.ok){status.textContent='维护中，稍后重新登录查看结果';return}
   const s=(await r.json()).data;
   status.textContent=s.message+(s.preview?'；备份版本：'+s.preview.programVersion+'；各表记录数：'+JSON.stringify(s.preview.tables):'');
   if(s.state==='PREVIEW_READY'){pending=id;document.querySelector('#commit').hidden=false;clearInterval(timer)}
   else if(s.state==='SUCCEEDED'){clearInterval(timer);if(s.action==='export'){download.dataset.job=id;download.hidden=false;download.href='/api/admin/database/jobs/'+encodeURIComponent(id)+'/download';status.textContent='导出成功！正在准备 ZIP 下载（含加密备份、校验文件和说明）。若浏览器未自动保存，请点击“下载备份 ZIP”。';saveBackup(id)}}
   else if(['FAILED','ROLLED_BACK','RECOVERY_REQUIRED'].includes(s.state))clearInterval(timer);
  }catch{status.textContent='维护切换中，请稍后重新登录'}
 },1000);
}
for(const name of ['export','import','commit'])document.querySelector('#'+name).addEventListener('submit',async event=>{
 event.preventDefault();const form=event.currentTarget,button=form.querySelector('button');if(button.disabled)return;
 const data=new FormData(form);
 if(name==='export'&&data.get('backupPassword')!==data.get('confirm')){status.textContent='两次备份口令不一致';return}
 if(name==='commit'&&!confirm('将整体替换数据库并注销所有会话，确认继续？'))return;
 button.disabled=true;
 try{
  data.set('grant',await grant(data,name==='export'?'export':name==='import'?'inspect':'commit',name==='commit'?pending:''));
  data.delete('password');data.delete('confirm');
  const result=await post(name==='commit'?'import/'+pending+'/confirm':name,data);
  form.reset();status.textContent='任务已提交';watch(result.task);
 }catch(e){status.textContent=e.message}finally{button.disabled=false}
});
})();