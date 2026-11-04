package com.example.ems.service;
import com.example.ems.model.SessionUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
@Service
public class SessionService {
 public static final String TOKEN="ems.authToken";
 public enum Validity {VALID,REPLACED,EXPIRED}
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.example.ems.security.SecurityEvents events;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private com.example.ems.security.SecurityPolicy policy;
 private final JdbcTemplate jdbc;
 private final SecureRandom random=new SecureRandom();
 private final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder();
 public SessionService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public static String digest(String token){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
 }
 public static String token(HttpSession session){if(session==null)return null;try{Object v=session.getAttribute(TOKEN);return v instanceof String s?s:null;}catch(IllegalStateException closed){return null;}}
 public static void invalidateQuietly(HttpSession session){if(session!=null)try{session.invalidate();}catch(IllegalStateException alreadyClosed){}}
 public static void invalidateObserved(HttpSession session,Long id,String observed){if(session==null)return;synchronized(session){try{Object current=session.getAttribute("user");if(current instanceof SessionUser user&&user.id().equals(id)&&Objects.equals(token(session),observed))session.invalidate();}catch(IllegalStateException alreadyClosed){}}}
 private Map<String,Object> lockAccount(Long id){
  var rows=jdbc.queryForList("SELECT employee_id,password_hash,account_status,locked_until FROM user_account WHERE employee_id=? FOR UPDATE",id);
  if(rows.isEmpty())throw new SessionExpired();return rows.get(0);
 }
 private Map<String,Object> employee(Long id){
  var rows=jdbc.queryForList("SELECT employment_status,user_type FROM employee WHERE id=? FOR UPDATE",id);
  if(rows.isEmpty())throw new SessionExpired();return rows.get(0);
 }
 private void active(Map<String,Object> account,Map<String,Object> employee){
  int role=((Number)employee.get("user_type")).intValue();
  if(role<0||role>2)throw new SessionExpired();
  if(((Number)account.get("account_status")).intValue()!=1||((Number)employee.get("employment_status")).intValue()!=1)throw new SessionExpired();
 }
 private static LocalDateTime date(Object v){return v instanceof java.sql.Timestamp t?t.toLocalDateTime():(LocalDateTime)v;}
 @Transactional
 public String issue(Long employeeId,String password){
  var person=employee(employeeId);var account=lockAccount(employeeId);active(account,person);
  var locked=date(account.get("locked_until"));
  if(locked!=null&&locked.isAfter(LocalDateTime.now()))throw new IllegalArgumentException("账户已锁定，请稍后再试");
  if(!bcrypt.matches(password,String.valueOf(account.get("password_hash"))))throw new IllegalArgumentException("凭证已变化，请重新登录");
  jdbc.update("INSERT IGNORE INTO account_security(employee_id) VALUES(?)",employeeId);
  var state=jdbc.queryForMap("SELECT *,temporary_expires_at>NOW(6) temporary_valid FROM account_security WHERE employee_id=? FOR UPDATE",employeeId);
  if(((Number)state.get("frozen")).intValue()!=0)throw new IllegalArgumentException("账号已安全冻结，请联系高层处理");
  if("REISSUE".equals(state.get("temporary_state"))||("TEMP".equals(state.get("temporary_state"))&&!Boolean.TRUE.equals(asBoolean(state.get("temporary_valid")))))throw new IllegalArgumentException("临时密码已失效，请联系高层重新签发");
  var previous=jdbc.queryForList("SELECT token_hash FROM account_active_session WHERE employee_id=? FOR UPDATE",employeeId);
  String oldHash=previous.isEmpty()?null:String.valueOf(previous.get(0).get("token_hash"));
  boolean sameSession=false;var attrs=RequestContextHolder.getRequestAttributes();String address="未知",browser="未知",device="未知";
  if(attrs instanceof ServletRequestAttributes req){var existing=req.getRequest().getSession(false);String prior=existing==null?null:token(existing);sameSession=prior!=null&&digest(prior).equals(oldHash);address=com.example.ems.security.SecurityPolicy.address(req.getRequest().getRemoteAddr());String ua=Optional.ofNullable(req.getRequest().getHeader("User-Agent")).orElse("");browser=ua.contains("Edg/")?"Edge":ua.contains("Chrome/")?"Chrome":ua.contains("Firefox/")?"Firefox":ua.contains("Safari/")?"Safari":"未知";device=ua.contains("Mobile")?"移动设备":"电脑或未知设备";}
  if(oldHash!=null&&!sameSession){
   jdbc.update("INSERT INTO login_security_event(employee_id,event_type,masked_address,device_type,browser_type,summary) VALUES(?,'SESSION_REPLACED',?,?,?,'成功登录替换有效会话')",employeeId,address,device,browser);
   if(((Number)employee(employeeId).get("user_type")).intValue()!=2){
    var p=policy==null?new com.example.ems.security.SecurityPolicy():policy;
    int count=jdbc.queryForObject("SELECT COUNT(*) FROM login_security_event WHERE employee_id=? AND event_type='SESSION_REPLACED' AND created_at>=GREATEST(?,DATE_SUB(NOW(6),INTERVAL ? MINUTE))",Integer.class,employeeId,state.get("anomaly_epoch"),p.getWindowMinutes());
    if(count<p.getWarningThreshold())jdbc.update("UPDATE account_security SET warning_sent=0 WHERE employee_id=?",employeeId);
    if(count>=p.getFreezeThreshold()){
     jdbc.update("UPDATE account_security SET frozen=1,freeze_reason='短时间频繁会话替换',frozen_at=NOW(6) WHERE employee_id=?",employeeId);deleteHash(employeeId,oldHash);jdbc.update("INSERT INTO operation_log(operator_id,operation_type,target_type,target_id,summary) VALUES(?,'SECURITY_FROZEN','ACCOUNT_SECURITY',?,'频繁会话替换触发安全冻结')",employeeId,employeeId);notifySecurity(employeeId,"SECURITY_FROZEN","账号因频繁会话替换已安全冻结");
     String old=oldHash;if(events!=null)afterCommit(()->events.ended(employeeId,old));return null;
    }
    if(count>=p.getWarningThreshold()&&((Number)state.get("warning_sent")).intValue()==0){jdbc.update("UPDATE account_security SET warning_sent=1 WHERE employee_id=?",employeeId);notifySecurity(employeeId,"LOGIN_WARNING","账号短时间内多次在其他会话登录，请检查安全状态");}
   }
  }
  if(oldHash!=null&&sameSession){String old=oldHash;if(events!=null)afterCommit(()->events.refreshed(employeeId,old));}
  if(oldHash!=null&&!sameSession){String old=oldHash;Map<String,Object> info=Map.of("message","账号已在其他设备登录","time",jdbc.queryForObject("SELECT DATE_FORMAT(NOW(),'%Y-%m-%d %H:%i:%s')",String.class),"address",address,"browser",browser,"device",device);if(events!=null)afterCommit(()->events.replaced(employeeId,old,info));}
  byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  jdbc.update("INSERT INTO account_active_session(employee_id,token_hash,updated_at) VALUES(?,?,NOW(6)) ON DUPLICATE KEY UPDATE token_hash=?,updated_at=NOW(6)",employeeId,digest(token),digest(token));
  jdbc.update("UPDATE user_account SET failed_login_count=0,locked_until=NULL,last_login_time=NOW() WHERE employee_id=?",employeeId);
  return token;
 }
 public Validity validate(Long id,String token){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return Validity.EXPIRED;
  var rows=jdbc.queryForList("SELECT token_hash FROM account_active_session WHERE employee_id=?"+(TransactionSynchronizationManager.isActualTransactionActive()?" FOR UPDATE":""),id);
  if(rows.isEmpty())return Validity.EXPIRED;
  return MessageDigest.isEqual(digest(token).getBytes(StandardCharsets.US_ASCII),String.valueOf(rows.get(0).get("token_hash")).getBytes(StandardCharsets.US_ASCII))?Validity.VALID:Validity.REPLACED;
 }
 private void deleteHash(Long id,String hash){
  if(hash!=null){jdbc.update("DELETE FROM account_active_session WHERE employee_id=? AND token_hash=?",id,hash);if(events!=null)afterCommit(()->events.ended(id,hash));}
 }
 @Transactional
 public void revoke(Long id,String token){
  if(token==null)return;
  var rows=jdbc.queryForList("SELECT employee_id FROM user_account WHERE employee_id=? FOR UPDATE",id);
  if(!rows.isEmpty())deleteHash(id,digest(token));
 }
 public void revokeLockedAccount(Long id){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Revocation requires transaction");
  lockAccount(id);
  var rows=jdbc.queryForList("SELECT token_hash FROM account_active_session WHERE employee_id=?"+(TransactionSynchronizationManager.isActualTransactionActive()?" FOR UPDATE":""),id);
  if(!rows.isEmpty())deleteHash(id,String.valueOf(rows.get(0).get("token_hash")));
 }
 public void guardPersonal(Long actorId){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Personal guard requires transaction");
  var rows=jdbc.queryForList("SELECT id FROM employee WHERE id=? FOR UPDATE",actorId);
  if(rows.isEmpty())throw new SessionExpired();
  guardCurrent(actorId,0);
 }
 public String requireObserved(Long actorId){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Session proof requires transaction");
  var attrs=RequestContextHolder.getRequestAttributes();if(!(attrs instanceof ServletRequestAttributes a))throw new SessionExpired();
  var session=a.getRequest().getSession(false);Object stored;try{stored=session==null?null:session.getAttribute("user");}catch(IllegalStateException closed){throw new SessionExpired();}
  String current=token(session);if(!(stored instanceof SessionUser user)||!user.id().equals(actorId)||current==null)throw new SessionExpired();
  Object observedId=a.getRequest().getAttribute("ems.auth.observedUserId"),observedToken=a.getRequest().getAttribute("ems.auth.observedToken");
  if(observedId!=null&&(!actorId.equals(observedId)||!Objects.equals(current,observedToken)))throw new SessionExpired();return current;
 }
 public void guardCurrent(Long actorId,int minimumRole){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Session guard requires transaction");
  String observed=requireObserved(actorId);
  var person=employee(actorId);var account=lockAccount(actorId);active(account,person);assertNormal(actorId);
  if(validate(actorId,observed)!=Validity.VALID)throw new SessionExpired();
  if(((Number)person.get("user_type")).intValue()<minimumRole)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权执行此操作");
 }
 @Transactional
 public void changePassword(Long id,String oldPassword,String newPassword,LogService logs){
  guardCurrent(id,0);var account=lockAccount(id);
  if(!bcrypt.matches(oldPassword,String.valueOf(account.get("password_hash"))))throw new IllegalArgumentException("原密码不正确");
  if(newPassword==null||newPassword.length()<8||newPassword.getBytes(StandardCharsets.UTF_8).length>72||!newPassword.matches("(?s).*[A-Za-z].*")||!newPassword.matches("(?s).*\\d.*"))throw new IllegalArgumentException("新密码至少8位、最多72字节，且必须包含字母和数字");
  var secondary=jdbc.queryForList("SELECT password_hash FROM secondary_credential WHERE employee_id=?",id);
  if(!secondary.isEmpty()&&bcrypt.matches(newPassword,String.valueOf(secondary.get(0).get("password_hash"))))throw new IllegalArgumentException("登录密码不能与二级密码相同");
  jdbc.update("DELETE FROM security_grant WHERE employee_id=? AND action='SELF_RESET'",id);
  jdbc.update("UPDATE password_change_request SET status='CANCELLED',active_employee_id=NULL,completed_at=NOW(6) WHERE employee_id=? AND status='APPROVED'",id);
  String current=token(((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest().getSession(false));
  jdbc.update("UPDATE user_account SET password_hash=?,password_update_time=NOW() WHERE employee_id=?",bcrypt.encode(newPassword),id);
  deleteHash(id,digest(current));logs.log(id,"CHANGE_PASSWORD","ACCOUNT",id,"用户修改密码并撤销有效会话");
 }
 private static Boolean asBoolean(Object o){return o instanceof Boolean b?b:o instanceof Number n&&n.intValue()!=0;}
 private void afterCommit(Runnable work){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCommit(){try{work.run();}catch(Exception ignored){}}});else work.run();}
 public void notifySecurity(Long id,String type,String message){
  jdbc.update("INSERT INTO security_notification(employee_id,event_type,subject_employee_id,summary) VALUES(?,?,?,?)",id,type,id,message);
  jdbc.update("INSERT INTO security_notification(employee_id,event_type,subject_employee_id,summary) SELECT e.id,?,?,? FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.user_type=2 AND e.employment_status=1 AND a.account_status=1 AND e.id<>?",type,id,message,id);
 }
 public boolean restricted(Long id){return jdbc.queryForObject("SELECT COUNT(*) FROM account_security WHERE employee_id=? AND temporary_state='TEMP'",Integer.class,id)>0;}
 public void assertNormal(Long id){if(jdbc.queryForObject("SELECT COUNT(*) FROM account_security WHERE employee_id=? AND (frozen=1 OR temporary_state<>'NORMAL')",Integer.class,id)>0)throw new SessionExpired();}
 @Transactional public void failedTemporary(Long id){lockAccount(id);jdbc.update("UPDATE account_security SET temporary_errors=temporary_errors+1,temporary_state=IF(temporary_errors>=5,'REISSUE',temporary_state) WHERE employee_id=? AND temporary_state='TEMP'",id);}
 public static final class SessionExpired extends ResponseStatusException {
  public SessionExpired(){super(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录");}
 }
}
