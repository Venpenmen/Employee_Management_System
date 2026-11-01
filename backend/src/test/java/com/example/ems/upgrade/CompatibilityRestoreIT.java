package com.example.ems.upgrade;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Real isolated SQL coverage for the preview engine; not an HTTP/UI acceptance claim. */
public final class CompatibilityRestoreIT {
 static int checks;static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
 static Path job(Path work,String name,Path base,Path extension,Path proof)throws Exception{Path job=work.resolve(name);Files.createDirectory(job);Files.copy(base,job.resolve("input.emsbk"));if(extension!=null)Files.copy(extension,job.resolve("extensions.emsbk"));if(proof!=null)Files.copy(proof,job.resolve("return-proof.emsbk"));return job;}
 static JsonNode result(Path job)throws Exception{return Snapshot.JSON.readTree(job.resolve("result.json").toFile());}
 static String targetUrl(String source,JsonNode result){return source.replaceFirst("/ems_next_[^?]+","/"+result.path("target").asText());}
 public static void main(String[] args)throws Exception{
  String url=System.getenv("EMS_IT_URL"),password=System.getenv("EMS_IT_PASSWORD");Schema.safeUrl(url);Path work=Path.of(args[0]);Files.createDirectory(work);char[] key="SyntheticRestorePreview91".toCharArray();JsonNode baseline;
  try(var source=DriverManager.getConnection(url,"root",password)){
   LineageTracker.owned(source);UpgradeDatabaseIT.seed(source);try(var st=source.createStatement()){st.executeUpdate("INSERT INTO account_security(employee_id) VALUES(2)");st.executeUpdate("INSERT INTO secondary_credential(employee_id,password_hash) SELECT employee_id,password_hash FROM user_account WHERE employee_id=1");}
   source.setAutoCommit(false);
   try(var st=source.createStatement()){
    st.executeUpdate("INSERT INTO account_security(employee_id) VALUES(1)");st.executeUpdate("INSERT INTO account_active_session(employee_id,token_hash) VALUES(1,'"+"a".repeat(64)+"')");
    MaintenanceAuthorization.require(source,1,"a".repeat(64));check(true,"Maintenance accepts current normal executive in explicit transaction");
    boolean rejected=false;try{MaintenanceAuthorization.require(source,1,"b".repeat(64));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Maintenance rejects replaced session before protected processing");
    st.executeUpdate("UPDATE account_security SET frozen=1 WHERE employee_id=1");rejected=false;try{MaintenanceAuthorization.require(source,1,"a".repeat(64));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Maintenance rejects frozen executive despite matching token");
    st.executeUpdate("UPDATE account_security SET frozen=0,temporary_state='REISSUE' WHERE employee_id=1");rejected=false;try{MaintenanceAuthorization.require(source,1,"a".repeat(64));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Maintenance rejects reissue-only executive despite matching token");
   }finally{source.rollback();source.setAutoCommit(true);}
   baseline=Snapshot.JSON.valueToTree(Snapshot.write(source,work.resolve("current.zip")));CompatibilityArchive.export(source,work.resolve("bundle"),key);
  }
  BackupCrypto.encrypt(work.resolve("current.zip"),work.resolve("current.emsbk"),key);Path current=job(work,"current-job",work.resolve("current.emsbk"),null,null);CompatibilityRestore.preview(url,"root",password,current,key);
  check(result(current).path("recoveryMode").asText().equals("CURRENT"),"Current backup receives a fresh isolated preview");ConfirmationBinding.validate(current,false,false,Instant.now());
  Path base=work.resolve("bundle/base-v120.emsbk"),extensionFile=work.resolve("bundle/extensions.emsbk");Path original=job(work,"original-job",base,extensionFile,null);CompatibilityRestore.preview(url,"root",password,original,key);check(result(original).path("recoveryMode").asText().equals("ORIGINAL_PAIR"),"Exact original pair restores extensions without inventing return proof");
  Path baseOnly=job(work,"base-only-job",base,null,null);CompatibilityRestore.preview(url,"root",password,baseOnly,key);boolean denied=false;try{ConfirmationBinding.validate(baseOnly,false,false,Instant.now());}catch(IllegalArgumentException expected){denied=true;}check(denied,"Old base-only upgrade requires explicit acknowledgement");ConfirmationBinding.validate(baseOnly,true,false,Instant.now());
  String oldName="ems_next_server_v121_l"+UUID.randomUUID().toString().replace("-","").substring(0,10),oldUrl=url.replaceFirst("/ems_next_[^?]+","/"+oldName);
  try(var source=DriverManager.getConnection(url,"root",password);var st=source.createStatement()){st.execute("CREATE DATABASE "+Snapshot.q(oldName)+" CHARACTER SET utf8mb4");}
  Flyway.configure().dataSource(oldUrl,"root",password).locations("classpath:db/upgrade").target("2").cleanDisabled(true).load().migrate();BackupCrypto.decrypt(base,work.resolve("old.zip"),key);var oldManifest=Snapshot.inspect(work.resolve("old.zip"),work.resolve("old-stage"));
  try(var old=DriverManager.getConnection(oldUrl,"root",password);var historical=new LegacyCopyMain.OldSnapshot(Path.of(args[1]));var extension=CompatibilityExtensions.read(extensionFile,work.resolve("private-extension"),key)){
   historical.restore(old,oldManifest,work.resolve("old-stage"));LineageTracker.install(old,extension,work.resolve("receipt.emsbk"),key);var receipt=LineageTracker.readEncrypted(work.resolve("receipt.emsbk"),work.resolve("receipt-read"),key);
   try(var st=old.createStatement()){st.executeUpdate("UPDATE employee SET phone='13800000009' WHERE id=2");}
   LineageTracker.exportReturn(old,extension,receipt,work.resolve("return"),key);Path returned=job(work,"return-job",work.resolve("return/return-v120.emsbk"),extensionFile,work.resolve("return/return-proof.emsbk"));CompatibilityRestore.preview(url,"root",password,returned,key);
   var restored=result(returned);check(restored.path("recoveryMode").asText().equals("VERIFIED_RETURN"),"Authenticated return uses the allocated-target capability");
   try(var target=DriverManager.getConnection(targetUrl(url,restored),"root",password);var st=target.createStatement();var rows=st.executeQuery("SELECT phone FROM employee WHERE id=2")){rows.next();check(rows.getString(1).equals("13800000009"),"Preview retains actual old-version common-field change");}
   ConfirmationBinding.validate(returned,false,false,Instant.now());
   try(var st=old.createStatement()){st.executeUpdate("UPDATE employee SET identity_number='110101199001019997' WHERE id=2");}
   LineageTracker.exportReturn(old,extension,receipt,work.resolve("conflict"),key);Path conflict=job(work,"conflict-job",work.resolve("conflict/return-v120.emsbk"),extensionFile,work.resolve("conflict/return-proof.emsbk"));CompatibilityRestore.preview(url,"root",password,conflict,key);
   check(result(conflict).path("blocking").asBoolean()&&!result(conflict).has("target")&&!Files.exists(conflict.resolve("target-plan.json")),"Blocking identity conflict does not allocate or activate a database");denied=false;try{ConfirmationBinding.validate(conflict,true,true,Instant.now());}catch(IllegalArgumentException expected){denied=true;}check(denied,"Acknowledgement cannot bypass a blocking security conflict");
  }
  Path damaged=job(work,"damaged-job",base,extensionFile,null);byte[] corrupted=Files.readAllBytes(damaged.resolve("input.emsbk"));corrupted[corrupted.length-1]^=1;Files.write(damaged.resolve("input.emsbk"),corrupted);denied=false;try{CompatibilityRestore.preview(url,"root",password,damaged,key);}catch(IllegalArgumentException expected){denied=true;}check(denied&&!Files.exists(damaged.resolve("target-plan.json")),"Corrupt encrypted input fails before target creation");
  try(var source=DriverManager.getConnection(url,"root",password)){
   var after=Snapshot.JSON.valueToTree(Snapshot.write(source,work.resolve("source-after.zip")));for(String table:Schema.TABLES)check(baseline.path("tables").path(table).path("sha256").equals(after.path("tables").path(table).path("sha256")),"Source table digest unchanged "+table);
  }
  Arrays.fill(key,'\0');Snapshot.JSON.writeValue(work.resolve("restore-engine-results.json").toFile(),Map.of("passed",true,"checks",checks,"activeDatabaseSwitched",false,"scope","owned SQL preview engine; HTTP and UI pending"));
 }
}
