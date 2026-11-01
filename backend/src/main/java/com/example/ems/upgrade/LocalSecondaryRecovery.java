package com.example.ems.upgrade;

import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Local, stopped-application recovery. Not a web service or a password-only backdoor. */
final class LocalSecondaryRecovery {
 record Captured(long employeeId,String primaryHash,String secondaryHash,long credentialVersion,String identityDigest){
  @Override public String toString(){return "Captured[private recovery baseline]";}
 }
 static final class CommitUncertain extends SQLException {CommitUncertain(SQLException cause){super("Recovery commit acknowledgement uncertain; inspect the request audit before any retry",cause);}}
 private LocalSecondaryRecovery(){}
 private static void require(boolean ok){if(!ok)throw new IllegalArgumentException("本机恢复核验失败、审批身份不符或记录已变化；未授权覆盖");}
 private static long id(Connection c,String no)throws SQLException{
  require(no!=null&&no.matches("[0-9]{6}"));try(var s=c.prepareStatement("SELECT id FROM employee WHERE employee_no=?")){s.setString(1,no);try(var r=s.executeQuery()){require(r.next());return r.getLong(1);}}
 }
 static void clear(Connection c,Captured captured,String targetNo,String targetPassword,String reviewerNo,String reviewerPassword,String requestId,String backupSha)throws Exception{
  require(c.getAutoCommit());require(requestId!=null&&requestId.matches("[a-f0-9-]{36}")&&backupSha!=null&&backupSha.matches("[a-f0-9]{64}"));
  require(targetPassword!=null&&reviewerPassword!=null&&targetPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72&&reviewerPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72);
  require(!targetPassword.equals(reviewerPassword));
  long target=id(c,targetNo),reviewer=id(c,reviewerNo);require(target==captured.employeeId()&&target!=reviewer);
  List<Long> order=java.util.stream.LongStream.of(target,reviewer).sorted().boxed().toList();boolean committing=false,reset=true;Exception primary=null;
  c.setAutoCommit(false);
  try{
   // Identical global order to SecurityService: all employees, all accounts,
   // then security/credential/session rows. Current reads, never a stale RR snapshot.
   for(long employee:order)try(var s=c.prepareStatement("SELECT employee_no,employment_status,user_type,identity_number FROM employee WHERE id=? FOR UPDATE")){s.setLong(1,employee);try(var r=s.executeQuery()){require(r.next()&&r.getInt(2)==1&&r.getInt(3)==2);require(r.getString(1).equals(employee==target?targetNo:reviewerNo));if(employee==target)require(com.example.ems.service.SessionService.digest(r.getString(4)).equals(captured.identityDigest()));}}
   Map<Long,String> passwords=new HashMap<>();
   for(long employee:order)try(var s=c.prepareStatement("SELECT password_hash,account_status,locked_until>NOW(6) locked FROM user_account WHERE employee_id=? FOR UPDATE")){s.setLong(1,employee);try(var r=s.executeQuery()){require(r.next()&&r.getInt(2)==1&&!r.getBoolean(3));passwords.put(employee,r.getString(1));}}
   for(long employee:order)try(var s=c.prepareStatement("SELECT frozen,temporary_state FROM account_security WHERE employee_id=? FOR UPDATE")){s.setLong(1,employee);try(var r=s.executeQuery()){if(r.next())require(r.getInt(1)==0&&"NORMAL".equals(r.getString(2)));}}
   var bcrypt=new BCryptPasswordEncoder();require(bcrypt.matches(targetPassword,passwords.get(target))&&bcrypt.matches(reviewerPassword,passwords.get(reviewer)));
   require(Objects.equals(passwords.get(target),captured.primaryHash()));
   try(var s=c.prepareStatement("SELECT password_hash,credential_version FROM secondary_credential WHERE employee_id=? FOR UPDATE")){s.setLong(1,target);try(var r=s.executeQuery()){require(r.next()&&r.getLong(2)==captured.credentialVersion()&&Objects.equals(r.getString(1),captured.secondaryHash()));}}
   String summary="request="+requestId+";backup="+backupSha;
   try(var s=c.prepareStatement("SELECT COUNT(*) FROM operation_log WHERE operation_type='SECONDARY_RECOVERY' AND target_id=? AND summary=?")){s.setLong(1,target);s.setString(2,summary);try(var r=s.executeQuery()){r.next();require(r.getInt(1)==0);}}
   String activeHash=null;try(var s=c.prepareStatement("SELECT token_hash FROM account_active_session WHERE employee_id=? FOR UPDATE")){s.setLong(1,target);try(var r=s.executeQuery()){if(r.next())activeHash=r.getString(1);}}
   try(var s=c.prepareStatement("DELETE FROM secondary_credential WHERE employee_id=? AND credential_version=? AND password_hash=?")){s.setLong(1,target);s.setLong(2,captured.credentialVersion());s.setString(3,captured.secondaryHash());require(s.executeUpdate()==1);}
   try(var s=c.prepareStatement("DELETE FROM security_grant WHERE actor_id=? AND credential_version=?")){s.setLong(1,target);s.setLong(2,captured.credentialVersion());s.executeUpdate();}
   if(activeHash!=null)try(var s=c.prepareStatement("DELETE FROM account_active_session WHERE employee_id=? AND token_hash=?")){s.setLong(1,target);s.setString(2,activeHash);s.executeUpdate();}
   try(var s=c.prepareStatement("INSERT INTO operation_log(operator_id,operation_type,target_type,target_id,summary) VALUES(?,'SECONDARY_RECOVERY','ACCOUNT_SECURITY',?,?)")){s.setLong(1,reviewer);s.setLong(2,target);s.setString(3,summary);s.executeUpdate();}
   try(var s=c.prepareStatement("INSERT INTO login_security_event(employee_id,event_type,summary) VALUES(?,'SECONDARY_RECOVERY','独立复核后本机恢复二级凭证，原授权及目标会话已撤销')")){s.setLong(1,target);s.executeUpdate();}
   try(var s=c.prepareStatement("INSERT INTO security_notification(employee_id,event_type,subject_employee_id,summary) VALUES(?,'SECONDARY_RECOVERY',?,'本机受控恢复已完成，请重新登录并设置二级密码')")){s.setLong(1,target);s.setLong(2,target);s.executeUpdate();}
   committing=true;c.commit();
  }catch(Exception failure){
   primary=failure;
   if(committing&&failure instanceof SQLException sql){reset=false;try{c.close();}catch(SQLException close){sql.addSuppressed(close);}throw new CommitUncertain(sql);}
   try{c.rollback();}catch(SQLException rollback){reset=false;failure.addSuppressed(rollback);try{c.close();}catch(SQLException close){failure.addSuppressed(close);}}throw failure;
  }finally{if(reset)try{c.setAutoCommit(true);}catch(SQLException cleanup){if(primary!=null)primary.addSuppressed(cleanup);else if(committing)throw new CommitUncertain(cleanup);else throw cleanup;}}
 }
}
