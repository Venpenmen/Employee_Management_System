package com.example.ems.upgrade;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.example.ems.service.SessionService;

/** Explicit main-based SQL tests. Maven compiling this file is not an acceptance pass. */
public final class LocalSecondaryRecoveryIT {
 static int checks;static String url,password,targetPassword,reviewerPassword;static LocalSecondaryRecovery.Captured captured;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
 static Connection connect()throws SQLException{return DriverManager.getConnection(url,"root",password);}
 static void sql(String text)throws Exception{try(var c=connect();var s=c.createStatement()){s.executeUpdate(text);}}
 static long number(String text)throws Exception{try(var c=connect();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getLong(1);}}
 static String text(String query)throws Exception{try(var c=connect();var s=c.createStatement();var r=s.executeQuery(query)){r.next();return r.getString(1);}}
 static void restoreCredential(String hash)throws Exception{try(var c=connect();var s=c.prepareStatement("INSERT INTO secondary_credential(employee_id,password_hash,credential_version) VALUES(1,?,1) ON DUPLICATE KEY UPDATE password_hash=?,credential_version=1")){s.setString(1,hash);s.setString(2,hash);s.executeUpdate();}}
 static void clear(Connection c,String request,String target,String targetPass,String reviewer,String reviewerPass)throws Exception{LocalSecondaryRecovery.clear(c,captured,target,targetPass,reviewer,reviewerPass,request,"a".repeat(64));}
 static void rejects(String target,String tp,String reviewer,String rp,String message)throws Exception{boolean refused=false;try(var c=connect()){try{clear(c,UUID.randomUUID().toString(),target,tp,reviewer,rp);}catch(IllegalArgumentException expected){refused=true;}check(c.getAutoCommit(),"Connection reset after refused local recovery");}check(refused,message);}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(System.getenv("EMS_IT_ROOT"));String scope=System.getenv("EMS_DEVELOPMENT_TASK_ID");if(!com.example.ems.config.IsolatedFixture.valid(root,scope))throw new IllegalArgumentException("Owned fixture required");
  url=System.getenv("EMS_IT_URL");password=System.getenv("EMS_IT_PASSWORD");targetPassword=System.getenv("EMS_TEST_PASSWORD");reviewerPassword=System.getenv("EMS_TEST_REVIEWER_PASSWORD");Schema.safeUrl(url);
  var owned=new org.springframework.core.env.StandardEnvironment();owned.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("owned-sql-fixture",Map.of("EMS_UPGRADE_ROOT",root.toString(),"EMS_UPGRADE_MODE","server")));com.example.ems.config.UpgradeIsolation.verifyInstallation(owned,"server",url);
  Path work=Path.of(args[0]);Files.createDirectory(work);var bcrypt=new BCryptPasswordEncoder();
  if(args.length>1&&args[1].equals("VerifyConsole")){
   check(number("SELECT COUNT(*) FROM secondary_credential WHERE employee_id=1")==0,"Actual console workflow clears only the intended secondary credential");
   check(number("SELECT COUNT(*) FROM security_grant WHERE actor_id=1")==0,"Actual console workflow invalidates old credential grants");check(number("SELECT COUNT(*) FROM account_active_session WHERE employee_id=1")==0,"Actual console workflow revokes the target session");
   check(text("SELECT token_hash FROM account_active_session WHERE employee_id=3").equals("b".repeat(64)),"Actual console workflow preserves independent reviewer session");
   check(bcrypt.matches(targetPassword,text("SELECT password_hash FROM user_account WHERE employee_id=1")),"Console recovery preserves target login password");check(bcrypt.matches(reviewerPassword,text("SELECT password_hash FROM user_account WHERE employee_id=3")),"Console recovery preserves reviewer password");check(number("SELECT user_type FROM employee WHERE id=1")==2&&number("SELECT user_type FROM employee WHERE id=3")==2,"Console recovery does not elevate or reduce roles");
   com.fasterxml.jackson.databind.JsonNode prior;try(var archive=new java.util.zip.ZipFile(Path.of(args[2]).toFile());var in=archive.getInputStream(archive.getEntry("manifest.json"))){prior=Snapshot.JSON.readTree(in);}
   try(var c=connect()){var now=Snapshot.JSON.valueToTree(Snapshot.write(c,work.resolve("after-console.zip")));for(String table:Schema.TABLES.subList(0,17))if(!table.equals("operation_log"))check(prior.path("tables").path(table).path("sha256").equals(now.path("tables").path(table).path("sha256")),"Console workflow preserves exact business table "+table);}
   Snapshot.JSON.writeValue(work.resolve("local-recovery-console-results.json").toFile(),Map.of("passed",true,"checks",checks,"scope","actual console workflow after separate core SQL tests; synthetic two-person approval"));return;
  }
  try(var c=connect()){
   UpgradeDatabaseIT.seed(c);
   try(var s=c.prepareStatement("UPDATE user_account SET password_hash=? WHERE employee_id=1")){s.setString(1,bcrypt.encode(targetPassword));s.executeUpdate();}
   try(var s=c.createStatement()){s.executeUpdate("INSERT INTO employee(id,employee_no,name,identity_number,birth_date,phone,user_type,hire_date,employment_status) VALUES(3,'260003','虚构独立复核高层','110101199001019995','1990-01-01','13800000005',2,'2020-01-01',1)");}
   try(var s=c.prepareStatement("INSERT INTO user_account(employee_id,password_hash) VALUES(3,?)")){s.setString(1,bcrypt.encode(reviewerPassword));s.executeUpdate();}
   try(var s=c.createStatement()){s.executeUpdate("INSERT INTO account_security(employee_id) VALUES(1),(3)");}
   String secondary=bcrypt.encode("SyntheticForgottenSecondary91");restoreCredential(secondary);
   captured=new LocalSecondaryRecovery.Captured(1,text("SELECT password_hash FROM user_account WHERE employee_id=1"),secondary,1,SessionService.digest("110101199001019999"));
  }
  sql("INSERT INTO account_active_session(employee_id,token_hash) VALUES(1,'"+"a".repeat(64)+"'),(3,'"+"b".repeat(64)+"')");
  sql("INSERT INTO security_grant(token_hash,employee_id,actor_id,target_id,action,session_hash,credential_version,expires_at) VALUES('"+"c".repeat(64)+"',1,1,2,'RESET','"+"a".repeat(64)+"',1,DATE_ADD(NOW(),INTERVAL 2 MINUTE))");
  com.fasterxml.jackson.databind.JsonNode before;try(var c=connect()){before=Snapshot.JSON.valueToTree(Snapshot.write(c,work.resolve("before.zip")));}
  rejects("260001",targetPassword,"260001",targetPassword,"Self-approval cannot recover a secondary credential");
  rejects("260001","WrongTargetPassword9","260003",reviewerPassword,"Wrong target primary password rejected");
  rejects("260001",targetPassword,"260003","WrongReviewerPassword9","Wrong independent reviewer proof rejected");
  rejects("260001",targetPassword,"260002","SyntheticPassword123","Nonexecutive cannot approve executive recovery");
  sql("UPDATE account_security SET frozen=1 WHERE employee_id=1");try{rejects("260001",targetPassword,"260003",reviewerPassword,"Frozen target cannot bypass security policy");}finally{sql("UPDATE account_security SET frozen=0 WHERE employee_id=1");}
  sql("UPDATE account_security SET temporary_state='REISSUE' WHERE employee_id=3");try{rejects("260001",targetPassword,"260003",reviewerPassword,"Restricted reviewer cannot approve recovery");}finally{sql("UPDATE account_security SET temporary_state='NORMAL' WHERE employee_id=3");}
  String originalUpdate=text("SELECT DATE_FORMAT(update_time,'%Y-%m-%d %H:%i:%s') FROM employee WHERE id=1");
  sql("UPDATE employee SET identity_number='110101199001019994' WHERE id=1");try{rejects("260001",targetPassword,"260003",reviewerPassword,"Identity change invalidates the verified recovery baseline");}finally{
   // Undo the synthetic fault including its automatic timestamp, so the later
   // full business digest checks measure recovery rather than test setup DML.
   try(var c=connect();var s=c.prepareStatement("UPDATE employee SET identity_number='110101199001019999',update_time=? WHERE id=1")){s.setTimestamp(1,Timestamp.valueOf(originalUpdate));s.executeUpdate();}
  }
  sql("UPDATE secondary_credential SET credential_version=2 WHERE employee_id=1");try{rejects("260001",targetPassword,"260003",reviewerPassword,"Changed credential version cannot be cleared using an old baseline");}finally{sql("UPDATE secondary_credential SET credential_version=1 WHERE employee_id=1");}
  sql("UPDATE user_account SET locked_until=DATE_ADD(NOW(),INTERVAL 15 MINUTE) WHERE employee_id=1");try{rejects("260001",targetPassword,"260003",reviewerPassword,"Independent login lock is not cleared by recovery");}finally{sql("UPDATE user_account SET locked_until=NULL WHERE employee_id=1");}
  sql("CREATE TRIGGER fail_recovery_audit BEFORE INSERT ON operation_log FOR EACH ROW BEGIN IF NEW.operation_type='SECONDARY_RECOVERY' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic audit interruption'; END IF; END");
  boolean refused=false;try(var c=connect()){try{clear(c,UUID.randomUUID().toString(),"260001",targetPassword,"260003",reviewerPassword);}catch(SQLException expected){refused=true;}check(c.getAutoCommit(),"Audit failure resets the dedicated connection");}finally{sql("DROP TRIGGER fail_recovery_audit");}
  check(refused,"Audit failure aborts recovery transaction");check(number("SELECT COUNT(*) FROM secondary_credential WHERE employee_id=1")==1,"Rollback retains the target secondary credential");check(number("SELECT COUNT(*) FROM security_grant WHERE actor_id=1")==1,"Rollback retains original operation grants");check(number("SELECT COUNT(*) FROM account_active_session WHERE employee_id=1")==1,"Rollback retains original target session");
  String uncertainId=UUID.randomUUID().toString();
  try(var raw=connect()){
   Connection ackLoss=(Connection)Proxy.newProxyInstance(LocalSecondaryRecoveryIT.class.getClassLoader(),new Class<?>[]{Connection.class},(object,method,arguments)->{if(method.getName().equals("commit")){raw.commit();throw new SQLException("Synthetic acknowledgement loss","08006");}try{return method.invoke(raw,arguments);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}});
   boolean unknown=false;try{clear(ackLoss,uncertainId,"260001",targetPassword,"260003",reviewerPassword);}catch(LocalSecondaryRecovery.CommitUncertain expected){unknown=true;}check(unknown&&raw.isClosed(),"Lost commit acknowledgement is explicit and closes the connection without auto-retry");
  }
  check(number("SELECT COUNT(*) FROM operation_log WHERE operation_type='SECONDARY_RECOVERY' AND summary LIKE 'request="+uncertainId+";%' ")==1,"Separate readback proves committed audit after injected acknowledgement loss");
  check(number("SELECT COUNT(*) FROM secondary_credential WHERE employee_id=1")==0,"Acknowledgement loss never pretends the committed deletion rolled back");
  restoreCredential(captured.secondaryHash());sql("INSERT INTO account_active_session(employee_id,token_hash) VALUES(1,'"+"a".repeat(64)+"')");
  sql("INSERT INTO security_grant(token_hash,employee_id,actor_id,target_id,action,session_hash,credential_version,expires_at) VALUES('"+"c".repeat(64)+"',1,1,2,'RESET','"+"a".repeat(64)+"',1,DATE_ADD(NOW(),INTERVAL 2 MINUTE))");
  String request=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try{
   Callable<Boolean> operation=()->{start.await(10,TimeUnit.SECONDS);try(var c=connect()){try{clear(c,request,"260001",targetPassword,"260003",reviewerPassword);return true;}catch(IllegalArgumentException lost){return false;}}};var one=pool.submit(operation);var two=pool.submit(operation);start.countDown();check((one.get(20,TimeUnit.SECONDS)?1:0)+(two.get(20,TimeUnit.SECONDS)?1:0)==1,"Concurrent approvals serialize and recover exactly once");
  }finally{pool.shutdownNow();}
  check(number("SELECT COUNT(*) FROM secondary_credential WHERE employee_id=1")==0,"Target may set a new secondary credential only after recovery");check(number("SELECT COUNT(*) FROM security_grant WHERE actor_id=1")==0,"Only target credential-version grants are revoked");check(number("SELECT COUNT(*) FROM account_active_session WHERE employee_id=1")==0,"Target session is conditionally revoked");check(text("SELECT token_hash FROM account_active_session WHERE employee_id=3").equals("b".repeat(64)),"Independent reviewer session is untouched");
  String replacement=bcrypt.encode("SyntheticNewSecondary92");restoreCredential(replacement);sql("INSERT INTO account_active_session(employee_id,token_hash) VALUES(1,'"+"d".repeat(64)+"')");
  sql("INSERT INTO security_grant(token_hash,employee_id,actor_id,target_id,action,session_hash,credential_version,expires_at) VALUES('"+"e".repeat(64)+"',1,1,2,'RESET','"+"d".repeat(64)+"',1,DATE_ADD(NOW(),INTERVAL 2 MINUTE))");
  boolean replay=false;try(var c=connect()){try{clear(c,request,"260001",targetPassword,"260003",reviewerPassword);}catch(IllegalArgumentException expected){replay=true;}}check(replay,"Replayed recovery cannot clear a newly set secondary password");check(text("SELECT password_hash FROM secondary_credential WHERE employee_id=1").equals(replacement)&&text("SELECT token_hash FROM account_active_session WHERE employee_id=1").equals("d".repeat(64)),"Replay preserves the new secondary credential and new session");
  try(var c=connect()){var after=Snapshot.JSON.valueToTree(Snapshot.write(c,work.resolve("after.zip")));for(String table:Schema.TABLES.subList(0,17))if(!table.equals("operation_log"))check(before.path("tables").path(table).path("sha256").equals(after.path("tables").path(table).path("sha256")),"Business data digest unchanged "+table);}
  Snapshot.JSON.writeValue(work.resolve("local-recovery-database-results.json").toFile(),Map.of("passed",true,"checks",checks,"scope","owned SQL primitive, concurrency and commit-ack fault; console workflow separately required"));
 }
}
