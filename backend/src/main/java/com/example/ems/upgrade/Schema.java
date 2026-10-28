package com.example.ems.upgrade;
import java.sql.*;import java.util.*;import org.flywaydb.core.Flyway;
public final class Schema {
 public static final List<String> TABLES=List.of("department","position_info","employee","user_account","employee_change_record","salary_record","operation_log","dictionary_item","attendance_rule","attendance_record","leave_request","leave_approval_record","employee_profile","employee_education","employee_experience","employee_skill","employee_certificate");
 public static void safeUrl(String url){if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(1650[6-9]|165[12][0-9]|16530)/ems_up_(server|portable|dev)_v120(_[a-z0-9]+)?(\\?.*)?"))throw new IllegalArgumentException("Unsafe maintenance target");}
 public static void migrate(String url,String user,String password)throws Exception{
  safeUrl(url);
  try(var c=DriverManager.getConnection(url,user,password)){
   var names=names(c);if(!names.isEmpty()&&!names.contains("flyway_schema_history"))throw new IllegalStateException("Nonempty legacy database requires isolated import, never automatic baseline");
  }
  Flyway f=Flyway.configure().dataSource(url,user,password).locations("classpath:db/upgrade").baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true).validateOnMigrate(true).load();var current=f.info().current();
  if(current!=null&&current.getVersion()!=null&&current.getVersion().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("2"))>0)throw new IllegalStateException("Unknown future database version");
  f.migrate();f.validate();
 }
 public static Set<String> names(Connection c)throws SQLException{
  Set<String> out=new TreeSet<>();try(var rs=c.getMetaData().getTables(c.getCatalog(),null,"%",new String[]{"TABLE"})){while(rs.next())out.add(rs.getString("TABLE_NAME"));}return out;
 }
 public static Map<String,String> columns(Connection c,String table)throws SQLException{
  var out=new LinkedHashMap<String,String>();try(var rs=c.getMetaData().getColumns(c.getCatalog(),null,table,"%")){while(rs.next())out.put(rs.getString("COLUMN_NAME"),rs.getString("TYPE_NAME"));}return out;
 }
 public static void known(Connection c)throws SQLException{
  var names=names(c);var allowed=new HashSet<>(TABLES);allowed.addAll(List.of("account_active_session","flyway_schema_history"));
  if(!allowed.containsAll(names)||!names.containsAll(TABLES.subList(0,12)))throw new IllegalArgumentException("Unknown or incomplete business schema");
  SchemaRegistry.validate(c);
  if(names.contains("flyway_schema_history")){
   try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){
    if(!rows.next()||!Set.of("1","2").contains(rows.getString(1)))throw new IllegalArgumentException("Unknown backup source database version");
   }
  }
 }
 public static void relations(Connection c)throws SQLException{
  Map<String,String> domains=Map.of("employee","user_type NOT IN(0,1,2) OR employment_status NOT IN(0,1,2,3)","user_account","account_status NOT IN(0,1)","department","status NOT IN(0,1,2)","position_info","status NOT IN(0,1) OR is_manager_position NOT IN(0,1)","salary_record","status NOT IN(0,1,2,3,4)","leave_request","status NOT IN(1,2,3,4,5)","attendance_record","attendance_status NOT BETWEEN 0 AND 8");
  for(var entry:domains.entrySet()){
   try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+entry.getKey()+" WHERE "+entry.getValue())){rows.next();if(rows.getLong(1)!=0)throw new IllegalArgumentException("Invalid business status in "+entry.getKey());}
  }

  for(String table:TABLES){if(!names(c).contains(table))continue;
   try(var rs=c.getMetaData().getImportedKeys(c.getCatalog(),null,table)){
    while(rs.next()){
     String col=rs.getString("FKCOLUMN_NAME"),parent=rs.getString("PKTABLE_NAME"),key=rs.getString("PKCOLUMN_NAME");
     if(!TABLES.contains(parent)||!col.matches("[a-z_]+")||!key.matches("[a-z_]+"))throw new IllegalArgumentException("Unexpected relation");
     try(var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM "+table+" a LEFT JOIN "+parent+" b ON a."+col+"=b."+key+" WHERE a."+col+" IS NOT NULL AND b."+key+" IS NULL")){rows.next();if(rows.getLong(1)!=0)throw new IllegalArgumentException("Orphan relation in "+table);}
    }
   }
  }
 }
}
