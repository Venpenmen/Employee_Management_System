package com.example.ems.security;
import com.example.ems.service.*;import com.example.ems.model.SessionUser;
import org.springframework.stereotype.Service;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.annotation.Transactional;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.*;import jakarta.servlet.http.HttpSession;import java.util.*;
@Service
public class SecurityService {
 private final JdbcTemplate jdbc;private final SessionService sessions;private final LogService logs;private final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder();
 public SecurityService(JdbcTemplate jdbc,SessionService sessions,LogService logs){this.jdbc=jdbc;this.sessions=sessions;this.logs=logs;}
 public static final class SecondaryRejected extends IllegalArgumentException{public SecondaryRejected(){super("凭证不正确；连续错误将暂停验证15分钟");}}
 private void conflict(){throw new ResponseStatusException(HttpStatus.CONFLICT,"记录已处理、授权过期或状态发生变化，请刷新后重试");}
 private void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
 private HttpSession http(){return ((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest().getSession(false);}
 private String hash(){var s=http();String t=s==null?null:SessionService.token(s);if(t==null)throw new SessionService.SessionExpired();return SessionService.digest(t);}
 private void lock(Long... ids){
  var sorted=Arrays.stream(ids).distinct().sorted().toList();
  for(Long id:sorted)if(jdbc.queryForList("SELECT id FROM employee WHERE id=? FOR UPDATE",id).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"员工不存在");
  for(Long id:sorted)if(jdbc.queryForList("SELECT employee_id FROM user_account WHERE employee_id=? FOR UPDATE",id).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"账号不存在");
 }
 private Map<String,Object> state(Long id){jdbc.update("INSERT IGNORE INTO account_security(employee_id) VALUES(?)",id);return jdbc.queryForMap("SELECT *,temporary_expires_at>NOW(6) temporary_valid FROM account_security WHERE employee_id=? FOR UPDATE",id);}
 private void executive(Long actor,Long target){lock(actor,target);sessions.guardCurrent(actor,2);if(actor.equals(target)||jdbc.queryForObject("SELECT user_type FROM employee WHERE id=? FOR UPDATE",Integer.class,target)==2)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能处理员工或经理账号");}
 private void audit(Long actor,String action,Long target,String summary){logs.log(actor,action,"ACCOUNT_SECURITY",target,summary);jdbc.update("INSERT INTO login_security_event(employee_id,event_type,summary) VALUES(?,?,?)",target,action,summary);}
 public Map<String,Object> overview(SessionUser actor){
  var result=new LinkedHashMap<String,Object>();result.put("secondarySet",jdbc.queryForObject("SELECT COUNT(*) FROM secondary_credential WHERE employee_id=?",Integer.class,actor.id())>0);
  result.put("requests",actor.isExecutive()?jdbc.queryForList("SELECT r.*,e.employee_no,e.name FROM password_change_request r JOIN employee e ON e.id=r.employee_id ORDER BY r.id DESC LIMIT 100"):jdbc.queryForList("SELECT id,reason,status,handling_method,created_at,expires_at FROM password_change_request WHERE employee_id=? ORDER BY id DESC LIMIT 50",actor.id()));
  if(actor.isExecutive())result.put("accounts",jdbc.queryForList("SELECT e.id,e.employee_no,e.name,e.user_type,COALESCE(s.frozen,0) frozen,s.freeze_reason,s.frozen_at,COALESCE(s.temporary_state,'NORMAL') temporary_state FROM employee e JOIN user_account a ON a.employee_id=e.id LEFT JOIN account_security s ON s.employee_id=e.id WHERE e.user_type IN(0,1) ORDER BY e.id"));
  return result;
 }
 @Transactional(noRollbackFor=SecondaryRejected.class)
 public void secondary(Long actor,String loginPassword,String oldSecondary,String next){
  lock(actor);sessions.guardCurrent(actor,2);SecurityPolicy.password(next);
  String loginHash=jdbc.queryForObject("SELECT password_hash FROM user_account WHERE employee_id=?",String.class,actor);
  require(!bcrypt.matches(next,loginHash),"二级密码不能与登录密码相同");
  var rows=jdbc.queryForList("SELECT *,locked_until>NOW(6) locked FROM secondary_credential WHERE employee_id=? FOR UPDATE",actor);
  if(rows.isEmpty()){
   if(loginPassword==null||!bcrypt.matches(loginPassword,loginHash)){jdbc.update("UPDATE user_account SET locked_until=IF(failed_login_count>=4,DATE_ADD(NOW(),INTERVAL 15 MINUTE),locked_until),failed_login_count=IF(failed_login_count>=4,0,failed_login_count+1) WHERE employee_id=?",actor);throw new SecondaryRejected();}
   jdbc.update("INSERT INTO secondary_credential(employee_id,password_hash) VALUES(?,?)",actor,bcrypt.encode(next));
  }else{
   verifySecondary(actor,oldSecondary,rows.get(0));require(!bcrypt.matches(next,String.valueOf(rows.get(0).get("password_hash"))),"新二级密码不能与原二级密码相同");jdbc.update("UPDATE secondary_credential SET password_hash=?,credential_version=credential_version+1,updated_at=NOW(6),failed_attempts=0,locked_until=NULL WHERE employee_id=?",bcrypt.encode(next),actor);
  }
  jdbc.update("DELETE FROM security_grant WHERE actor_id=? AND action IN('RESET','UNFREEZE')",actor);audit(actor,"SECONDARY_CHANGED",actor,"高层设置或修改二级密码");
 }
 private void verifySecondary(Long actor,String supplied,Map<String,Object> row){
  if(truth(row.get("locked")))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"二级密码验证暂停，请稍后重试");
  if(row.get("locked_until")!=null)jdbc.update("UPDATE secondary_credential SET failed_attempts=0,locked_until=NULL WHERE employee_id=?",actor);
  if(supplied==null||!bcrypt.matches(supplied,String.valueOf(row.get("password_hash")))){
   jdbc.update("UPDATE secondary_credential SET failed_attempts=failed_attempts+1,locked_until=IF(failed_attempts>=5,DATE_ADD(NOW(),INTERVAL 15 MINUTE),NULL) WHERE employee_id=?",actor);audit(actor,"SECONDARY_REJECTED",actor,"二级密码验证失败");throw new SecondaryRejected();
  }
  jdbc.update("UPDATE secondary_credential SET failed_attempts=0,locked_until=NULL WHERE employee_id=?",actor);
 }
 @Transactional(noRollbackFor=SecondaryRejected.class)
 public String authorize(Long actor,Long target,String action,String password){
  require(Set.of("RESET","UNFREEZE").contains(action),"无效操作");executive(actor,target);
  var rows=jdbc.queryForList("SELECT *,locked_until>NOW(6) locked FROM secondary_credential WHERE employee_id=? FOR UPDATE",actor);require(!rows.isEmpty(),"请先设置二级密码");
  verifySecondary(actor,password,rows.get(0));String secret=SecurityPolicy.secret();
  jdbc.update("INSERT INTO security_grant(token_hash,employee_id,actor_id,target_id,action,session_hash,credential_version,expires_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(NOW(6),INTERVAL 2 MINUTE))",SessionService.digest(secret),actor,actor,target,action,hash(),rows.get(0).get("credential_version"));return secret;
 }
 private void consume(Long actor,Long target,String action,String token){
  require(token!=null&&token.matches("[A-Za-z0-9_-]{43}"),"操作授权无效");
  int count=jdbc.update("UPDATE security_grant g JOIN secondary_credential c ON c.employee_id=g.actor_id SET g.consumed_at=NOW(6) WHERE g.token_hash=? AND g.actor_id=? AND g.target_id=? AND g.action=? AND g.session_hash=? AND g.credential_version=c.credential_version AND g.consumed_at IS NULL AND g.expires_at>NOW(6)",SessionService.digest(token),actor,target,action,hash());if(count!=1)conflict();
 }
 @Transactional public void request(Long actor,String reason){
  lock(actor);sessions.guardCurrent(actor,0);require(jdbc.queryForObject("SELECT user_type FROM employee WHERE id=? FOR UPDATE",Integer.class,actor)<2,"高层不参与员工密码申请");require(reason!=null&&!reason.isBlank()&&reason.length()<=500,"申请原因必填且不超过500字");
  jdbc.update("UPDATE password_change_request SET status='EXPIRED',active_employee_id=NULL WHERE employee_id=? AND status IN('PENDING','APPROVED') AND expires_at<=NOW(6)",actor);
  if(jdbc.queryForObject("SELECT COUNT(*) FROM password_change_request WHERE active_employee_id=?",Integer.class,actor)>0)conflict();
  jdbc.update("INSERT INTO password_change_request(employee_id,active_employee_id,reason,expires_at) VALUES(?,?,?,DATE_ADD(NOW(6),INTERVAL 7 DAY))",actor,actor,reason.trim());audit(actor,"PASSWORD_REQUEST",actor,"提交密码修改申请");sessions.notifySecurity(actor,"PASSWORD_REQUEST","已提交密码修改申请，等待高层线下核验");
 }
 private Long owner(Long id){var rows=jdbc.queryForList("SELECT employee_id FROM password_change_request WHERE id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"申请不存在");return ((Number)rows.get(0).get("employee_id")).longValue();}
 @Transactional public void cancel(Long actor,Long id){lock(actor);sessions.guardCurrent(actor,0);if(jdbc.queryForObject("SELECT COUNT(*) FROM password_change_request WHERE id=? AND employee_id=?",Integer.class,id,actor)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"申请不存在");if(jdbc.update("UPDATE password_change_request SET status='CANCELLED',active_employee_id=NULL,completed_at=NOW(6) WHERE id=? AND employee_id=? AND status='PENDING'",id,actor)!=1)conflict();audit(actor,"PASSWORD_CANCELLED",actor,"撤回密码申请");}
 @Transactional public String approve(Long actor,Long requestId,boolean approve){
  Long target=owner(requestId);executive(actor,target);var row=jdbc.queryForMap("SELECT *,expires_at>NOW(6) valid FROM password_change_request WHERE id=? FOR UPDATE",requestId);if(!"PENDING".equals(row.get("status"))||!truth(row.get("valid")))conflict();
  if(!approve){jdbc.update("UPDATE password_change_request SET status='REJECTED',active_employee_id=NULL,approver_id=?,completed_at=NOW(6) WHERE id=?",actor,requestId);audit(actor,"PASSWORD_REJECTED",target,"拒绝密码修改申请");sessions.notifySecurity(target,"PASSWORD_REJECTED","密码修改申请未获批准");return null;}
  String secret=SecurityPolicy.secret();jdbc.update("UPDATE password_change_request SET status='APPROVED',approver_id=?,approved_at=NOW(6),handling_method='SELF',expires_at=DATE_ADD(NOW(6),INTERVAL 30 MINUTE) WHERE id=?",actor,requestId);
  jdbc.update("INSERT INTO security_grant(token_hash,employee_id,actor_id,target_id,request_id,action,expires_at) VALUES(?,?,?,?,?,'SELF_RESET',DATE_ADD(NOW(6),INTERVAL 30 MINUTE))",SessionService.digest(secret),target,actor,target,requestId);
  audit(actor,"PASSWORD_APPROVED",target,"线下核验后批准自行重置；重置码单次交付");sessions.notifySecurity(target,"PASSWORD_APPROVED","密码修改申请已批准，请通过线下渠道接收重置码");return secret;
 }
 @Transactional public String reissue(Long actor,Long requestId){
  Long target=owner(requestId);executive(actor,target);var row=jdbc.queryForMap("SELECT * FROM password_change_request WHERE id=? FOR UPDATE",requestId);
  if(!Set.of("APPROVED","EXPIRED").contains(String.valueOf(row.get("status")))||!"SELF".equals(row.get("handling_method")))conflict();
  if(jdbc.queryForObject("SELECT COUNT(*) FROM password_change_request WHERE employee_id=? AND active_employee_id IS NOT NULL AND id<>?",Integer.class,target,requestId)>0)conflict();
  jdbc.update("DELETE FROM security_grant WHERE request_id=? AND action='SELF_RESET'",requestId);String secret=SecurityPolicy.secret();
  jdbc.update("UPDATE password_change_request SET status='APPROVED',active_employee_id=employee_id,approver_id=?,approved_at=NOW(6),expires_at=DATE_ADD(NOW(6),INTERVAL 30 MINUTE),completed_at=NULL WHERE id=?",actor,requestId);
  jdbc.update("INSERT INTO security_grant(token_hash,employee_id,actor_id,target_id,request_id,action,expires_at) VALUES(?,?,?,?,?,'SELF_RESET',DATE_ADD(NOW(6),INTERVAL 30 MINUTE))",SessionService.digest(secret),target,actor,target,requestId);
  audit(actor,"PASSWORD_REISSUED",target,"线下重新核验后签发新重置码，旧码失效");sessions.notifySecurity(target,"PASSWORD_REISSUED","重置码已重新签发，请线下接收，旧码失效");return secret;
 }
 @Transactional public String forceReset(Long actor,Long target,String grant){
  executive(actor,target);consume(actor,target,"RESET",grant);state(target);String temporary="Tmp9"+SecurityPolicy.secret();jdbc.update("UPDATE user_account SET password_hash=?,password_update_time=NOW() WHERE employee_id=?",bcrypt.encode(temporary),target);
  jdbc.update("UPDATE account_security SET temporary_state='TEMP',temporary_expires_at=DATE_ADD(NOW(6),INTERVAL 24 HOUR),temporary_errors=0 WHERE employee_id=?",target);
  jdbc.update("DELETE FROM security_grant WHERE employee_id=? AND action='SELF_RESET'",target);jdbc.update("UPDATE password_change_request SET status='COMPLETED',active_employee_id=NULL,handling_method='ADMIN',approver_id=?,completed_at=NOW(6) WHERE employee_id=? AND status IN('PENDING','APPROVED')",actor,target);
  sessions.revokeLockedAccount(target);audit(actor,"PASSWORD_FORCED",target,"高层签发临时密码并撤销目标会话");sessions.notifySecurity(target,"PASSWORD_FORCED","登录密码已由高层重置，请线下接收临时密码并及时修改");return temporary;
 }
 @Transactional public void selfReset(String no,String code,String next){
  SecurityPolicy.password(next);require(code!=null&&code.matches("[A-Za-z0-9_-]{43}"),"重置资料无效或已过期");var rows=jdbc.queryForList("SELECT e.id FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.employee_no=?",no==null?"":no.trim());require(!rows.isEmpty(),"重置资料无效或已过期");Long id=((Number)rows.get(0).get("id")).longValue();lock(id);var state=state(id);
  require(((Number)state.get("frozen")).intValue()==0,"重置资料无效或已过期");var account=jdbc.queryForMap("SELECT a.account_status,e.employment_status,e.user_type FROM user_account a JOIN employee e ON e.id=a.employee_id WHERE e.id=? FOR UPDATE",id);require(((Number)account.get("account_status")).intValue()==1&&((Number)account.get("employment_status")).intValue()==1&&((Number)account.get("user_type")).intValue()<2,"重置资料无效或已过期");
  var grants=jdbc.queryForList("SELECT * FROM security_grant WHERE token_hash=? AND employee_id=? AND action='SELF_RESET' AND consumed_at IS NULL AND expires_at>NOW(6) FOR UPDATE",SessionService.digest(code),id);require(grants.size()==1,"重置资料无效或已过期");var g=grants.get(0);
  if(jdbc.update("UPDATE password_change_request SET status='COMPLETED',active_employee_id=NULL,completed_at=NOW(6) WHERE id=? AND employee_id=? AND status='APPROVED' AND expires_at>NOW(6)",g.get("request_id"),id)!=1)conflict();
  jdbc.update("UPDATE security_grant SET consumed_at=NOW(6) WHERE id=?",g.get("id"));jdbc.update("UPDATE user_account SET password_hash=?,password_update_time=NOW() WHERE employee_id=?",bcrypt.encode(next),id);jdbc.update("UPDATE account_security SET temporary_state='NORMAL',temporary_expires_at=NULL,temporary_errors=0 WHERE employee_id=?",id);sessions.revokeLockedAccount(id);audit(id,"PASSWORD_SELF_RESET",id,"本人使用线下重置码重设密码");sessions.notifySecurity(id,"PASSWORD_SELF_RESET","密码重置完成，请使用新密码登录");
 }
 @Transactional public void temporaryPassword(Long id,String next){
  SecurityPolicy.password(next);lock(id);var s=state(id);var a=jdbc.queryForMap("SELECT a.password_hash,a.account_status,e.employment_status FROM user_account a JOIN employee e ON e.id=a.employee_id WHERE e.id=? FOR UPDATE",id);
  if(((Number)s.get("frozen")).intValue()!=0||!"TEMP".equals(s.get("temporary_state"))||!truth(s.get("temporary_valid"))||((Number)a.get("account_status")).intValue()!=1||((Number)a.get("employment_status")).intValue()!=1||sessions.validate(id,sessions.requireObserved(id))!=SessionService.Validity.VALID)throw new SessionService.SessionExpired();
  require(!bcrypt.matches(next,String.valueOf(a.get("password_hash"))),"正式密码不能与临时密码相同");jdbc.update("UPDATE user_account SET password_hash=?,password_update_time=NOW() WHERE employee_id=?",bcrypt.encode(next),id);jdbc.update("UPDATE account_security SET temporary_state='NORMAL',temporary_expires_at=NULL,temporary_errors=0 WHERE employee_id=?",id);sessions.revokeLockedAccount(id);audit(id,"TEMP_PASSWORD_CHANGED",id,"临时密码改为正式密码并退出受限会话");
 }
 @Transactional public void unfreeze(Long actor,Long target,String grant,String reason){
  require(reason!=null&&!reason.isBlank()&&reason.length()<=400,"处理理由必填且不超过400字");executive(actor,target);consume(actor,target,"UNFREEZE",grant);state(target);
  if(jdbc.update("UPDATE account_security SET frozen=0,freeze_reason=NULL,frozen_at=NULL,anomaly_epoch=NOW(6),warning_sent=0 WHERE employee_id=? AND frozen=1",target)!=1)conflict();sessions.revokeLockedAccount(target);audit(actor,"SECURITY_UNFROZEN",target,"高层解除安全冻结："+reason.trim());sessions.notifySecurity(target,"SECURITY_UNFROZEN","安全冻结已解除，原有其他账号限制保持不变");
 }
 private java.time.LocalDate filterDate(String value){try{return java.time.LocalDate.parse(value);}catch(java.time.format.DateTimeParseException e){throw new IllegalArgumentException("日期格式不正确，请使用yyyy-MM-dd");}}
 public List<Map<String,Object>> notifications(Long actor,String type,String query,String from,String until,String unread){
  require((type==null||type.length()<=40)&&(query==null||query.length()<=50),"查询条件过长");if(from!=null&&!from.isBlank()&&until!=null&&!until.isBlank())require(!filterDate(from).isAfter(filterDate(until)),"开始日期不能晚于结束日期");
  StringBuilder sql=new StringBuilder("SELECT n.id,n.event_type,n.summary,n.created_at,n.read_at,e.employee_no,e.name FROM security_notification n JOIN employee e ON e.id=n.subject_employee_id WHERE n.employee_id=?");List<Object> args=new ArrayList<>();args.add(actor);
  if(type!=null&&!type.isBlank()){sql.append(" AND n.event_type=?");args.add(type);}if(query!=null&&!query.isBlank()){sql.append(" AND (e.employee_no LIKE ? OR e.name LIKE ?)");args.add("%"+query+"%");args.add("%"+query+"%");}
  if(from!=null&&!from.isBlank()){sql.append(" AND n.created_at>=?");args.add(filterDate(from).atStartOfDay());}if(until!=null&&!until.isBlank()){sql.append(" AND n.created_at<?");args.add(filterDate(until).plusDays(1).atStartOfDay());}if("1".equals(unread))sql.append(" AND n.read_at IS NULL");sql.append(" ORDER BY n.id DESC LIMIT 100");return jdbc.queryForList(sql.toString(),args.toArray());
 }
 @Transactional public void read(Long actor,Long id){lock(actor);sessions.guardCurrent(actor,0);if(jdbc.update("UPDATE security_notification SET read_at=COALESCE(read_at,NOW(6)) WHERE id=? AND employee_id=?",id,actor)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"通知不存在");}
 public static boolean truth(Object o){return o instanceof Boolean b?b:o instanceof Number n&&n.intValue()!=0;}
}
