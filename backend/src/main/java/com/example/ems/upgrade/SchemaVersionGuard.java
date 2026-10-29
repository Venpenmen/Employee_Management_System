package com.example.ems.upgrade;
import org.springframework.stereotype.Component;import org.springframework.boot.ApplicationRunner;import org.springframework.boot.ApplicationArguments;import javax.sql.DataSource;
@Component public class SchemaVersionGuard implements ApplicationRunner {
 private final DataSource data;
 public SchemaVersionGuard(DataSource d){data=d;}
 public void run(ApplicationArguments ignored)throws Exception{
  try(var c=data.getConnection()){
   Schema.safeUrl(c.getMetaData().getURL());
   if(!Schema.names(c).contains("flyway_schema_history"))throw new IllegalStateException("Prepare database with the local migration launcher before running the application");
   try(var s=c.createStatement();var rows=s.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){
    if(!rows.next()||!"2".equals(rows.getString(1)))throw new IllegalStateException("Database version does not match this build");
   }
  }
  var f=org.flywaydb.core.Flyway.configure().dataSource(data).locations("classpath:db/upgrade").cleanDisabled(true).baselineOnMigrate(false).load();f.validate();
 }
}
