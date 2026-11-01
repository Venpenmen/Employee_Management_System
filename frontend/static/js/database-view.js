/* Only structural counts and fixed status labels may reach the restore view. */
((root)=>{
 const count=v=>Number.isSafeInteger(v)&&v>=0?v:0;
 const text=v=>typeof v==='string'&&/^[A-Za-z0-9_. -]{1,100}$/.test(v)?v:'未知';
 function model(p={}){
  const tables=Object.entries(p.tables||{}).filter(([k,v])=>/^[a-z_]{1,64}$/.test(k)&&Number.isSafeInteger(v)&&v>=0).sort(([a],[b])=>a.localeCompare(b));
  const ext=p.extensions||{},conflicts=Object.entries(ext.conflicts||{}).filter(([k,v])=>/^[A-Z_]{1,80}$/.test(k)&&Number.isSafeInteger(v)&&v>=0);
  const rows=tables.reduce((sum,[,n])=>sum+n,0),details=['识别版本：'+text(p.programVersion),'结构描述：'+text(p.identifiedProfile),'恢复模式：'+text(p.recoveryMode),'表数量：'+tables.length,'总记录数：'+rows,'预览恢复扩展数：'+count(ext.restoredInPreview),'可恢复扩展数：'+count(ext.restorable),'需重新设置二级凭证：'+count(ext.secondaryCredentialsToReset),'冲突数量：'+count(p.skippableConflicts),...conflicts.map(([k,n])=>'冲突 '+k+'：'+n),...tables.map(([k,n])=>k+'：'+n)];
  return {summary:'备份版本 '+text(p.programVersion)+'；'+tables.length+' 张表，'+rows+' 条记录；'+(p.blocking?'存在阻断冲突，不能切换':p.baseOnlyRequired?'仅基础升级，需明确确认':'隔离校验通过，尚未切换')+'；冲突扩展 '+count(p.skippableConflicts)+' 项。',details:details.join('\n')};
 }
 const api=Object.freeze({model});if(typeof module==='object'&&module.exports)module.exports=api;else root.EMSDatabaseView=api;
})(typeof window==='object'?window:globalThis);
