(() => {
'use strict';
const states=new Map();
const snapshot=f=>JSON.stringify([...f.querySelectorAll('input:not([type=hidden]),textarea,select')].map(e=>[e.name,e.type==='checkbox'?e.checked:e.value]));
for(const form of document.querySelectorAll('form[data-profile-edit],form[action^="/profile/"]')){
 const state={pending:false,submitted:false,invalid:form.dataset.unsaved==='true',editing:form.hasAttribute('data-profile-edit')};states.set(form,state);
 const current=form.querySelector('input[type=checkbox][name=currentEntry]'),end=form.querySelector('[name=endMonth]');
 const sync=()=>{if(!end||!current)return;if(current.checked)end.value='';end.disabled=current.checked;end.required=!current.checked;};
 sync();current?.addEventListener('change',sync);
 for(const input of form.querySelectorAll('textarea[maxlength]')){
  const counter=document.createElement('small');counter.className='field-counter';counter.setAttribute('aria-live','polite');input.after(counter);
  const update=()=>{counter.textContent=input.value.length+' / '+input.maxLength+' 字';};input.addEventListener('input',update);update();
 }
 state.initial=snapshot(form);
 form.addEventListener('submit',event=>{
  if(event.defaultPrevented)return;
  if(state.pending){event.preventDefault();return;}
  state.pending=true;state.submitted=true;form.setAttribute('aria-busy','true');
  for(const b of form.querySelectorAll('button:not([type=button]),input[type=submit]')){
   b.dataset.originalLabel=b.tagName==='INPUT'?b.value:b.textContent;b.disabled=true;
   if(b.tagName==='INPUT')b.value='正在保存…';else b.textContent='正在保存…';
  }
 });
}
window.addEventListener('beforeunload',event=>{
 if([...states].some(([f,s])=>s.editing&&!s.submitted&&(s.invalid||snapshot(f)!==s.initial))){event.preventDefault();event.returnValue='';}
});
document.querySelectorAll('[data-discard-reload]').forEach(a=>a.addEventListener('click',event=>{
 if(!confirm('重新载入会丢弃当前未保存的输入，确定继续吗？')){event.preventDefault();return;}for(const s of states.values())s.submitted=true;
}));
window.addEventListener('pageshow',()=>{for(const [f,s] of states){s.pending=false;s.submitted=false;f.removeAttribute('aria-busy');for(const b of f.querySelectorAll('[data-original-label]')){b.disabled=false;if(b.tagName==='INPUT')b.value=b.dataset.originalLabel;else b.textContent=b.dataset.originalLabel;}}});
})();