package com.example.ems.upgrade;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Phased driver around an actual unmodified old server process, orchestrated by the owned launcher. */
public final class ControlledLegacyIT {
 static final char[] PASSWORD="SyntheticControlledRoundtrip91".toCharArray();
 static String env(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing isolated fixture input");return value;}
 static ObjectNode syntheticAuth(){var result=Snapshot.JSON.createObjectNode();for(var entry:Map.of("executive",1,"employee",2,"manager",3).entrySet()){var user=result.putObject(entry.getKey());user.put("id",String.valueOf(entry.getValue()));user.put("no","26000"+entry.getValue());user.put("password","SyntheticPassword123");}result.put("phone","13800000009");return result;}
 static ObjectNode demo(Connection connection,Path work)throws Exception{
  Path bundle=Path.of(env("EMS_DEMO_BUNDLE")),encrypted=work.resolve("demo.emsbk"),raw=work.resolve("demo.zip"),stage=work.resolve("demo-stage");var passwords=new HashMap<String,String>();
  try(var zip=new ZipFile(bundle.toFile())){
   try(var stream=zip.getInputStream(zip.getEntry("EMS_v1.2.0_demo.emsbk"))){Files.copy(stream,encrypted);}
   if(!BackupCrypto.sha256(encrypted).equals("e94cb6f0be8d56c0750d58839af42bccd737600591eca74456cc0cd8ddd2c582"))throw new AssertionError("Not the preserved public synthetic demo");
   String pass;try(var stream=zip.getInputStream(zip.getEntry("DEMO_BACKUP_PASSWORD.txt"))){pass=new String(stream.readAllBytes(),StandardCharsets.UTF_8).strip();pass=pass.substring(pass.lastIndexOf('：')+1).strip();}
   char[] key=pass.toCharArray();try{BackupCrypto.decrypt(encrypted,raw,key);}finally{Arrays.fill(key,'\0');}
   try(var stream=zip.getInputStream(zip.getEntry("DEMO_ACCOUNTS.csv"))){for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).split("\\R")){String[] fields=line.split(",",-1);if(fields.length==4&&fields[0].matches("[0-9]{6}"))passwords.put(fields[0],fields[3]);}}
  }
  try{
   var manifest=Snapshot.inspect(raw,stage);if(!BackupVersionCatalog.identify(manifest).id().equals("official-demo-120-sparse"))throw new AssertionError("Official demo archive descriptor not recognized");var description=Snapshot.JSON.createObjectNode();description.put("profileId","official-demo-120-sparse");description.put("programVersion",manifest.path("programVersion").asText());description.put("schemaVersion",manifest.path("schemaVersion").asInt());var tables=description.putObject("tables");
   manifest.path("tables").fields().forEachRemaining(entry->tables.set(entry.getKey(),entry.getValue().path("columns").deepCopy()));
   Snapshot.JSON.writeValue(work.resolve("demo-schema-results.json").toFile(),description);
   Snapshot.restore(connection,manifest,stage);
  }finally{Files.deleteIfExists(raw);MaintenanceMain.removeStaging(stage);}
  var auth=Snapshot.JSON.createObjectNode();
  try(var st=connection.createStatement();var rows=st.executeQuery("SELECT e.id,e.employee_no,e.user_type,d.manager_id FROM employee e JOIN user_account a ON a.employee_id=e.id LEFT JOIN department d ON d.id=e.department_id WHERE e.employment_status=1 AND a.account_status=1 ORDER BY e.id")){
   var candidates=new ArrayList<Map<String,String>>();while(rows.next())if(passwords.containsKey(rows.getString(2)))candidates.add(Map.of("id",rows.getString(1),"no",rows.getString(2),"role",rows.getString(3),"manager",Objects.toString(rows.getObject(4),"")));
   var executive=candidates.stream().filter(r->r.get("role").equals("2")).findFirst().orElseThrow();
   var employee=candidates.stream().filter(r->r.get("role").equals("0")&&candidates.stream().anyMatch(m->m.get("role").equals("1")&&m.get("id").equals(r.get("manager")))).findFirst().orElseThrow();
   var manager=candidates.stream().filter(r->r.get("id").equals(employee.get("manager"))).findFirst().orElseThrow();
   for(var entry:Map.of("executive",executive,"employee",employee,"manager",manager).entrySet()){var user=auth.putObject(entry.getKey());user.put("id",entry.getValue().get("id"));user.put("no",entry.getValue().get("no"));user.put("password",passwords.get(entry.getValue().get("no")));}
  }
  String phone=null;try(var check=connection.prepareStatement("SELECT COUNT(*) FROM employee WHERE phone=?")){for(int i=9000;i<10000;i++){String candidate="1390000"+i;check.setString(1,candidate);try(var rows=check.executeQuery()){rows.next();if(rows.getLong(1)==0){phone=candidate;break;}}}}
  if(phone==null)throw new AssertionError("No unused synthetic phone");auth.put("phone",phone);return auth;
 }
 public static void main(String[] args)throws Exception{
  String command=args[0],url=env("EMS_IT_URL"),password=env("EMS_IT_PASSWORD");Path work=Path.of(args[1]);
  if(command.equals("source")){
   Schema.safeUrl(url);Files.createDirectory(work);
   try(var connection=DriverManager.getConnection(url,"root",password)){
    ObjectNode auth;
    if(System.getenv("EMS_DEMO_BUNDLE")!=null&&!System.getenv("EMS_DEMO_BUNDLE").isBlank())auth=demo(connection,work);
    else{
     UpgradeDatabaseIT.seed(connection);auth=syntheticAuth();
     try(var st=connection.createStatement()){
     st.executeUpdate("UPDATE department SET status=1 WHERE id=1");
     st.executeUpdate("INSERT INTO position_info(id,department_id,position_name,position_salary,is_manager_position) VALUES(2,1,'Synthetic manager',2500,1)");
     st.executeUpdate("INSERT INTO employee(id,employee_no,name,identity_number,birth_date,phone,department_id,position_id,user_type,hire_date,employment_status) VALUES(3,'260003','SyntheticManager','110101199001019997','1990-01-01','13800000003',1,2,1,'2020-01-01',1)");
     st.executeUpdate("INSERT INTO user_account(employee_id,password_hash) SELECT 3,password_hash FROM user_account WHERE employee_id=1");
     st.executeUpdate("UPDATE department SET manager_id=3 WHERE id=1");
     }
    }
    long employee=auth.path("employee").path("id").asLong(),executive=auth.path("executive").path("id").asLong();
    try(var st=connection.prepareStatement("INSERT INTO account_security(employee_id) VALUES(?)")){st.setLong(1,employee);st.executeUpdate();}
    try(var st=connection.prepareStatement("INSERT INTO login_security_event(employee_id,event_type,summary) VALUES(?,'SYNTHETIC','Synthetic roundtrip event')")){st.setLong(1,employee);st.executeUpdate();}
    try(var st=connection.prepareStatement("INSERT INTO security_notification(employee_id,event_type,subject_employee_id,summary) VALUES(?,'SYNTHETIC',?,'Synthetic roundtrip notice')")){st.setLong(1,executive);st.setLong(2,employee);st.executeUpdate();}
    try(var st=connection.prepareStatement("INSERT INTO secondary_credential(employee_id,password_hash) VALUES(?,?)")){st.setLong(1,executive);st.setString(2,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("SyntheticSecondaryPassword91"));st.executeUpdate();}
    LineageTracker.writeEncrypted(auth,work.resolve("test-auth.emsbk"),PASSWORD);
    CompatibilityArchive.export(connection,work.resolve("bundle"),PASSWORD);
   }
  }else if(command.equals("install")){
   try(var connection=DriverManager.getConnection(url,"root",password);var extension=CompatibilityExtensions.read(work.resolve("bundle/extensions.emsbk"),work.resolve("install-private"),PASSWORD);var old=new Rc2MigrationIT.Historical(Path.of(args[2]))){
    LineageTracker.owned(connection);extension.requireOriginalBase(work.resolve("bundle/base-v120.emsbk"));
    BackupCrypto.decrypt(work.resolve("bundle/base-v120.emsbk"),work.resolve("install.zip"),PASSWORD);var manifest=Snapshot.inspect(work.resolve("install.zip"),work.resolve("install-stage"));
    old.restoreV2(connection,manifest,work.resolve("install-stage"));LineageTracker.install(connection,extension,work.resolve("receipt.emsbk"),PASSWORD);
   }finally{Files.deleteIfExists(work.resolve("install.zip"));MaintenanceMain.removeStaging(work.resolve("install-stage"));}
  }else if(command.equals("return")){
   try(var connection=DriverManager.getConnection(url,"root",password);var extension=CompatibilityExtensions.read(work.resolve("bundle/extensions.emsbk"),work.resolve("return-private"),PASSWORD)){
    var receipt=LineageTracker.readEncrypted(work.resolve("receipt.emsbk"),work.resolve("receipt-private.json"),PASSWORD);LineageTracker.exportReturn(connection,extension,receipt,work.resolve("return"),PASSWORD);
   }
  }else if(command.equals("restore")){
   Schema.safeUrl(url);
   try(var connection=DriverManager.getConnection(url,"root",password);var extension=CompatibilityExtensions.read(work.resolve("bundle/extensions.emsbk"),work.resolve("restore-private"),PASSWORD)){
    var proof=LineageTracker.readEncrypted(work.resolve("return/return-proof.emsbk"),work.resolve("proof-private.json"),PASSWORD);var verified=LineageTracker.verifyReturn(extension,work.resolve("return/return-v120.emsbk"),proof);
    BackupCrypto.decrypt(work.resolve("return/return-v120.emsbk"),work.resolve("returned.zip"),PASSWORD);var manifest=Snapshot.inspect(work.resolve("returned.zip"),work.resolve("returned-stage"));
    var plan=ExtensionReconciler.preview(extension,work.resolve("returned-stage/tables"),manifest,verified,BackupCrypto.sha256(work.resolve("return/return-v120.emsbk")));
    if(plan.blocked()||!plan.issues().isEmpty())throw new AssertionError("Unexpected extension conflict in legal old runtime roundtrip");
    String target="ems_next_server_v121_z"+UUID.randomUUID().toString().replace("-","").substring(0,12),targetUrl=url.replaceFirst("/ems_next_[^?]+","/"+target);
    try(var st=connection.createStatement()){st.execute("CREATE DATABASE "+Snapshot.q(target)+" CHARACTER SET utf8mb4");}Schema.migrate(targetUrl,"root",password);
    try(var restored=DriverManager.getConnection(targetUrl,"root",password)){
     Snapshot.restore(restored,manifest,work.resolve("returned-stage"));int count=ExtensionReconciler.applyIsolated(restored,plan,plan.previewSha256(),false);if(count!=4)throw new AssertionError("Persistent security extensions were not all restored");
     var auth=LineageTracker.readEncrypted(work.resolve("test-auth.emsbk"),work.resolve("auth-private.json"),PASSWORD);
     try(var st=restored.prepareStatement("SELECT phone FROM employee WHERE id=?")){st.setLong(1,auth.path("employee").path("id").asLong());try(var rows=st.executeQuery()){if(!rows.next()||!rows.getString(1).equals(auth.path("phone").asText()))throw new AssertionError("Old runtime employee edit lost");}}
     try(var st=restored.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM secondary_credential")){rows.next();if(rows.getInt(1)!=1)throw new AssertionError("Unchanged executive secondary credential lost");}
     try(var st=restored.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM employee_skill WHERE name='SyntheticOldSkill'")){rows.next();if(rows.getInt(1)!=1)throw new AssertionError("Old runtime skill not retained");}
     Schema.relations(restored);
    }
    Snapshot.JSON.writeValue(work.resolve("controlled-roundtrip-results.json").toFile(),Map.of("passed",true,"actualOldServer",true,"officialDemo",Files.exists(work.resolve("demo-schema-results.json")),"persistentSecurityExtensions",4,"commonChangesRetained",true,"activeDatabaseSwitched",false));
   }finally{Files.deleteIfExists(work.resolve("returned.zip"));MaintenanceMain.removeStaging(work.resolve("returned-stage"));}
  }else throw new IllegalArgumentException("Unknown isolated phase");
 }
}
