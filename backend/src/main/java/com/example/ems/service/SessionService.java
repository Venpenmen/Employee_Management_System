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
 private final JdbcTemplate jdbc;
 private final SecureRandom random=new SecureRandom();
 private final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder();
 public SessionService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public static String digest(String token){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
 }
 public static String token(HttpSession session){Object v=session.getAttribute(TOKEN);return v instanceof String s?s:null;}
 private Map<String,Object> lockAccount(Long id){
  var rows=jdbc.queryForList("SELECT employee_id,password_hash,account_status,locked_until FROM user_account WHERE employee_id=? FOR UPDATE",id);
  if(rows.isEmpty())throw new SessionExpired();return rows.get(0);
 }
 private Map<String,Object> employee(Long id){
  var rows=jdbc.queryForList("SELECT employment_status,user_type FROM employee WHERE id=?",id);
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
  var account=lockAccount(employeeId);active(account,employee(employeeId));
  var locked=date(account.get("locked_until"));
  if(locked!=null&&locked.isAfter(LocalDateTime.now()))throw new IllegalArgumentException("账户已锁定，请稍后再试");
  if(!bcrypt.matches(password,String.valueOf(account.get("password_hash"))))throw new IllegalArgumentException("凭证已变化，请重新登录");
  byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  jdbc.update("INSERT INTO account_active_session(employee_id,token_hash,updated_at) VALUES(?,?,NOW(6)) ON DUPLICATE KEY UPDATE token_hash=?,updated_at=NOW(6)",employeeId,digest(token),digest(token));
  jdbc.update("UPDATE user_account SET failed_login_count=0,locked_until=NULL,last_login_time=NOW() WHERE employee_id=?",employeeId);
  return token;
 }
 public Validity validate(Long id,String token){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return Validity.EXPIRED;
  var rows=jdbc.queryForList("SELECT token_hash FROM account_active_session WHERE employee_id=?",id);
  if(rows.isEmpty())return Validity.EXPIRED;
  return MessageDigest.isEqual(digest(token).getBytes(StandardCharsets.US_ASCII),String.valueOf(rows.get(0).get("token_hash")).getBytes(StandardCharsets.US_ASCII))?Validity.VALID:Validity.REPLACED;
 }
 private void deleteHash(Long id,String hash){
  if(hash!=null)jdbc.update("DELETE FROM account_active_session WHERE employee_id=? AND token_hash=?",id,hash);
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
  var rows=jdbc.queryForList("SELECT token_hash FROM account_active_session WHERE employee_id=?",id);
  if(!rows.isEmpty())deleteHash(id,String.valueOf(rows.get(0).get("token_hash")));
 }
 public void guardCurrent(Long actorId,int minimumRole){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Session guard requires transaction");
  var attributes=RequestContextHolder.getRequestAttributes();
  if(!(attributes instanceof ServletRequestAttributes a))throw new SessionExpired();
  HttpSession session=a.getRequest().getSession(false);Object stored=session==null?null:session.getAttribute("user");
  if(!(stored instanceof SessionUser u)||!u.id().equals(actorId))throw new SessionExpired();
  var account=lockAccount(actorId);var person=employee(actorId);active(account,person);
  if(validate(actorId,token(session))!=Validity.VALID)throw new SessionExpired();
  if(((Number)person.get("user_type")).intValue()<minimumRole)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权执行此操作");
 }
 @Transactional
 public void changePassword(Long id,String oldPassword,String newPassword,LogService logs){
  guardCurrent(id,0);var account=lockAccount(id);
  if(!bcrypt.matches(oldPassword,String.valueOf(account.get("password_hash"))))throw new IllegalArgumentException("原密码不正确");
  if(newPassword==null||newPassword.length()<8||newPassword.getBytes(StandardCharsets.UTF_8).length>72||!newPassword.matches("(?s).*[A-Za-z].*")||!newPassword.matches("(?s).*\\d.*"))throw new IllegalArgumentException("新密码至少8位、最多72字节，且必须包含字母和数字");
  String current=token(((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest().getSession(false));
  jdbc.update("UPDATE user_account SET password_hash=?,password_update_time=NOW() WHERE employee_id=?",bcrypt.encode(newPassword),id);
  deleteHash(id,digest(current));logs.log(id,"CHANGE_PASSWORD","ACCOUNT",id,"用户修改密码并撤销有效会话");
 }
 public static final class SessionExpired extends ResponseStatusException {
  public SessionExpired(){super(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录");}
 }
}
