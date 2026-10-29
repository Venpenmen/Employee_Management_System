
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
 let admin=await new Client().login('260001'),employee=await new Client().login('260002');
 check((await admin.req('/profile')).text.includes('虚构高层'),'Restored legacy executive profile is accessible');
 check((await employee.req('/my-salaries?month=2030-01')).text.includes('12,345.67'),'Restored legacy salary decimal is visible');
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks},null,2));
})().catch(e=>{console.error(e.stack);process.exitCode=1});
