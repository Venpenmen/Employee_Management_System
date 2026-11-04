package com.example.ems.upgrade;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.zip.ZipFile;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Opt-in integration main. Only runs against an owned launcher fixture; never an installed DB. */
public final class LegacyDemoImportIT {
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);System.out.println("PASS: "+text);}
 public static void main(String[] args)throws Exception {
  String base=System.getenv("EMS_IT_URL"),pw=System.getenv("EMS_IT_PASSWORD");Schema.safeUrl(base);
  Path work=Path.of(args[0]),bundle=Path.of(System.getenv("EMS_DEMO_BUNDLE"));Files.createDirectories(work);
  String target="ems_next_server_v121_g"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  String url=base.replaceFirst("/ems_next_[^?]+","/"+target);
  try(var archive=new ZipFile(bundle.toFile())){
   byte[] passBytes=archive.getInputStream(archive.getEntry("DEMO_BACKUP_PASSWORD.txt")).readAllBytes();
   String pass=new String(passBytes,StandardCharsets.UTF_8).strip();pass=pass.substring(pass.lastIndexOf('：')+1).strip();
   Path input=work.resolve("input.emsbk"),raw=work.resolve("input.zip");
   try(var stream=archive.getInputStream(archive.getEntry("EMS_v1.2.0_demo.emsbk"))){Files.copy(stream,input);}
   check(BackupCrypto.sha256(input).equals("e94cb6f0be8d56c0750d58839af42bccd737600591eca74456cc0cd8ddd2c582"),"exact preserved failing demo ciphertext SHA256");
   char[] password=pass.toCharArray();try{BackupCrypto.decrypt(input,raw,password);}finally{Arrays.fill(password,'\0');Arrays.fill(passBytes,(byte)0);}
   JsonNode manifest=Snapshot.inspect(raw,work.resolve("staging"));
   try(var c=DriverManager.getConnection(base,"root",pw);var st=c.createStatement()){st.executeUpdate("CREATE DATABASE "+Snapshot.q(target)+" CHARACTER SET utf8mb4");}
   try{
    Schema.migrate(url,"root",pw);
    try(var c=DriverManager.getConnection(url,"root",pw)){
     if(args.length>1&&args[1].equals("--expect-original-failure")){
      Path old=work.resolve("preserved-rc2.jar");try(var preserved=new ZipFile(System.getenv("EMS_REPRO_REFERENCE_ZIP"));var in=preserved.getInputStream(preserved.getEntry("app/ems-1.2.1-next-rc2.jar"))){Files.copy(in,old);}
      check(BackupCrypto.sha256(old).equals("dc595706fffc3ff4c47bfba93540270a1fc579ed6ca888d47a8560c32f250d44"),"preserved pre-fix program hash verified");
      try(var previous=new Rc2MigrationIT.Historical(old)){
       try{previous.restoreV2(c,manifest,work.resolve("staging"));throw new AssertionError("Original failure not reproduced");}
       catch(java.lang.reflect.InvocationTargetException wrapper){var expected=wrapper.getCause();check(expected instanceof IllegalArgumentException&&expected.getMessage().equals("Restored primary keys or field digest mismatch: department"),"original failure reproduced at department typed value digest");System.out.println("ROOT_CAUSE: "+expected.getMessage());}
      }
      return;
     }
     var mapping=Snapshot.restore(c,manifest,work.resolve("staging"));Schema.known(c);Schema.relations(c);
     Snapshot.JSON.writeValue(work.resolve("field-mapping-results.json").toFile(),mapping);
     check(((Number)((Map<?,?>)mapping.get("salary_record")).get("generatedIds")).longValue()==141,"missing leaf IDs generated without changing referenced identities");
     for(var fields=manifest.path("tables").fields();fields.hasNext();){var item=fields.next();try(var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM "+Snapshot.q(item.getKey()))){rows.next();check(rows.getLong(1)==item.getValue().path("rows").asLong(),"legacy demo restored count "+item.getKey());}}
     try(var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM account_security WHERE frozen<>0 OR temporary_state<>'NORMAL'")){rows.next();check(rows.getLong(1)==0,"old accounts not frozen or forced to change");}
     // Every original BCrypt hash, role and employee relationship must remain usable.
     JsonNode accounts=Snapshot.JSON.readTree(work.resolve("staging/tables/user_account.json").toFile());
     try(var st=c.prepareStatement("SELECT password_hash,account_status FROM user_account WHERE employee_id=?")){
      for(var row:accounts){st.setLong(1,row.path("employee_id").asLong());try(var actual=st.executeQuery()){check(actual.next()&&actual.getString(1).equals(row.path("password_hash").asText())&&actual.getInt(2)==row.path("account_status").asInt(),"original account hash and status retained");}}
     }
     Path roundtrip=work.resolve("upgraded.zip");Snapshot.write(c,roundtrip);
     check(Files.size(roundtrip)>0,"upgraded database exports successfully with current schema");
     var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"root",pw);var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
     var auth=new com.example.ems.service.AuthService(jdbc,new com.example.ems.service.LogService(jdbc));var sessions=new com.example.ems.service.SessionService(jdbc);var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
     Set<Integer> roles=new HashSet<>();String csv=new String(archive.getInputStream(archive.getEntry("DEMO_ACCOUNTS.csv")).readAllBytes(),StandardCharsets.UTF_8);
     for(String line:csv.split("\\R")){String[] fields=line.split(",",-1);if(fields.length!=4||!fields[0].matches("[0-9]{6}"))continue;var person=auth.login(fields[0],fields[3]);String token=tx.execute(s->sessions.issue(person.id(),fields[3]));check(sessions.validate(person.id(),token)==com.example.ems.service.SessionService.Validity.VALID,"preserved demo account transactional login");tx.execute(s->{sessions.revoke(person.id(),token);return null;});roles.add(person.userType());}
     check(roles.equals(Set.of(0,1,2)),"executive manager and employee original credentials all work");
     String second="ems_next_server_v121_g"+UUID.randomUUID().toString().replace("-","").substring(0,12),secondUrl=base.replaceFirst("/ems_next_[^?]+","/"+second);
     try(var admin=DriverManager.getConnection(base,"root",pw);var statement=admin.createStatement()){
      statement.executeUpdate("CREATE DATABASE "+Snapshot.q(second)+" CHARACTER SET utf8mb4");
      try{Schema.migrate(secondUrl,"root",pw);var current=Snapshot.inspect(roundtrip,work.resolve("upgraded-stage"));try(var restored=DriverManager.getConnection(secondUrl,"root",pw)){Snapshot.restore(restored,current,work.resolve("upgraded-stage"));Schema.relations(restored);}check(true,"upgraded current backup restores again in another isolated schema");
       // A historically valid sparse profile has update_time but no create_time.
       // Backfill must explicitly retain update_time despite ON UPDATE CURRENT_TIMESTAMP.
       Path profiles=work.resolve("upgraded-stage/tables/employee_profile.json");var rows=Snapshot.JSON.readTree(profiles.toFile());
       for(var row:rows){((com.fasterxml.jackson.databind.node.ObjectNode)row).remove("create_time");((com.fasterxml.jackson.databind.node.ObjectNode)row).put("update_time","2001-02-03 04:05:06");}
       Snapshot.JSON.writeValue(profiles.toFile(),rows);var meta=(com.fasterxml.jackson.databind.node.ObjectNode)current.path("tables").path("employee_profile");((com.fasterxml.jackson.databind.node.ObjectNode)meta.path("columns")).remove("create_time");meta.put("sha256",BackupCrypto.sha256(profiles));
       String sparse="ems_next_server_v121_g"+UUID.randomUUID().toString().replace("-","").substring(0,12),sparseUrl=base.replaceFirst("/ems_next_[^?]+","/"+sparse);
       statement.executeUpdate("CREATE DATABASE "+Snapshot.q(sparse)+" CHARACTER SET utf8mb4");
       try{Schema.migrate(sparseUrl,"root",pw);try(var restored=DriverManager.getConnection(sparseUrl,"root",pw)){Snapshot.restore(restored,current,work.resolve("upgraded-stage"));try(var st=restored.createStatement();var checked=st.executeQuery("SELECT COUNT(*) FROM employee_profile WHERE update_time='2001-02-03 04:05:06' AND create_time=update_time")){checked.next();check(checked.getLong(1)==48,"sparse legacy profile backfill preserves all historical update timestamps");}}}
       finally{statement.executeUpdate("DROP DATABASE "+Snapshot.q(sparse));}
      }
      finally{statement.executeUpdate("DROP DATABASE "+Snapshot.q(second));MaintenanceMain.removeStaging(work.resolve("upgraded-stage"));}
     }
    }
   }finally{try(var c=DriverManager.getConnection(base,"root",pw);var st=c.createStatement()){st.executeUpdate("DROP DATABASE "+Snapshot.q(target));}}
  }finally{MaintenanceMain.removeStaging(work.resolve("staging"));Files.deleteIfExists(work.resolve("input.zip"));}
 }
}
