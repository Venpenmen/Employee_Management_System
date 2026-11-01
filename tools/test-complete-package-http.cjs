const fs=require('node:fs');
const [base,mode,phase,out]=process.argv.slice(2),password=process.env.EMS_TEST_PASSWORD;
const checks=[];function check(ok,m){if(!ok)throw Error(m);checks.push(m);console.log('PASS '+m)}
const jar={};let token='';
async function req(route,fields){const headers={Cookie:Object.entries(jar).map(([k,v])=>k+'='+v).join('; ')};let body;if(fields){body=new URLSearchParams({...fields,_csrf:token})}const r=await fetch(base+route,{method:fields?'POST':'GET',headers,body,redirect:'manual'});for(const value of r.headers.getSetCookie()){const p=value.split(';')[0],i=p.indexOf('=');jar[p.slice(0,i)]=p.slice(i+1)}const text=await r.text();for(const tag of text.matchAll(/<input\b[^>]*>/g))if(/name="_csrf"/.test(tag[0])){const m=tag[0].match(/value="([^"]*)"/);if(m)token=m[1]}return {status:r.status,text,headers:r.headers}}
(async()=>{
 check((await req('/health')).text.includes('UP'),'Extracted package healthy');
 await req('/login');check(!!token,'Frontend and CSRF rendered');const cookie='EMS121N_COMPLETE_'+mode.toUpperCase(),before=jar[cookie];
 check((await req('/login',{employeeNo:'260001',password})).status===302,'Actual original account login');check(!!jar[cookie]&&jar[cookie]!==before,'Mode-isolated cookie and Session ID rotation');
 const page=await req('/profile');check(page.status===200,'Actual frontend/backend/database profile');
 if(phase==='write'){
  const edit=await req('/profile/edit');const version=edit.text.match(/name="version"[^>]*value="([^"]*)"/)?.[1];check(version!==undefined,'Optimistic version available');
  const r=await req('/profile',{version,phone:'13800000001',email:mode+'@example.invalid',address:'Synthetic persistent '+mode,emergencyName:'',emergencyRelation:'',emergencyPhone:'',biography:'',gender:''});check(r.status===302,'Protected persistent profile write');
 }else{check(page.text.includes('Synthetic persistent '+mode)&&page.text.includes(mode+'@example.invalid'),'Profile values survived restart/move/crash')}
 check((await req('/api/me')).status===200,'Authenticated API available');
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks,mode,phase},null,2));
})().catch(e=>{console.error(e.stack);process.exitCode=1});
