
const fs=require('node:fs'),path=require('node:path');
const [base,out,workbook]=process.argv.slice(2),password=process.env.EMS_TEST_PASSWORD;
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
 const admin=await new Client().login('260001'),manager=await new Client().login('260002'),employee=await new Client().login('260003');
 await admin.req('/admin/salaries/settle','POST',{month:'2031-01'});
 let salary=(await admin.req('/admin/salaries?month=2031-01')).text;
 let salaryId=salary.match(/\/admin\/salaries\/(\d+)\/adjust/);check(!!salaryId,'Salary settlement produces adjustable records');
 let adjusted=await admin.req('/admin/salaries/'+salaryId[1]+'/adjust','POST',{month:'2031-01',reward:'123.45',subsidy:'200',overtimePay:'0',otherIncome:'0',deduction:'10',otherDeduction:'0'});
 check(adjusted.status===302,'Draft salary adjustment succeeds');
 await admin.req('/admin/salaries/publish','POST',{month:'2031-01'});
 check((await admin.req('/admin/salaries/'+salaryId[1]+'/adjust','POST',{month:'2031-01',reward:'1',subsidy:'0',overtimePay:'0',otherIncome:'0',deduction:'0',otherDeduction:'0'})).status===409,'Published salary cannot be overwritten');
 const oldAttendance=await admin.req('/attendance/manage');
 const attendanceId=oldAttendance.text.match(/\/attendance\/(\d+)\/correct/);check(!!attendanceId,'Existing attendance can be located');
 check((await manager.req('/attendance/'+attendanceId[1]+'/correct','POST',{status:'8',reason:'forbidden',month:'2030-01'})).status===403,'Manager cannot correct attendance');
 await admin.req('/attendance/'+attendanceId[1]+'/correct','POST',{status:'8',reason:'虚构回归修正',month:'2030-01'});
 await admin.req('/attendance/rule','POST',{workStart:'09:30',workEnd:'18:30',lateGrace:'5',earlyGrace:'0'});
 check((await admin.req('/attendance/manage')).text.includes('09:30'),'Attendance rule update retained');
 const options=()=>admin.req('/api/dictionaries/employee-options').then(x=>JSON.parse(x.text).data);
 await admin.req('/admin/departments','POST',{name:'回归空部门',baseSalary:'4000',description:'虚构'});
 let dept=(await options()).departments.find(x=>x.department_name==='回归空部门');check(!!dept,'Department creation retained');
 await admin.req('/admin/departments/'+dept.id+'/update','POST',{departmentName:'回归空部门改',baseSalary:'4100',description:'虚构'});
 check((await options()).departments.some(x=>x.department_name==='回归空部门改'),'Department modification retained');
 await admin.req('/admin/departments/'+dept.id+'/delete','POST',{});
 check(!(await options()).departments.some(x=>x.id===dept.id),'Dissolved department is hidden from selectable options');
 await admin.req('/admin/departments/1/delete','POST',{});
 check((await options()).departments.find(x=>x.id===1).status===1,'Populated department dissolution rejected atomically');
 await admin.req('/admin/positions','POST',{departmentId:'1',name:'回归岗位',salary:'2100',managerPosition:'false'});
 let position=(await options()).positions.find(x=>x.position_name==='回归岗位');check(!!position,'Position creation retained');
 await manager.req('/employees/260003/position','POST',{positionId:String(position.id),reason:'虚构职位调整'});
 check((await employee.req('/profile')).text.includes('回归岗位'),'Manager can adjust own department employee position');
 const sheet=new FormData();sheet.set('_csrf',admin.token);sheet.set('file',new Blob([fs.readFileSync(workbook)]),'synthetic.xlsx');
 const upload=await fetch(base+'/admin/recruitment/import',{method:'POST',headers:{Cookie:Object.entries(admin.jar).map(([k,v])=>k+'='+v).join('; ')},body:sheet});
 const imported=await upload.text();check(upload.status===200&&imported.includes('身份证号已经存在')&&imported.includes('身份证号格式不正确'),'Excel batch reports duplicate and invalid rows');
 check((await admin.req('/employees?keyword=BatchSyntheticA')).text.includes('BatchSyntheticA'),'Valid Excel row commits through recruitment workflow');
 // Manager's own leave must route to executive and cannot be self-approved.
 const leaveFields=(month)=>({leaveType:'PERSONAL',startTime:'2033-'+month+'-10T09:00',endTime:'2033-'+month+'-10T10:00',reason:'虚构回归请假'});
 await manager.req('/leaves','POST',leaveFields('01'));
 let page=(await manager.req('/leaves/my')).text;let match=[...page.matchAll(/L[0-9A-F]{16}/g)];let requestNo=match.length?match[0][0]:null;
 check(!!requestNo,'Manager leave submitted');check((await admin.req('/leaves/approvals')).text.includes(requestNo),'Manager leave routes to executive');
 await manager.req('/leaves/'+requestNo+'/approve','POST',{opinion:'self'});
 check((await admin.req('/leaves/approvals')).text.includes(requestNo),'Manager cannot self-approve');
 await admin.req('/leaves/'+requestNo+'/approve','POST',{opinion:'虚构高层审批'});
 await employee.req('/leaves','POST',leaveFields('02'));
 const approvals=(await manager.req('/leaves/approvals')).text;
 const pending=approvals.match(/\/leaves\/(L[0-9A-F]+)\/approve/);check(!!pending,'Ordinary employee leave initially routes to manager');
 await admin.req('/admin/departments/1/manager','POST',{employeeNo:'260003'});
 check(JSON.parse((await manager.req('/api/me')).text).data.userType===0,'Former manager immediately loses manager role');
 check(JSON.parse((await employee.req('/api/me')).text).data.userType===1,'New manager role refreshed without re-login');
 check((await admin.req('/leaves/approvals')).text.includes(pending[1]),'Promoted manager pending own leave transfers to executive');
 check((await manager.req('/leaves/approvals')).status===403,'Demoted manager cannot access approval management');
 await admin.req('/leaves/'+pending[1]+'/approve','POST',{opinion:'任命后高层审批'});
 fs.writeFileSync(out,JSON.stringify({passed:true,count:checks.length,checks},null,2));
})().catch(e=>{console.error(e.stack);process.exitCode=1});
