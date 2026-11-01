'use strict';
const fs=require('node:fs');
const crypto=require('node:crypto');
const [base,out,authFile]=process.argv.slice(2),cookie=process.env.EMS_LEGACY_COOKIE;
if(!process.env.EMS_DEVELOPMENT_TASK_ID||new URL(base).origin!=='http://127.0.0.1:8485'||!/^EMS120_P1_[a-f0-9]+$/.test(cookie))throw Error('Owned loopback legacy fixture required');
const checks=[];function check(value,message){if(!value)throw Error(message);checks.push(message);console.log('PASS '+message);}
function credentials(file){
 const bytes=fs.readFileSync(file);if(bytes.length>16384||bytes.subarray(0,8).toString('ascii')!=='EMSBK001'||bytes.readUInt32BE(8)!==600000)throw Error('Invalid synthetic fixture auth envelope');
 const key=crypto.pbkdf2Sync('SyntheticControlledRoundtrip91',bytes.subarray(12,28),600000,32,'sha256');
 try{const decipher=crypto.createDecipheriv('aes-256-gcm',key,bytes.subarray(28,40));decipher.setAAD(bytes.subarray(0,8));decipher.setAuthTag(bytes.subarray(bytes.length-16));return JSON.parse(Buffer.concat([decipher.update(bytes.subarray(40,bytes.length-16)),decipher.final()]).toString('utf8'));}finally{key.fill(0);}
}
const auth=credentials(authFile);
class Client{
 constructor(){this.jar={};this.token='';}
 async request(route,method='GET',fields){
  const headers={Cookie:Object.entries(this.jar).map(([k,v])=>k+'='+v).join('; ')};
  let body;if(fields){body=new URLSearchParams({...fields,_csrf:this.token});headers['Content-Type']='application/x-www-form-urlencoded';}
  const response=await fetch(base+route,{method,headers,body,redirect:'manual',signal:AbortSignal.timeout(15000)});
  for(const value of response.headers.getSetCookie()){const pair=value.split(';')[0],at=pair.indexOf('=');this.jar[pair.slice(0,at)]=pair.slice(at+1);}
  const text=await response.text();for(const tag of text.matchAll(/<input\b[^>]*>/g)){if(/\bname="_csrf"/.test(tag[0])){const value=tag[0].match(/\bvalue="([^"]*)"/);if(value)this.token=value[1];}}
  return {status:response.status,text,location:response.headers.get('location')};
 }
 async login(person,role){await this.request('/login');const before=this.jar[cookie];const response=await this.request('/login','POST',{employeeNo:person.no,password:person.password});check(response.status===302,'Actual old program login role '+role);check(!!this.jar[cookie]&&this.jar[cookie]!==before,'Dedicated cookie and session rotation role '+role);const me=JSON.parse((await this.request('/api/me')).text);check(me.data.userType===role,'Actual old program role '+role);return this;}
}
(async()=>{
 const admin=await new Client().login(auth.executive,2),employee=await new Client().login(auth.employee,0),manager=await new Client().login(auth.manager,1);
 for(const route of ['/dashboard','/employees','/profile','/my-salaries','/attendance/my','/leaves/my'])check((await employee.request(route)).status===200,'Old runtime page '+route);
 const edit=await employee.request('/profile/edit');let version;
 for(const tag of edit.text.matchAll(/<input\b[^>]*>/g))if(/\bname="version"/.test(tag[0]))version=tag[0].match(/\bvalue="([^"]*)"/)?.[1];check(version!==undefined,'Profile concurrency version is read from actual old form');
 check((await employee.request('/profile','POST',{phone:auth.phone,email:'roundtrip@example.invalid',address:'Synthetic old address',emergencyName:'',emergencyRelation:'',emergencyPhone:'',biography:'Synthetic old edit',gender:'',version})).status===302,'Old runtime profile edit');
 check((await employee.request('/profile/skills','POST',{name:'SyntheticOldSkill',proficiency:'熟练',notes:'Synthetic old addition'})).status===302,'Old runtime profile detail addition');
 check((await employee.request('/profile')).text.includes('SyntheticOldSkill'),'Old runtime profile change visible');
 check((await employee.request('/attendance/check-in','POST',{})).status===302,'Old runtime attendance write');
 const previous=new Set([...((await manager.request('/leaves/approvals')).text).matchAll(/\/leaves\/(L[0-9A-F]+)\/approve/g)].map(m=>m[1]));
 check((await employee.request('/leaves','POST',{leaveType:'PERSONAL',startTime:'2034-01-10T09:00',endTime:'2034-01-10T10:00',reason:'Synthetic roundtrip leave'})).status===302,'Old runtime leave submission');
 const approvals=(await manager.request('/leaves/approvals')).text,number=[...approvals.matchAll(/\/leaves\/(L[0-9A-F]+)\/approve/g)].map(m=>m[1]).find(no=>!previous.has(no));check(!!number,'Old runtime manager approval scope');
 check((await manager.request('/leaves/'+number+'/approve','POST',{opinion:'Synthetic approval'})).status===302,'Old runtime manager approval');
 check((await admin.request('/admin/salaries/settle','POST',{month:'2034-01'})).status===302,'Old runtime salary calculation');
 check((await admin.request('/admin/salaries/publish','POST',{month:'2034-01'})).status===302,'Old runtime salary publish');
 check((await employee.request('/my-salaries?month=2034-01')).text.includes('2034-01'),'Old runtime salary visible');
 check((await employee.request('/admin/departments')).status===403,'Old runtime executive permission enforced');
 const departmentName='S2SyntheticDept'+Date.now(),options=async()=>JSON.parse((await admin.request('/api/dictionaries/employee-options')).text).data;
 check((await admin.request('/admin/departments','POST',{name:departmentName,baseSalary:'4000',description:'Synthetic controlled-copy department'})).status===302,'Old runtime department creation');
 let department=(await options()).departments.find(d=>d.department_name===departmentName);check(!!department,'Old runtime new department selectable');
 check((await admin.request('/admin/departments/'+department.id+'/update','POST',{departmentName:departmentName+'Updated',baseSalary:'4100',description:'Synthetic update'})).status===302,'Old runtime department update');
 check((await options()).departments.some(d=>d.id===department.id&&d.department_name===departmentName+'Updated'),'Old runtime department update visible');
 check((await admin.request('/admin/departments/'+department.id+'/delete','POST',{})).status===302,'Old runtime empty department removal');
 check(!(await options()).departments.some(d=>d.id===department.id),'Old runtime removed department not selectable');
 fs.writeFileSync(out,JSON.stringify({passed:true,checks:checks.length,actualOldBinaryHttp:true,scope:'owned loopback fixture'},null,2));
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
