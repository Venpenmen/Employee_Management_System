package com.example.ems.upgrade;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Executed only by the owned-fixture launcher; no installed database is used. */
public final class CompatibilityCatalogIT {
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);System.out.println("PASS "+message);}
 public static void main(String[] args)throws Exception{
  String url=System.getenv("EMS_IT_URL"),password=System.getenv("EMS_IT_PASSWORD");Schema.safeUrl(url);
  if(System.getenv("EMS_DEVELOPMENT_TASK_ID")==null)throw new IllegalStateException("Owned task context required");
  Path work=Path.of(args[0]);Files.createDirectory(work);int checks=0;
  try(var connection=DriverManager.getConnection(url,"root",password)){
   require(CompatibilityCatalog.identify(connection).id().equals("canonical-121"),"Fresh V3 recognized exactly");checks++;
   for(String table:Schema.TABLES){var expected=CompatibilityCatalog.profile("canonical-121").tables().get(table);require(expected.equals(CompatibilityCatalog.describe(connection).get(table)),"Current table shape "+table);checks++;}
  }
  for(String id:List.of("legacy-113","legacy-120","canonical-120")){
   String name="ems_next_server_v121_c"+UUID.randomUUID().toString().replace("-","").substring(0,10);
   String target=url.replaceFirst("/ems_next_[^?]+","/"+name);
   try(var root=DriverManager.getConnection(url,"root",password);var statement=root.createStatement()){statement.execute("CREATE DATABASE "+Snapshot.q(name)+" CHARACTER SET utf8mb4");}
   // The entire isolated MySQL fixture is collected after this test; no global schema is removed.
   if(id.equals("canonical-120"))Flyway.configure().dataSource(target,"root",password).locations("classpath:db/upgrade").target("2").cleanDisabled(true).load().migrate();
   try(var connection=DriverManager.getConnection(target,"root",password)){
    if(id.startsWith("legacy-"))ScriptUtils.executeSqlScript(connection,new EncodedResource(new ClassPathResource("db/legacy/"+id.substring(7)+".sql"),"UTF-8"));
    CompatibilityCatalog.requireProfile(connection,id);checks++;System.out.println("PASS Registered source "+id);
    if(id.equals("canonical-120")){
     require(CompatibilityCatalog.identify(connection).id().equals(id),"V2 recognized by actual structure and history");checks++;
     try(var statement=connection.createStatement()){statement.execute("ALTER TABLE department ALTER COLUMN base_salary SET DEFAULT 5.00");}
     boolean rejected=false;try{CompatibilityCatalog.requireProfile(connection,id);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Unexpected default rejected");checks++;
     try(var statement=connection.createStatement()){statement.execute("ALTER TABLE department ALTER COLUMN base_salary SET DEFAULT 0.00");statement.execute("ALTER TABLE account_active_session ADD COLUMN unregistered INT NULL");}
     rejected=false;try{CompatibilityCatalog.requireProfile(connection,id);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Unknown ephemeral column rejected");checks++;
     try(var statement=connection.createStatement()){statement.execute("ALTER TABLE account_active_session DROP COLUMN unregistered");statement.execute("ALTER TABLE employee MODIFY name VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL");}
     rejected=false;try{CompatibilityCatalog.requireProfile(connection,id);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Changed text collation rejected");checks++;
     try(var statement=connection.createStatement()){statement.execute("ALTER TABLE employee MODIFY name VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");statement.execute("ALTER TABLE department MODIFY update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");}
     rejected=false;try{CompatibilityCatalog.requireProfile(connection,id);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Changed timestamp update behavior rejected");checks++;
     try(var statement=connection.createStatement()){statement.execute("ALTER TABLE department MODIFY update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");statement.execute("ALTER TABLE operation_log ENGINE=MyISAM");}
     rejected=false;try{CompatibilityCatalog.requireProfile(connection,id);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Nontransactional table engine rejected");checks++;
     try(var statement=connection.createStatement()){statement.execute("ALTER TABLE operation_log ENGINE=InnoDB");statement.execute("CREATE INDEX ems_test_duplicate_index ON operation_log(operator_id)");}
     rejected=false;try{CompatibilityCatalog.requireProfile(connection,id);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Duplicate index multiplicity rejected");checks++;
     try(var statement=connection.createStatement()){statement.execute("DROP INDEX ems_test_duplicate_index ON operation_log");statement.execute("ALTER TABLE operation_log ADD CONSTRAINT ems_test_check CHECK (operation_type<>'')");}
     rejected=false;try{CompatibilityCatalog.requireProfile(connection,id);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Unregistered check constraint rejected");checks++;
     try(var statement=connection.createStatement()){statement.execute("ALTER TABLE operation_log DROP CHECK ems_test_check");statement.executeUpdate("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='2'");}
     rejected=false;try{CompatibilityCatalog.requireProfile(connection,id);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Changed immutable migration checksum rejected without repair");checks++;
    }
   }
  }
  Snapshot.JSON.writeValue(work.resolve("catalog-results.json").toFile(),Map.of("passed",true,"checks",checks,"scope","task-owned isolated database only"));
 }
}
