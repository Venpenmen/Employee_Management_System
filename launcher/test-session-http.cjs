
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
 const no='260099';
 let a=await new Client().login(no),b=await new Client().login(no);
 let kicked=await a.req('/profile');
 check(kicked.status===302&&kicked.headers.get('location').includes('reason=other-login'),'Replaced browser page gets explicit login reason');
 check((await a.req('/login?reason=other-login')).text.includes('账号已在其他设备登录'),'Replacement message rendered');
 check((await a.req('/api/me')).status===401,'Replaced API is401');
 await a.req('/logout','POST',{});check((await b.req('/api/me')).status===200,'Old logout cannot revoke new login');
 let c=await new Client().login(no),d=await new Client().login(no);
 await d.req('/profile/edit');
 let stale=await c.req('/profile','POST',{phone:'13800000099',email:'probe@example.invalid',address:'MUST_NOT_BE_SAVED',emergencyName:'',emergencyRelation:'',emergencyPhone:'',biography:'',gender:'',version:'0'});
 check(stale.status===302&&stale.headers.get('location').includes('other-login'),'Old POST rejected before business handling');
 check(!(await d.req('/profile')).text.includes('MUST_NOT_BE_SAVED'),'Old POST leaves profile unchanged');
 let e=new Client,f=new Client;await Promise.all([e.login(no),f.login(no)]);
 const responses=await Promise.all([e.req('/api/me'),f.req('/api/me')]);
 check(responses.filter(x=>x.status===200).length===1&&responses.filter(x=>x.status===401).length===1,'Parallel same-account HTTP logins leave one winner');
 let winner=responses[0].status===200?e:f;
 check((await winner.req('/api/me')).status===200,'Losing request destruction preserves winner');
 const other=await new Client().login('260002');
 check((await winner.req('/api/me')).status===200&&(await other.req('/api/me')).status===200,'Different accounts remain independent');
 await winner.req('/password');
 let changed=await winner.req('/password','POST',{oldPassword:password,newPassword:password+'Z1',confirmPassword:password+'Z1'});
 check(changed.status===302&&changed.headers.get('location').includes('password-changed'),'Password change redirects to reauthentication');
 check((await winner.req('/api/me')).status===401,'Password change invalidates current browser');
 let fresh=new Client;await fresh.req('/login');
 let wrong=await fresh.req('/login','POST',{employeeNo:no,password});
 check(wrong.status===200,'Old password no longer authenticates');
 let ok=await fresh.req('/login','POST',{employeeNo:no,password:password+'Z1'});
 check(ok.status===302&&(await fresh.req('/api/me')).status===200,'Changed password authenticates');
 await fresh.req('/password');
 await fresh.req('/password','POST',{oldPassword:password+'Z1',newPassword:password,confirmPassword:password});
 let valid=await new Client().login(no),bad=new Client;await bad.req('/login');
 const failures=await Promise.all(Array.from({length:5},()=>bad.req('/login','POST',{employeeNo:no,password:'WrongPassword123'})));
 check(failures.every(x=>x.status===200),'Failed password requests do not authenticate');
 let locked=await bad.req('/login','POST',{employeeNo:no,password});
 check(locked.status===200&&locked.text.includes('锁定'),'Locked login refused');
 check((await valid.req('/api/me')).status===200,'Password failures and lockout do not kick existing valid session');
 const highA=await new Client().login('260001'),highB=await new Client().login('260001');
 check((await highA.req('/admin/salaries/settle','POST',{month:'2040-01'})).status===302,'Superseded privileged write refused');
 check(!(await highB.req('/admin/salaries?month=2040-01')).text.includes('260003'),'Superseded privileged write creates no salary');
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks},null,2));
})().catch(e=>{console.error(e.stack);process.exitCode=1});
