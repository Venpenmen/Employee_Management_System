
const fs=require('node:fs'),path=require('node:path');
const [base,out]=process.argv.slice(2),password=process.env.EMS_TEST_PASSWORD;
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
(async()=>{
 let anonymous=new Client;
 check((await anonymous.req('/api/me')).status===401,'Anonymous API returns401');
 check((await anonymous.req('/dashboard')).location==='/login','Anonymous page redirects');
 check((await anonymous.req('/login','POST',{employeeNo:'260001',password},false)).status===403,'Login CSRF enforced');
 const admin=await new Client().login('260001'),manager=await new Client().login('260002'),a=await new Client().login('260003'),b=await new Client().login('260004');
 for(const [c,no] of [[admin,'260001'],[manager,'260002'],[a,'260003'],[b,'260004']]){check(JSON.parse((await c.req('/api/me')).text).data.employeeNo===no,'Independent session '+no);for(const route of ['/dashboard','/employees','/profile','/my-salaries','/attendance/my','/leaves/my'])check((await c.req(route)).status===200,no+' page '+route)}
 check((await a.req('/admin/departments')).status===403,'Employee cannot administer');
 check((await a.req('/admin;qa/departments')).status===403,'Semicolon path cannot bypass executive read permission');
 check((await a.req('/admin;qa/departments','POST',{name:'must-not-create',baseSalary:'0'})).status===403,'Semicolon path cannot bypass executive write permission');
 check((await anonymous.req('/api;qa/me')).status===401,'Semicolon API path still returns unauthenticated JSON401');
 check((await manager.req('/admin/salaries')).status===403,'Manager cannot administer salary');
 check(!(await a.req('/employees?departmentId=2&page=999&pageSize=50')).text.includes('测试员工乙'),'Department filter cannot widen employee scope');
 for(const c of [a,manager])check(!/base_salary|position_salary/.test((await c.req('/api/dictionaries/employee-options')).text),'Option API excludes wage fields');
 const health=await anonymous.req('/health');check(health.text==='{"status":"UP"}','Health response is minimal');
 check((await anonymous.req('/internal/portable/status')).status!==200,'Server exposes no portable status');
 check((await anonymous.req('/internal/portable/stop','POST',{},false)).status!==202,'Server exposes no portable stop');
 for(const route of ['/attendance/check-in','/logout','/profile','/leaves','/admin/salaries/settle'])check((await a.req(route,'POST',{},false)).status===403,'CSRF denies '+route);
 let old=a.token;a.token=admin.token;check((await a.req('/attendance/check-in','POST',{})).status===403,'Other session CSRF rejected');a.token=old;
 const date='2030-06-03';
 let results=await Promise.all([a.req('/leaves','POST',{leaveType:'ANNUAL',startTime:date+'T09:00',endTime:date+'T18:00',reason:'并发测试'}),a.req('/leaves','POST',{leaveType:'ANNUAL',startTime:date+'T09:00',endTime:date+'T18:00',reason:'并发测试'})]);
 console.log('Concurrent leave status: '+results.map(x=>x.status).join(',')); check(results.every(x=>x.status===302),'Concurrent leave requests handled');
 let approvals=await manager.req('/leaves/approvals'),numbers=[...approvals.text.matchAll(/\/leaves\/(L[0-9A-F]+)\/approve/g)].map(x=>x[1]);
 check(new Set(numbers).size===1,'Exactly one overlapping leave created');
 let no=numbers[0];
 check((await b.req('/leaves/'+no+'/withdraw','POST',{})).status===302,'Foreign withdrawal handled without granting access');
 let decisions=await Promise.all([manager.req('/leaves/'+no+'/approve','POST',{opinion:'同意'}),manager.req('/leaves/'+no+'/approve','POST',{opinion:'同意'})]);
 check(decisions.some(x=>x.status===409),'Repeated concurrent approval conflicts');
 check((await a.req('/leaves/my')).text.includes('已通过'),'Applicant sees manager result');
 await Promise.all([a.req('/attendance/check-in','POST',{}),a.req('/attendance/check-in','POST',{})]);
 check((await a.req('/attendance/my')).text.includes('已上班打卡'),'Attendance shared after concurrent requests');
 await admin.req('/admin/salaries/settle','POST',{month:'2030-06'});
 await admin.req('/admin/salaries/publish','POST',{month:'2030-06'});
 check((await a.req('/my-salaries?month=2030-06')).text.includes('2030-06'),'Published salary visible to employee');
 const xlsx=await fetch(base+'/admin/salaries/export?month=2030-06',{headers:{Cookie:Object.entries(admin.jar).map(([k,v])=>k+'='+v).join('; ')}});
 check(xlsx.status===200,'Excel export succeeds');fs.writeFileSync(path.join(path.dirname(out),'salary.xlsx'),Buffer.from(await xlsx.arrayBuffer()));
 await a.req('/profile/edit');
 const basic={phone:'13800000003',email:'test@example.invalid',address:'测试地址',emergencyName:'',emergencyRelation:'',emergencyPhone:'',biography:'测试档案',gender:'',version:'0',userType:'2',employeeId:'1'};
 check((await a.req('/profile','POST',basic)).status===302,'Profile save succeeds');
 let conflict=await a.req('/profile','POST',basic);check(conflict.status===409&&conflict.text.includes('测试地址'),'Stale profile version409 preserves input');
 check(JSON.parse((await a.req('/api/me')).text).data.userType===0,'Extra role/employee parameters ignored');
 for(const [kind,fields] of [
 ['education',{school:'测试学校',degree:'本科',major:'计算机',startMonth:'2020-09',endMonth:'2024-06'}],
 ['experience',{company:'测试单位',department:'研发',position:'开发',startMonth:'2024-07',currentEntry:'true',description:'测试'}],
 ['skills',{name:'测试技能',proficiency:'熟练',notes:'测试说明'}],
 ['certificates',{name:'测试证书',issuer:'测试机构',certificateNumber:'TEST001',obtainedDate:'2024-01-01',expiryDate:'2030-01-01'}]
 ]){
  let result=await a.req('/profile/'+kind,'POST',fields);check(result.status===302,'Profile detail create '+kind);
  const firstPage=await a.req('/profile'),pattern=new RegExp('/profile/'+kind+'/(\\d+)/edit','g');
  let ids=[...firstPage.text.matchAll(pattern)].map(x=>x[1]);check(ids.length>=1,'Profile detail read '+kind);
  check((await b.req('/profile/'+kind+'/'+ids[0]+'/edit')).status===404,'Foreign detail edit denied '+kind);
  check((await b.req('/profile/'+kind+'/'+ids[0]+'/delete','POST',{})).status===404,'Foreign detail deletion denied '+kind);
  check((await a.req('/profile/'+kind+'/'+ids[0],'POST',fields)).status===302,'Profile detail update '+kind);
  await a.req('/profile/'+kind,'POST',fields);
  let page=await a.req('/profile'),all=[...page.text.matchAll(pattern)].map(x=>Number(x[1])),extra=Math.max(...all);
  check(all.length===2,'Second detail created '+kind);
  check((await a.req('/profile/'+kind+'/'+extra+'/delete','POST',{})).status===302,'Profile detail delete '+kind);
 }
 check((await a.req('/employees?page=oops')).status===400,'Readable malformed-page400');
 check((await a.req('/unknown-path')).status===404,'Unknown page404');
 check((await admin.req('/admin/logs')).status===200,'Executive operation logs');
 await admin.req('/admin/departments','POST',{name:'QA额外部门',baseSalary:'3500.25',description:'测试'});
 check((await admin.req('/admin/departments')).text.includes('QA额外部门'),'Department maintenance with globalCSRF');
 await admin.req('/admin/positions','POST',{departmentId:'1',name:'QA新增职位',salary:'900.50',managerPosition:'false'});
 check((await admin.req('/admin/positions/manage')).text.includes('QA新增职位'),'Position maintenance with globalCSRF');
 await admin.req('/admin/recruitment/single','POST',{name:'QARecruit',identityNumber:'110101199201019995',birthDate:'1992-01-01',hireDate:'2024-01-01',departmentId:'1'});
 const recruited=await admin.req('/employees?keyword=QARecruit');
 let match=recruited.text.match(/\/employees\/(\d{6})/);check(!!match,'Recruitment creates pending employee');
 const noNew=match[1],registration=new Client;await registration.req('/register');
 let bad=await registration.req('/register','POST',{employeeNo:noNew,name:'QARecruit',identitySuffix:'995',phone:'13800000005',password,confirmPassword:password});
 check(bad.status===200&&bad.text.includes('六位'),'Registration rejects short identity suffix');
 let good=await registration.req('/register','POST',{employeeNo:noNew,name:'QARecruit',identitySuffix:'019995',phone:'13800000005',password,confirmPassword:password});
 check(good.status===200&&good.text.includes('注册成功'),'Registration succeeds for recruited employee');
 await registration.login(noNew);
 const attack=new Client;await attack.req('/login');
 const failed=await Promise.all(Array.from({length:5},()=>attack.req('/login','POST',{employeeNo:noNew,password:'WrongPassword123'})));
 check(failed.every(x=>x.status===200),'Concurrent failed logins return readable errors');
 const locked=await attack.req('/login','POST',{employeeNo:noNew,password});
 check(locked.status===200&&locked.text.includes('锁定'),'Five concurrent failures preserve15minute lockout');
 check((await attack.req('/api/me')).status===401,'Locked login cannot create an authenticated session');
 let logout=await b.req('/logout','POST',{});check(logout.status===302&&(await b.req('/api/me')).status===401,'Logout invalidates session');
 let disabled=await new Client().login('260004');
 await admin.req('/employees/260004/terminate','POST',{reason:'测试停用已有会话'});
 check((await disabled.req('/api/me')).status===401,'Disabled employee existing session is rejected');
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks},null,2));
})().catch(e=>{console.error(e.stack);process.exitCode=1});
