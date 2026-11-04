package com.example.ems.upgrade;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Real MySQL trigger/transaction test. The legacy importer is read from an immutable old binary. */
public final class LineageTrackerIT {
 static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
 static String generation(com.fasterxml.jackson.databind.JsonNode heads,String table,String id){for(var row:heads)if(row.path("table").asText().equals(table)&&row.path("id").asText().equals(id))return row.path("generation").asText();throw new AssertionError("Missing test generation");}
 public static void main(String[] args)throws Exception{
  String url=System.getenv("EMS_IT_URL"),password=System.getenv("EMS_IT_PASSWORD");Schema.safeUrl(url);Path work=Path.of(args[0]);Files.createDirectory(work);
  char[] archivePassword="SyntheticLineageArchivePassword91".toCharArray();
  try(var interrupted=DriverManager.getConnection(url+"&socketTimeout=1000","root",password)){
   LineageTracker.owned(interrupted);boolean timedOut=false;
   try(var st=interrupted.createStatement()){st.executeQuery("SELECT SLEEP(3)");}catch(SQLException expected){timedOut="08S01".equals(expected.getSQLState())||expected instanceof java.sql.SQLTimeoutException;}
   check(timedOut,"Real socket timeout interrupts only the owned test connection");
   boolean refused=false;try{LineageTracker.owned(interrupted);}catch(SQLException expected){refused=true;}check(refused,"Interrupted connection cannot pass maintenance ownership checks");
  }
  try(var connection=DriverManager.getConnection(url,"root",password)){
   LineageTracker.owned(connection);check(true,"Maintenance connection matches live task-owned MySQL process");
   String originalCatalog=connection.getCatalog(),foreignMode="ems_next_dev_v121_b"+UUID.randomUUID().toString().replace("-","").substring(0,10);
   try(var st=connection.createStatement()){st.execute("CREATE DATABASE "+Snapshot.q(foreignMode)+" CHARACTER SET utf8mb4");}
   connection.setCatalog(foreignMode);boolean modeRejected=false;
   try{LineageTracker.owned(connection);}catch(IllegalArgumentException expected){modeRejected="Connection is not bound to the owned database process".equals(expected.getMessage());}
   connection.setCatalog(originalCatalog);check(modeRejected,"Mode mismatch rejected even inside the same owned MySQL instance");
   UpgradeDatabaseIT.seed(connection);
   try(var st=connection.createStatement()){
    st.executeUpdate("INSERT INTO account_security(employee_id) VALUES(2)");
    st.executeUpdate("INSERT INTO secondary_credential(employee_id,password_hash) SELECT employee_id,password_hash FROM user_account WHERE employee_id=1");
    st.executeUpdate("INSERT INTO password_change_request(employee_id,active_employee_id,reason,status,expires_at) VALUES(2,2,'Synthetic pending request','PENDING',DATE_ADD(NOW(),INTERVAL 1 DAY))");
    st.executeUpdate("INSERT INTO login_security_event(employee_id,event_type,summary) VALUES(2,'SYNTHETIC','Synthetic audit')");
    st.executeUpdate("INSERT INTO security_notification(employee_id,event_type,subject_employee_id,summary,read_at) VALUES(1,'SYNTHETIC',2,'Synthetic notice',NOW(6))");
   }
   CompatibilityArchive.export(connection,work.resolve("bundle"),archivePassword);
  }
  String oldName="ems_next_server_v121_l"+UUID.randomUUID().toString().replace("-","").substring(0,12),oldUrl=url.replaceFirst("/ems_next_[^?]+","/"+oldName);
  try(var connection=DriverManager.getConnection(url,"root",password);var st=connection.createStatement()){st.execute("CREATE DATABASE "+Snapshot.q(oldName)+" CHARACTER SET utf8mb4");}
  Flyway.configure().dataSource(oldUrl,"root",password).locations("classpath:db/upgrade").target("2").cleanDisabled(true).load().migrate();
  Path base=work.resolve("bundle/base-v120.emsbk");BackupCrypto.decrypt(base,work.resolve("base.zip"),archivePassword);var manifest=Snapshot.inspect(work.resolve("base.zip"),work.resolve("base-stage"));
  try(var connection=DriverManager.getConnection(oldUrl,"root",password);var old=new Rc2MigrationIT.Historical(Path.of(args[1]))){old.restoreV2(connection,manifest,work.resolve("base-stage"));check(true,"Actual preserved v1.2.0 Snapshot imports compatibility base");}
  try(var extension=CompatibilityExtensions.read(work.resolve("bundle/extensions.emsbk"),work.resolve("extension-private"),archivePassword);var connection=DriverManager.getConnection(oldUrl,"root",password)){
   extension.requireOriginalBase(base);Path receiptFile=work.resolve("receipt.emsbk");LineageTracker.install(connection,extension,receiptFile,archivePassword);
   var receipt=LineageTracker.readEncrypted(receiptFile,work.resolve("receipt-read.json"),archivePassword);
   var initial=LineageTracker.validateReceipt(connection,extension,receipt);String tracking=initial.path("trackingSchema").asText();
   check(Schema.names(connection).size()==19,"Legacy business schema retains exactly 17 persistent plus session/history tables");check(LineageTracker.triggers(connection).size()==51,"All 17 tables have transactional INSERT UPDATE DELETE tracking");
   String originalSkill=generation(initial.path("baseline"),"employee_skill","1");
   String user="ems_test_"+UUID.randomUUID().toString().replace("-","").substring(0,10),appPassword=UUID.randomUUID().toString();
   try(var st=connection.createStatement()){st.execute("CREATE USER '"+user+"'@'127.0.0.1' IDENTIFIED BY '"+appPassword+"'");st.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+Snapshot.q(oldName)+".* TO '"+user+"'@'127.0.0.1'");}
   try(var app=DriverManager.getConnection(oldUrl,user,appPassword);var st=app.createStatement()){
    st.executeUpdate("UPDATE employee SET phone='13800000009' WHERE id=2");
    check(generation(initial.path("baseline"),"employee","2").equals(generation(LineageTracker.heads(connection,tracking),"employee","2")),"Normal update preserves employee generation");
    app.setAutoCommit(false);st.executeUpdate("UPDATE department SET description='Uncommitted synthetic change' WHERE id=1");app.rollback();app.setAutoCommit(true);
    try(var query=connection.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM "+Snapshot.q(tracking)+".events WHERE table_name='department'")){rows.next();check(rows.getInt(1)==0,"Rolled-back business update leaves no lineage event");}
    st.executeUpdate("DELETE FROM employee_skill WHERE id=1");
    var deleted=LineageTracker.heads(connection,tracking);boolean tombstone=false;for(var row:deleted)if(row.path("table").asText().equals("employee_skill")&&row.path("id").asText().equals("1"))tombstone=!row.path("alive").asBoolean();check(tombstone,"Delete retains a tombstone");
    st.executeUpdate("INSERT INTO employee_skill(id,employee_id,name,proficiency) VALUES(1,2,'Synthetic replacement','熟悉')");
    check(!originalSkill.equals(generation(LineageTracker.heads(connection,tracking),"employee_skill","1")),"Primary-key reuse receives a new generation");
    boolean denied=false;try{st.executeUpdate("UPDATE "+Snapshot.q(tracking)+".heads SET alive=0");}catch(SQLException expected){denied=true;}check(denied,"Old application cannot modify tracking schema");
    denied=false;try{st.execute("DROP TRIGGER "+Snapshot.q(LineageTracker.triggerName("employee",'u')));}catch(SQLException expected){denied=true;}check(denied,"Old application cannot remove tracking triggers");
   }
   LineageTracker.exportReturn(connection,extension,receipt,work.resolve("return"),archivePassword);
   var proof=LineageTracker.readEncrypted(work.resolve("return/return-proof.emsbk"),work.resolve("proof-read.json"),archivePassword);
   var verified=LineageTracker.verifyReturn(extension,work.resolve("return/return-v120.emsbk"),proof);check(true,"Return proof binds original receipt and changed return ciphertext");
   BackupCrypto.decrypt(work.resolve("return/return-v120.emsbk"),work.resolve("return.zip"),archivePassword);
   var returned=Snapshot.inspect(work.resolve("return.zip"),work.resolve("return-stage"));
   var plan=ExtensionReconciler.preview(extension,work.resolve("return-stage/tables"),returned,verified,"a".repeat(64));
   check(!plan.blocked()&&plan.issues().isEmpty(),"Legal common-field edit preserves all applicable independent extensions");
   String target="ems_next_server_v121_r"+UUID.randomUUID().toString().replace("-","").substring(0,12),targetUrl=url.replaceFirst("/ems_next_[^?]+","/"+target);
   try(var st=connection.createStatement()){st.execute("CREATE DATABASE "+Snapshot.q(target)+" CHARACTER SET utf8mb4");}Schema.migrate(targetUrl,"root",password);
   try(var restored=DriverManager.getConnection(targetUrl,"root",password)){
    Snapshot.restore(restored,returned,work.resolve("return-stage"));
    try(var st=restored.createStatement()){st.execute("CREATE TRIGGER ems_test_restore_failure BEFORE INSERT ON security_notification FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic rollback fault'");}
    boolean rolledBack=false;try{ExtensionReconciler.applyIsolated(restored,plan,plan.previewSha256(),false);}catch(SQLException expected){rolledBack="45000".equals(expected.getSQLState());}
    check(rolledBack,"Injected last-table extension write failure is reported");
    try(var st=restored.createStatement();var row=st.executeQuery("SELECT (SELECT COUNT(*) FROM account_security)+(SELECT COUNT(*) FROM secondary_credential)+(SELECT COUNT(*) FROM password_change_request)+(SELECT COUNT(*) FROM login_security_event)+(SELECT COUNT(*) FROM security_notification)")){row.next();check(row.getInt(1)==0&&restored.getAutoCommit(),"Failed extension transaction rolls back all preceding writes and resets connection");}
    try(var st=restored.createStatement()){st.execute("DROP TRIGGER ems_test_restore_failure");}
    check(ExtensionReconciler.applyIsolated(restored,plan,plan.previewSha256(),false)==5,"Five persistent security extensions restored transactionally into isolated generation");
    try(var st=restored.createStatement();var row=st.executeQuery("SELECT phone FROM employee WHERE id=2")){row.next();check(row.getString(1).equals("13800000009"),"Legal old-version phone modification retained");}
    try(var st=restored.createStatement();var row=st.executeQuery("SELECT status,active_employee_id FROM password_change_request")){row.next();check(row.getString(1).equals("EXPIRED")&&row.getObject(2)==null,"Incomplete reset eligibility expires on roundtrip");}
    try(var st=restored.createStatement();var row=st.executeQuery("SELECT (SELECT COUNT(*) FROM account_active_session)+(SELECT COUNT(*) FROM security_grant)")){row.next();check(row.getInt(1)==0,"Sessions and one-time authorizations do not revive");}
   }
   // The decision is bound to actual private restore values, not merely to a public summary.
   plan.restore().get("secondary_credential").get(0).put("password_hash","tampered after preview");boolean changedRejected=false;
   try{ExtensionReconciler.applyIsolated(connection,plan,plan.previewSha256(),false);}catch(IllegalArgumentException expected){changedRejected=expected.getMessage().startsWith("Preview is blocked");}check(changedRejected,"Changed preview values invalidate confirmation before database DML");
   try(var st=connection.createStatement()){st.executeUpdate("UPDATE user_account SET password_hash='SyntheticChangedHashNeverAnOldCredential' WHERE employee_id=1");}
   LineageTracker.exportReturn(connection,extension,receipt,work.resolve("changed-password"),archivePassword);
   var changedProof=LineageTracker.readEncrypted(work.resolve("changed-password/return-proof.emsbk"),work.resolve("changed-proof.json"),archivePassword);
   var changedVerified=LineageTracker.verifyReturn(extension,work.resolve("changed-password/return-v120.emsbk"),changedProof);
   BackupCrypto.decrypt(work.resolve("changed-password/return-v120.emsbk"),work.resolve("changed.zip"),archivePassword);var changedManifest=Snapshot.inspect(work.resolve("changed.zip"),work.resolve("changed-stage"));
   var changedPlan=ExtensionReconciler.preview(extension,work.resolve("changed-stage/tables"),changedManifest,changedVerified,"b".repeat(64));
   check(changedPlan.credentialsToReset()==1&&changedPlan.restore().get("secondary_credential").isEmpty(),"Old-version primary-password change prevents secondary-credential revival");
   try(var st=connection.createStatement()){st.executeUpdate("UPDATE employee SET identity_number='110101199001019997' WHERE id=2");}
   LineageTracker.exportReturn(connection,extension,receipt,work.resolve("changed-identity"),archivePassword);
   var identityProof=LineageTracker.readEncrypted(work.resolve("changed-identity/return-proof.emsbk"),work.resolve("identity-proof.json"),archivePassword);
   var identityVerified=LineageTracker.verifyReturn(extension,work.resolve("changed-identity/return-v120.emsbk"),identityProof);
   BackupCrypto.decrypt(work.resolve("changed-identity/return-v120.emsbk"),work.resolve("identity.zip"),archivePassword);var identityManifest=Snapshot.inspect(work.resolve("identity.zip"),work.resolve("identity-stage"));
   var identityPlan=ExtensionReconciler.preview(extension,work.resolve("identity-stage/tables"),identityManifest,identityVerified,"c".repeat(64));
   check(identityPlan.blocked()&&identityPlan.issues().stream().anyMatch(i->i.code().equals("IDENTITY_CHANGED")),"Changed identity blocks unsafe security extension association");
   try(var st=connection.createStatement()){
    st.executeUpdate("UPDATE employee SET identity_number='110101199001019998' WHERE id=2");
    st.executeUpdate("DELETE FROM user_account WHERE employee_id=1");
    st.executeUpdate("INSERT INTO user_account(id,employee_id,password_hash) VALUES(1,1,'SyntheticReplacementHash')");
   }
   LineageTracker.exportReturn(connection,extension,receipt,work.resolve("replaced-account"),archivePassword);
   var replacedProof=LineageTracker.readEncrypted(work.resolve("replaced-account/return-proof.emsbk"),work.resolve("replaced-proof.json"),archivePassword);
   var replacedVerified=LineageTracker.verifyReturn(extension,work.resolve("replaced-account/return-v120.emsbk"),replacedProof);
   BackupCrypto.decrypt(work.resolve("replaced-account/return-v120.emsbk"),work.resolve("replaced.zip"),archivePassword);var replacedManifest=Snapshot.inspect(work.resolve("replaced.zip"),work.resolve("replaced-stage"));
   var replacedPlan=ExtensionReconciler.preview(extension,work.resolve("replaced-stage/tables"),replacedManifest,replacedVerified,"d".repeat(64));
   check(replacedPlan.blocked()&&replacedPlan.issues().stream().anyMatch(i->i.code().equals("ACCOUNT_REPLACED")&&i.blocking()),"Account deletion and same-key recreation blocks old security association");
   try(var st=connection.createStatement()){
    st.executeUpdate("INSERT INTO employee(id,employee_no,name,identity_number,birth_date,user_type,hire_date,employment_status) VALUES(99,'269999','SyntheticCascade','110101199001019990','1990-01-01',0,'2020-01-01',1)");
    st.executeUpdate("INSERT INTO employee_certificate(id,employee_id,name,issuer,obtained_date) VALUES(99,99,'Synthetic cascade certificate','Synthetic issuer','2020-01-01')");
    st.execute("ALTER TABLE employee_certificate DROP FOREIGN KEY fk_certificate_employee, ADD CONSTRAINT fk_certificate_p1_cascade FOREIGN KEY(employee_id) REFERENCES employee(id) ON DELETE CASCADE");
    st.executeUpdate("DELETE FROM employee WHERE id=99");
   }
   boolean stale=false,normalized=false;
   for(var row:LineageTracker.heads(connection,tracking))if(row.path("table").asText().equals("employee_certificate")&&row.path("id").asText().equals("99"))stale=row.path("alive").asBoolean();
   for(var row:LineageTracker.snapshotHeads(connection,tracking))if(row.path("table").asText().equals("employee_certificate")&&row.path("id").asText().equals("99"))normalized=!row.path("alive").asBoolean();
   check(stale&&normalized,"Actual MySQL cascade skips child trigger but snapshot marks absent child deleted");
   boolean rejected=false;try{CompatibilityCatalog.requireProfile(connection,"canonical-120");}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Unregistered cascade schema cannot bypass conversion catalog");
   try(var st=connection.createStatement()){st.execute("ALTER TABLE employee_certificate DROP FOREIGN KEY fk_certificate_p1_cascade, ADD CONSTRAINT fk_certificate_employee FOREIGN KEY(employee_id) REFERENCES employee(id)");st.executeUpdate("DELETE FROM "+Snapshot.q(tracking)+".heads WHERE table_name='employee_skill' AND row_id=1");}
   rejected=false;try{LineageTracker.exportReturn(connection,extension,receipt,work.resolve("missing-head"),archivePassword);}catch(IllegalArgumentException expected){rejected="Tracking lacks a live business identity".equals(expected.getMessage());}check(rejected&&!Files.exists(work.resolve("missing-head/return-proof.emsbk")),"Missing live ledger row blocks proof signing even without an associated security extension");
   var tampered=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)tampered.path("body")).put("checkpoint","99999");
   rejected=false;
   try{LineageTracker.verifyReturn(extension,work.resolve("return/return-v120.emsbk"),tampered);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Tampered proof is rejected");
   rejected=false;try{LineageTracker.verifyReturn(extension,base,proof);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Proof from another backup cannot be replayed as a pair");
   try(var st=connection.createStatement()){st.executeUpdate("UPDATE "+Snapshot.q(tracking)+".metadata SET epoch=UUID() WHERE id=1");}
   rejected=false;try{LineageTracker.validateReceipt(connection,extension,receipt);}catch(IllegalArgumentException expected){rejected="Tracking generation was replaced".equals(expected.getMessage());}check(rejected,"Reinitialized tracking epoch invalidates the original receipt");
   try(var st=connection.prepareStatement("UPDATE "+Snapshot.q(tracking)+".metadata SET epoch=? WHERE id=1")){st.setString(1,receipt.path("body").path("epoch").asText());st.executeUpdate();}
   try(var st=connection.createStatement()){st.execute("DROP TRIGGER "+Snapshot.q(LineageTracker.triggerName("employee",'u')));}
   rejected=false;try{LineageTracker.validateReceipt(connection,extension,receipt);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Tracking interruption blocks trusted return");
  }finally{Arrays.fill(archivePassword,'\0');}
  Snapshot.JSON.writeValue(work.resolve("lineage-results.json").toFile(),Map.of("passed",true,"checks",checks,"oldBinaryImport",true,"fullOldRuntimeHttpAcceptance",false,"scope","isolated fixture only"));
 }
}
