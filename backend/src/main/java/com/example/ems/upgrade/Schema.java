package com.example.ems.upgrade;
import java.sql.*;import java.util.*;import org.flywaydb.core.Flyway;
public final class Schema {
 public static final List<String> TABLES=List.of("department","position_info","employee","user_account","employee_change_record","salary_record","operation_log","dictionary_item","attendance_rule","attendance_record","leave_request","leave_approval_record","employee_profile","employee_education","employee_experience","employee_skill","employee_certificate","account_security","secondary_credential","password_change_request","login_security_event","security_notification");
 public static final List<String> EPHEMERAL=List.of("account_active_session","security_grant");
 public static void safeUrl(String url){if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(1660[6-9]|166[12][0-9]|16630)/ems_next_(server|portable|dev)_v121(_[a-z0-9]+)?(\\?.*)?"))throw new IllegalArgumentException("Unsafe maintenance target");}
 public static void migrate(String url,String user,String password)throws Exception{
  safeUrl(url);
  try(var c=DriverManager.getConnection(url,user,password)){
   var currentNames=names(c);if(currentNames.contains("flyway_schema_history")){try(var s=c.createStatement();var rows=s.executeQuery("SELECT version,success FROM flyway_schema_history WHERE version IS NOT NULL")){while(rows.next())if(!rows.getBoolean(2)||!Set.of("1","2","3").contains(rows.getString(1)))throw new IllegalStateException("数据库存在失败迁移或不兼容版本，已禁止启动和自动重试；请使用经过验证的隔离恢复路径");}}
   var names=names(c);if(!names.isEmpty()&&!names.contains("flyway_schema_history"))throw new IllegalStateException("Nonempty legacy database requires isolated import, never automatic baseline");
  }
  Flyway f=Flyway.configure().dataSource(url,user,password).locations("classpath:db/upgrade").baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true).validateOnMigrate(true).load();var current=f.info().current();
  if(current!=null&&current.getVersion()!=null&&current.getVersion().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("3"))>0)throw new IllegalStateException("Unknown future database version");
  f.migrate();f.validate();
  try(var c=DriverManager.getConnection(url,user,password)){known(c);if(!names(c).containsAll(TABLES)||!names(c).containsAll(EPHEMERAL))throw new IllegalStateException("迁移后结构不完整，禁止启动");relations(c);}
 }
 public static Set<String> names(Connection c)throws SQLException{
  Set<String> out=new TreeSet<>();try(var rs=c.getMetaData().getTables(c.getCatalog(),null,"%",new String[]{"TABLE"})){while(rs.next())out.add(rs.getString("TABLE_NAME"));}return out;
 }
 public static Map<String,String> columns(Connection c,String table)throws SQLException{
  var out=new LinkedHashMap<String,String>();try(var rs=c.getMetaData().getColumns(c.getCatalog(),null,table,"%")){while(rs.next())out.put(rs.getString("COLUMN_NAME"),rs.getString("TYPE_NAME"));}return out;
 }
 public static void known(Connection c)throws SQLException{
  var names=names(c);var allowed=new HashSet<>(TABLES);allowed.addAll(List.of("account_active_session","security_grant","flyway_schema_history"));
  if(!allowed.containsAll(names)||!names.containsAll(TABLES.subList(0,12)))throw new IllegalArgumentException("Unknown or incomplete business schema");
  SchemaRegistry.validate(c);
  if(names.contains("flyway_schema_history")){
   try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT version,success FROM flyway_schema_history WHERE version IS NOT NULL")){while(rows.next())if(!rows.getBoolean(2)||!Set.of("1","2","3").contains(rows.getString(1)))throw new IllegalStateException("数据库迁移历史包含失败或不兼容版本，禁止业务启动和导出");}

   try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){
    if(!rows.next()||!Set.of("1","2","3").contains(rows.getString(1)))throw new IllegalArgumentException("Unknown backup source database version");
   }
  }
 }
 public static void relations(Connection c)throws SQLException{
  Map<String,String> domains=Map.of("employee","user_type NOT IN(0,1,2) OR employment_status NOT IN(0,1,2,3)","user_account","account_status NOT IN(0,1)","department","status NOT IN(0,1,2)","position_info","status NOT IN(0,1) OR is_manager_position NOT IN(0,1)","salary_record","status NOT IN(0,1,2,3,4)","leave_request","status NOT IN(1,2,3,4,5)","attendance_record","attendance_status NOT BETWEEN 0 AND 8");
  for(var entry:domains.entrySet()){
   try(var statement=c.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM "+entry.getKey()+" WHERE "+entry.getValue())){rows.next();if(rows.getLong(1)!=0)throw new IllegalArgumentException("Invalid business status in "+entry.getKey());}
  }

  for(String sql:List.of(
   "SELECT COUNT(*) FROM employee e JOIN position_info p ON p.id=e.position_id WHERE e.department_id IS NULL OR p.department_id<>e.department_id",
   "SELECT COUNT(*) FROM department d JOIN employee e ON e.id=d.manager_id WHERE e.user_type<>1 OR e.department_id IS NULL OR e.department_id<>d.id",
   "SELECT COUNT(*) FROM leave_request r JOIN employee e ON e.id=r.current_approver_id WHERE r.status=1 AND e.user_type NOT IN(1,2)"
  )){try(var statement=c.createStatement();var rows=statement.executeQuery(sql)){rows.next();if(rows.getLong(1)!=0)throw new IllegalArgumentException("Invalid employee/department/manager/approval business relationship");}}
  if(names(c).contains("account_security")){
   for(String sql:List.of(
    "SELECT COUNT(*) FROM account_security s JOIN employee e ON e.id=s.employee_id WHERE s.frozen NOT IN(0,1) OR s.temporary_state NOT IN('NORMAL','TEMP','REISSUE') OR s.temporary_errors<0 OR (e.user_type=2 AND (s.frozen<>0 OR s.temporary_state<>'NORMAL'))",
    "SELECT COUNT(*) FROM secondary_credential s JOIN employee e ON e.id=s.employee_id WHERE e.user_type<>2 OR s.credential_version<1 OR s.failed_attempts<0",
    "SELECT COUNT(*) FROM password_change_request WHERE status NOT IN('PENDING','APPROVED','REJECTED','COMPLETED','EXPIRED','CANCELLED') OR (status IN('PENDING','APPROVED') AND (active_employee_id IS NULL OR active_employee_id<>employee_id)) OR (status NOT IN('PENDING','APPROVED') AND active_employee_id IS NOT NULL)"
   )){try(var statement=c.createStatement();var rows=statement.executeQuery(sql)){rows.next();if(rows.getLong(1)!=0)throw new IllegalArgumentException("Invalid persistent account security relationship/state");}}
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
