package com.example.ems.upgrade;
import java.sql.*;import java.nio.file.*;import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public final class MaintenanceMain {
 static String env(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing maintenance input");return v;}
 static String url(String schema){String original=env("EMS_MAINT_URL");Schema.safeUrl(original);String result=original.replaceFirst("/ems_next_[^?]+","/"+schema);Schema.safeUrl(result);return result;}
 public static void main(String[] args){
  try{execute(args);System.out.println("Maintenance completed");}
  catch(Exception e){diagnostic("Maintenance",e);System.exit(1);}
 }
 static void diagnostic(String phase,Exception error){
  JobDiagnostics.persist(error);
  String id=UUID.randomUUID().toString();try{String candidate=Path.of(env("EMS_MAINT_JOB")).getFileName().toString();if(candidate.matches("[a-f0-9-]{36}"))id=candidate;}catch(Exception ignored){}
  System.err.println(phase+" failed ["+id+"]");
  System.err.println("Diagnostic: "+JobDiagnostics.describe(id,error,"UNCHANGED"));
  Throwable cause=error;int depth=0;
  while(cause!=null&&depth++<8){
   String detail=cause.getClass().getName();
   if(cause instanceof SQLException sql)detail+=" SQLState="+sql.getSQLState()+" vendorCode="+sql.getErrorCode();
   System.err.println("Cause["+depth+"]: "+detail);
   for(StackTraceElement frame:cause.getStackTrace())if(frame.getClassName().startsWith("com.example.ems.upgrade")||frame.getClassName().startsWith("org.flywaydb"))System.err.println(" at "+frame);
   cause=cause.getCause();
  }
 }
 static void execute(String[] args)throws Exception{
  JobDiagnostics.phase("preflight");
  String command=args[0],url=env("EMS_MAINT_URL"),user=env("EMS_MAINT_USER"),pw=env("EMS_MAINT_PASSWORD");Schema.safeUrl(url);
  if(System.getenv("EMS_MAINT_ACTOR_ID")!=null){
   JobDiagnostics.phase("authorization");
   try(var c=DriverManager.getConnection(url,user,pw)){
    c.setAutoCommit(false);
    MaintenanceAuthorization.require(c,Long.parseLong(env("EMS_MAINT_ACTOR_ID")),env("EMS_MAINT_ACTOR_HASH"));c.commit();
   }
  }
  if(command.equals("verify-current"))return;
  if(command.equals("verify-confirmation")){
   Path job=Path.of(env("EMS_MAINT_JOB")).toRealPath();
   var receipt=ConfirmationBinding.validate(job,Boolean.parseBoolean(env("EMS_CONFIRM_BASE_ONLY")),Boolean.parseBoolean(env("EMS_CONFIRM_SKIP")),java.time.Instant.now());
   if(!LineageTracker.hash(receipt).equals(env("EMS_CONFIRM_RECEIPT")))throw new IllegalArgumentException("Confirmation changed");
   return;
  }
  if(command.equals("migrate")){Schema.migrate(url,user,pw);return;}
  Path job=Path.of(env("EMS_MAINT_JOB")).toRealPath();Path root=Path.of(env("EMS_MAINT_ROOT")).toRealPath();
  if(!job.startsWith(root.resolve("data"))||Files.isSymbolicLink(job))throw new IllegalArgumentException("Unsafe job");
  if(command.equals("export")){
   if("1.2.0".equals(System.getenv("EMS_EXPORT_TARGET_VERSION"))){
    char[] pass=env("EMS_BACKUP_PASSWORD").toCharArray();
    try(var c=DriverManager.getConnection(url,user,pw)){JobDiagnostics.phase("compatibility-export");CompatibilityArchive.export(c,job.resolve("compatibility-export"),pass);}finally{Arrays.fill(pass,'\0');}return;
   }
   JobDiagnostics.phase("export-snapshot");
   Path raw=job.resolve("snapshot.zip");Map<String,Object> metadata;try(var c=DriverManager.getConnection(url,user,pw)){metadata=Snapshot.write(c,raw);}
   Exception primary=null;char[] pass=env("EMS_BACKUP_PASSWORD").toCharArray();try{JobDiagnostics.phase("encrypt");BackupCrypto.encrypt(raw,job.resolve("backup.emsbk"),pass);metadata.put("ciphertextSha256",BackupCrypto.sha256(job.resolve("backup.emsbk")));metadata.put("createdAt",java.time.Instant.now().toString());Snapshot.JSON.writeValue(job.resolve("metadata.json").toFile(),metadata);}catch(Exception failure){primary=failure;throw failure;}finally{Arrays.fill(pass,'\0');cleanup(raw,null,primary);}return;
  }
  if(command.equals("restore")){
   if("true".equals(System.getenv("EMS_COMPATIBILITY_PREVIEW"))){
    char[] pass=env("EMS_BACKUP_PASSWORD").toCharArray();
    try{CompatibilityRestore.preview(url,user,pw,job,pass);}finally{Arrays.fill(pass,'\0');}return;
   }
   String path=url.split("\\?",2)[0];String current=path.substring(path.lastIndexOf('/')+1),target=current.replaceFirst("_g[a-z0-9]+$","")+"_g"+UUID.randomUUID().toString().replace("-","").substring(0,12);
   boolean created=false;Exception primary=null;Path raw=job.resolve("input.zip");char[] pass=env("EMS_BACKUP_PASSWORD").toCharArray();
   try{
    JobDiagnostics.phase("decrypt");BackupCrypto.decrypt(job.resolve("input.emsbk"),raw,pass);JobDiagnostics.phase("inspect");var manifest=Snapshot.inspect(raw,job.resolve("staging"));
    JobDiagnostics.phase("create-target");
    try(var c=DriverManager.getConnection(url,user,pw);var st=c.createStatement()){st.executeUpdate("CREATE DATABASE "+Snapshot.q(target)+" CHARACTER SET utf8mb4");created=true;}
    String targetUrl=url(target);JobDiagnostics.phase("migrate-target");Schema.migrate(targetUrl,user,pw);
    Map<String,Object> mapping;try(var c=DriverManager.getConnection(targetUrl,user,pw)){JobDiagnostics.phase("restore-data");mapping=Snapshot.restore(c,manifest,job.resolve("staging"));}
    JobDiagnostics.phase("restore-attachments");Path attachmentStage=job.resolve("attachments-generation");Snapshot.stageAttachments(manifest,job.resolve("staging"),attachmentStage);
    Map<String,Object> result=new LinkedHashMap<>();result.put("target",target);result.put("tables",manifest.get("tables"));result.put("state","PREVIEW_READY");result.put("programVersion",manifest.path("programVersion").asText());result.put("schemaVersion",manifest.path("schemaVersion").asInt());result.put("attachmentGeneration",target);result.put("attachmentStage",attachmentStage.toString());
    result.put("mapping",mapping);Snapshot.JSON.writeValue(job.resolve("result.json").toFile(),result);
   }catch(Exception failure){
    primary=failure;
    if(created){try{Snapshot.JSON.writeValue(job.resolve("failed-target.json").toFile(),Map.of("target",target,"state","FAILED_RETAINED","reason","Isolated recovery target retained; never activate"));}catch(Exception diagnosticFailure){failure.addSuppressed(diagnosticFailure);}}
    throw failure;
   }finally{Arrays.fill(pass,'\0');cleanup(raw,job.resolve("staging"),primary);}
   return;
  }
  if(command.equals("verify-admin")){
   JobDiagnostics.phase("verify-admin");
   var result=Snapshot.JSON.readTree(job.resolve("result.json").toFile());String target=result.path("target").asText();
   try(var c=DriverManager.getConnection(url(target),user,pw);var st=c.prepareStatement("SELECT a.password_hash FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.employee_no=? AND e.user_type=2 AND e.employment_status=1 AND a.account_status=1")){
    st.setString(1,env("EMS_RESTORE_NO"));try(var rs=st.executeQuery()){if(!rs.next()||!new BCryptPasswordEncoder().matches(env("EMS_RESTORE_PASSWORD"),rs.getString(1)))throw new IllegalArgumentException("Restored executive verification failed");}
   }return;
  }
  throw new IllegalArgumentException("Unknown maintenance command");
 }
 static void cleanup(Path raw,Path staging,Exception primary)throws Exception{
  Exception failure=null;try{Files.deleteIfExists(raw);}catch(Exception e){failure=e;}
  try{if(staging!=null)removeStaging(staging);}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
  if(failure!=null){if(primary!=null)primary.addSuppressed(failure);else throw failure;}
 }
 static void removeStaging(Path path)throws Exception{
  if(!Files.exists(path))return;
  try(var walk=Files.walk(path)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(p))throw new IllegalArgumentException("Unexpected staging link");Files.delete(p);}}
 }
}
