package com.example.ems.upgrade;
import java.sql.*;import java.nio.file.*;import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public final class MaintenanceMain {
 static String env(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing maintenance input");return v;}
 static String url(String schema){String original=env("EMS_MAINT_URL");Schema.safeUrl(original);String result=original.replaceFirst("/ems_up_[^?]+","/"+schema);Schema.safeUrl(result);return result;}
 public static void main(String[] args){
  try{execute(args);System.out.println("Maintenance completed");}
  catch(Exception e){diagnostic("Maintenance",e);System.exit(1);}
 }
 static void diagnostic(String phase,Exception error){
  System.err.println(phase+" failed ["+UUID.randomUUID()+"]");
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
  String command=args[0],url=env("EMS_MAINT_URL"),user=env("EMS_MAINT_USER"),pw=env("EMS_MAINT_PASSWORD");Schema.safeUrl(url);
  if(System.getenv("EMS_MAINT_ACTOR_ID")!=null){
   try(var c=DriverManager.getConnection(url,user,pw)){
    c.setAutoCommit(false);
    try(var s=c.prepareStatement("SELECT a.account_status,e.employment_status,e.user_type,x.token_hash FROM user_account a JOIN employee e ON e.id=a.employee_id JOIN account_active_session x ON x.employee_id=e.id WHERE e.id=? FOR UPDATE")){
     s.setLong(1,Long.parseLong(env("EMS_MAINT_ACTOR_ID")));
     try(var result=s.executeQuery()){if(!result.next()||result.getInt(1)!=1||result.getInt(2)!=1||result.getInt(3)!=2||!java.security.MessageDigest.isEqual(result.getString(4).getBytes(java.nio.charset.StandardCharsets.US_ASCII),env("EMS_MAINT_ACTOR_HASH").getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IllegalArgumentException("Executive operation authorization expired");}
    }c.commit();
   }
  }
  if(command.equals("verify-current"))return;
  if(command.equals("migrate")){Schema.migrate(url,user,pw);return;}
  Path job=Path.of(env("EMS_MAINT_JOB")).toRealPath();Path root=Path.of(env("EMS_MAINT_ROOT")).toRealPath();
  if(!job.startsWith(root.resolve("data"))||Files.isSymbolicLink(job))throw new IllegalArgumentException("Unsafe job");
  if(command.equals("export")){
   Path raw=job.resolve("snapshot.zip");Map<String,Object> metadata;try(var c=DriverManager.getConnection(url,user,pw)){metadata=Snapshot.write(c,raw);}
   char[] pass=env("EMS_BACKUP_PASSWORD").toCharArray();try{BackupCrypto.encrypt(raw,job.resolve("backup.emsbk"),pass);metadata.put("ciphertextSha256",BackupCrypto.sha256(job.resolve("backup.emsbk")));metadata.put("createdAt",java.time.Instant.now().toString());Snapshot.JSON.writeValue(job.resolve("metadata.json").toFile(),metadata);}finally{Arrays.fill(pass,'\0');Files.deleteIfExists(raw);}return;
  }
  if(command.equals("restore")){
   String path=url.split("\\?",2)[0];String current=path.substring(path.lastIndexOf('/')+1),target=current.replaceFirst("_g[a-z0-9]+$","")+"_g"+UUID.randomUUID().toString().replace("-","").substring(0,12);
   boolean created=false;Path raw=job.resolve("input.zip");char[] pass=env("EMS_BACKUP_PASSWORD").toCharArray();
   try{
    BackupCrypto.decrypt(job.resolve("input.emsbk"),raw,pass);var manifest=Snapshot.inspect(raw,job.resolve("staging"));
    try(var c=DriverManager.getConnection(url,user,pw);var st=c.createStatement()){st.executeUpdate("CREATE DATABASE "+Snapshot.q(target)+" CHARACTER SET utf8mb4");created=true;}
    String targetUrl=url(target);Schema.migrate(targetUrl,user,pw);
    try(var c=DriverManager.getConnection(targetUrl,user,pw)){Snapshot.restore(c,manifest,job.resolve("staging"));}
    Map<String,Object> result=new LinkedHashMap<>();result.put("target",target);result.put("tables",manifest.get("tables"));result.put("state","PREVIEW_READY");result.put("programVersion",manifest.path("programVersion").asText());result.put("schemaVersion",manifest.path("schemaVersion").asInt());
    Snapshot.JSON.writeValue(job.resolve("result.json").toFile(),result);
   }catch(Exception failure){
    if(created){
     Path active=root.resolve("data/"+current.split("_")[2]+"/active-schema");
     if(!Files.exists(active)||!Files.readString(active).trim().equals(target)){
      try(var c=DriverManager.getConnection(url,user,pw);var statement=c.createStatement()){statement.executeUpdate("DROP DATABASE "+Snapshot.q(target));}
      catch(Exception cleanup){failure.addSuppressed(cleanup);}
     }
    }
    throw failure;
   }finally{Arrays.fill(pass,'\0');Files.deleteIfExists(raw);removeStaging(job.resolve("staging"));}
   return;
  }
  if(command.equals("verify-admin")){
   var result=Snapshot.JSON.readTree(job.resolve("result.json").toFile());String target=result.path("target").asText();
   try(var c=DriverManager.getConnection(url(target),user,pw);var st=c.prepareStatement("SELECT a.password_hash FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.employee_no=? AND e.user_type=2 AND e.employment_status=1 AND a.account_status=1")){
    st.setString(1,env("EMS_RESTORE_NO"));try(var rs=st.executeQuery()){if(!rs.next()||!new BCryptPasswordEncoder().matches(env("EMS_RESTORE_PASSWORD"),rs.getString(1)))throw new IllegalArgumentException("Restored executive verification failed");}
   }return;
  }
  throw new IllegalArgumentException("Unknown maintenance command");
 }
 static void removeStaging(Path path)throws Exception{
  if(!Files.exists(path))return;
  try(var walk=Files.walk(path)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(p))throw new IllegalArgumentException("Unexpected staging link");Files.delete(p);}}
 }
}
