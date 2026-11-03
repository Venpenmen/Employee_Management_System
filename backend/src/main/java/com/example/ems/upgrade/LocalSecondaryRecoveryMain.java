package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.ems.config.UpgradeIsolation;
import org.springframework.core.env.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Console-only entry; every invocation requires fresh two-person proof and a real restored backup. */
public final class LocalSecondaryRecoveryMain {
 private LocalSecondaryRecoveryMain(){}
 static JsonNode find(Path table,String field,String value)throws Exception{
  JsonNode found=null;
  try(var parser=Snapshot.JSON.getFactory().createParser(table.toFile())){
   if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw new IllegalArgumentException("Invalid recovery baseline");
   while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){JsonNode row=Snapshot.JSON.readTree(parser);if(row==null)throw new IllegalArgumentException("Invalid recovery baseline");if(value.equals(row.path(field).asText())){if(found!=null)throw new IllegalArgumentException("Ambiguous recovery identity");found=row;}}
  }if(found==null)throw new IllegalArgumentException("Recovery identity or credential unavailable");return found;
 }
 static void plain(Path path)throws Exception{for(Path p=path;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){var a=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(a.isSymbolicLink()||a.isOther())throw new IllegalArgumentException("Recovery path must be local and unlinked");}}
 static void stopped(Path root,String mode)throws Exception{var state=Snapshot.JSON.readTree(root.resolve(("true".equals(System.getenv("EMS_RC2_ENABLED"))?"data/complete-rc2/":"data/complete/")+mode+"/instance-upgrade.json").toFile());if(!state.path("app").isMissingNode()&&!state.path("app").isNull())throw new IllegalArgumentException("Application must be stopped using its own complete-candidate entry point");}
 static void execute()throws Exception{
  String url=MaintenanceMain.env("EMS_MAINT_URL"),mode=MaintenanceMain.env("EMS_UPGRADE_MODE");if(!Set.of("server","portable","dev").contains(mode))throw new IllegalArgumentException("Invalid recovery mode");Schema.safeUrl(url);
  Path root=Path.of(MaintenanceMain.env("EMS_MAINT_ROOT")).toAbsolutePath().normalize(),job=Path.of(MaintenanceMain.env("EMS_MAINT_JOB")).toAbsolutePath().normalize();plain(root);plain(job);
  if(!root.equals(root.toRealPath())||!job.equals(job.toRealPath())||!job.getParent().equals(root.resolve(("true".equals(System.getenv("EMS_RC2_ENABLED"))?"data/complete-rc2/":"data/complete/")+mode+"/secondary-recovery"))||!job.getFileName().toString().matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Recovery context not owned");
  boolean rc2="true".equals(System.getenv("EMS_RC2_ENABLED"));var environment=new StandardEnvironment();environment.getPropertySources().addFirst(new MapPropertySource("recovery-build",Map.of("ems.rc2.enabled",rc2,"EMS_CONTROL_DIR",root.resolve((rc2?"data/complete-rc2/":"data/complete/")+mode).toString())));environment.setActiveProfiles(mode.equals("dev")?new String[0]:new String[]{mode});
  environment.getPropertySources().addFirst(new MapPropertySource("local-recovery",Map.of("EMS_UPGRADE_ROOT",root.toString(),"EMS_UPGRADE_MODE",mode,"spring.datasource.url",url,"server.port",8482,"server.servlet.session.cookie.name","EMS121N_COMPLETE_"+mode.toUpperCase(Locale.ROOT))));
  UpgradeIsolation.verifyInstallation(environment,mode,url);com.example.ems.config.ApplicationLease.hold(root,mode,rc2);stopped(root,mode);
  String reference=MaintenanceMain.env("EMS_RECOVERY_APPROVAL_REFERENCE");JsonNode request=Snapshot.JSON.readTree(job.resolve("request.json").toFile());
  if(!reference.matches("[A-Z0-9_-]{8,64}")||!reference.equals(request.path("approvalReference").asText())||!job.getFileName().toString().equals(request.path("requestId").asText())||!mode.equals(request.path("mode").asText())||!MaintenanceMain.env("EMS_RECOVERY_TARGET").equals(request.path("targetEmployeeNo").asText())||java.time.Instant.parse(request.path("createdAt").asText()).isBefore(java.time.Instant.now().minusSeconds(1800)))throw new IllegalArgumentException("Local review request expired or mismatched");
  Path backup=job.resolve("backup.emsbk");String sha=BackupCrypto.sha256(backup);if(!sha.equals(MaintenanceMain.env("EMS_RECOVERY_BACKUP_SHA")))throw new IllegalArgumentException("Recovery point changed");
  JsonNode verification=Snapshot.JSON.readTree(job.resolve("verification/result.json").toFile());String target=verification.path("target").asText();
  if(!target.matches("ems_next_"+mode+"_v121_g[a-f0-9]{12}")||url.split("\\?",2)[0].endsWith("/"+target))throw new IllegalArgumentException("Real isolated backup verification required");
  Path work=job.resolve("inspection-"+UUID.randomUUID());Files.createDirectory(work);char[] password=MaintenanceMain.env("EMS_BACKUP_PASSWORD").toCharArray();Exception primary=null;boolean applied=false;
  try{
   BackupCrypto.decrypt(backup,work.resolve("snapshot.zip"),password);JsonNode manifest=Snapshot.inspect(work.resolve("snapshot.zip"),work.resolve("tables-stage"));if(!"canonical-121".equals(BackupVersionCatalog.identify(manifest).id()))throw new IllegalArgumentException("Current canonical backup required");
   Path tables=work.resolve("tables-stage/tables");JsonNode employee=find(tables.resolve("employee.json"),"employee_no",MaintenanceMain.env("EMS_RECOVERY_TARGET"));String id=employee.path("id").asText();JsonNode account=find(tables.resolve("user_account.json"),"employee_id",id),credential=find(tables.resolve("secondary_credential.json"),"employee_id",id);
   var captured=new LocalSecondaryRecovery.Captured(employee.path("id").asLong(),account.path("password_hash").asText(),credential.path("password_hash").asText(),credential.path("credential_version").asLong(),com.example.ems.service.SessionService.digest(employee.path("identity_number").asText()));
   String user=MaintenanceMain.env("EMS_MAINT_USER"),dbPassword=MaintenanceMain.env("EMS_MAINT_PASSWORD");
   try(var restored=DriverManager.getConnection(MaintenanceMain.url(target),user,dbPassword)){
    if(!"canonical-121".equals(CompatibilityCatalog.identify(restored).id()))throw new IllegalArgumentException("Restored point has invalid structure");
    try(var s=restored.prepareStatement("SELECT a.password_hash,s.password_hash,s.credential_version FROM user_account a JOIN secondary_credential s ON s.employee_id=a.employee_id WHERE a.employee_id=?")){s.setLong(1,captured.employeeId());try(var r=s.executeQuery()){if(!r.next()||!captured.primaryHash().equals(r.getString(1))||!captured.secondaryHash().equals(r.getString(2))||captured.credentialVersion()!=r.getLong(3))throw new IllegalArgumentException("Restored recovery point does not match encrypted backup");}}
   }
   stopped(root,mode);if(!sha.equals(BackupCrypto.sha256(backup)))throw new IllegalArgumentException("Recovery point changed");
   try(var source=DriverManager.getConnection(url,user,dbPassword)){LocalSecondaryRecovery.clear(source,captured,MaintenanceMain.env("EMS_RECOVERY_TARGET"),MaintenanceMain.env("EMS_RECOVERY_TARGET_PASSWORD"),MaintenanceMain.env("EMS_RECOVERY_REVIEWER"),MaintenanceMain.env("EMS_RECOVERY_REVIEWER_PASSWORD"),job.getFileName().toString(),sha);}
   applied=true;
   try{Files.write(job.resolve("applied.json"),Snapshot.JSON.writeValueAsBytes(Map.of("applied",true,"requestId",job.getFileName().toString(),"backupSha256",sha,"loginPasswordChanged",false,"rolesChanged",false,"approval","two-distinct-active-executives")),StandardOpenOption.CREATE_NEW);}catch(Exception receipt){throw new LocalSecondaryRecovery.CommitUncertain(new SQLException("Committed recovery; local receipt persistence failed",receipt));}
  }catch(Exception failure){primary=failure;throw failure;}
  finally{Arrays.fill(password,'\0');try{MaintenanceMain.removeStaging(work);}catch(Exception cleanup){if(primary!=null)primary.addSuppressed(cleanup);else if(applied)throw new LocalSecondaryRecovery.CommitUncertain(new SQLException("Committed recovery cleanup requires verification",cleanup));else throw cleanup;}}
 }
 public static void main(String[] args){try{execute();System.out.println("Local recovery committed; re-login and set a new secondary password");}catch(LocalSecondaryRecovery.CommitUncertain uncertain){System.err.println("Recovery outcome requires local audit verification; do not retry or restore active data automatically");System.exit(2);}catch(Exception refused){MaintenanceMain.diagnostic("Local recovery",refused);System.exit(1);}}
}
