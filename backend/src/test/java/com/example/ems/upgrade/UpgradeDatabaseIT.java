package com.example.ems.upgrade;
import java.sql.*;import java.nio.file.*;import java.util.*;import java.io.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;import org.springframework.core.io.ClassPathResource;import org.springframework.core.io.support.EncodedResource;import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class UpgradeDatabaseIT {
 static int count;static void check(boolean v,String name){if(!v)throw new AssertionError(name);count++;System.out.println("PASS: "+name);}
 static String base,pw;static List<String> created=new ArrayList<>();
 static Connection db(String schema)throws Exception{return DriverManager.getConnection(base.replaceFirst("/ems_up_[^?]+","/"+schema),"root",pw);}
 static String create(String tag)throws Exception{
  String name="ems_up_server_v120_"+tag+UUID.randomUUID().toString().replace("-","").substring(0,10);
  try(var c=DriverManager.getConnection(base,"root",pw);var s=c.createStatement()){s.executeUpdate("CREATE DATABASE "+name+" CHARACTER SET utf8mb4");}created.add(name);return name;
 }
 static void migrate(String name)throws Exception{Schema.migrate(base.replaceFirst("/ems_up_[^?]+","/"+name),"root",pw);}
 static void seed(Connection c)throws Exception{
  var s=c.createStatement();
  s.executeUpdate("INSERT INTO department(id,department_name,base_salary) VALUES(1,'虚构测试部',5000)");
  s.executeUpdate("INSERT INTO position_info(id,department_id,position_name,position_salary) VALUES(1,1,'虚构岗位',1200)");
  s.executeUpdate("INSERT INTO employee(id,employee_no,name,identity_number,birth_date,phone,department_id,position_id,user_type,hire_date,employment_status) VALUES(1,'260001','虚构高层','110101199001019999','1990-01-01','13800000001',NULL,NULL,2,'2020-01-01',1),(2,'260002','虚构员工','110101199001019998','1990-01-01','13800000002',1,1,0,'2020-01-01',1)");
  try(var insert=c.prepareStatement("INSERT INTO user_account(employee_id,password_hash) VALUES(?,?)")){for(int id=1;id<=2;id++){insert.setInt(1,id);insert.setString(2,new BCryptPasswordEncoder().encode("SyntheticPassword123"));insert.executeUpdate();}}
  s.executeUpdate("INSERT INTO employee_change_record(employee_id,change_type,reason,operator_id) VALUES(2,'HIRE','虚构入职',1)");
  s.executeUpdate("INSERT INTO salary_record(employee_id,salary_month,department_name_snapshot,position_name_snapshot,actual_salary,operator_id) VALUES(2,'2030-01','虚构测试部','虚构岗位',12345.67,1)");
  s.executeUpdate("INSERT INTO operation_log(operator_id,operation_type,summary) VALUES(1,'TEST','虚构审计')");
  s.executeUpdate("INSERT INTO dictionary_item(dict_type,item_value,item_label) VALUES('gender','1','男')");
  s.executeUpdate("INSERT INTO attendance_rule(rule_name,work_start_time,work_end_time) VALUES('虚构制度','09:00:00','18:00:00')");
  s.executeUpdate("INSERT INTO attendance_record(employee_id,attendance_date) VALUES(2,'2030-01-01')");
  s.executeUpdate("INSERT INTO leave_request(id,request_no,employee_id,leave_type,start_time,end_time,leave_hours,reason,status,current_approver_id) VALUES(1,'L_TEST',2,'PERSONAL','2030-01-01 09:00:00','2030-01-01 10:00:00',1,'虚构请假',1,1)");
  s.executeUpdate("INSERT INTO leave_approval_record(leave_request_id,approver_id,action,after_status) VALUES(1,2,'SUBMIT',1)");
  s.executeUpdate("INSERT INTO employee_profile(employee_id,email,version) VALUES(2,'synthetic@example.invalid',7)");
  s.executeUpdate("INSERT INTO employee_education(employee_id,school,degree,start_month,end_month) VALUES(2,'虚构学校','本科','2008-09','2012-06')");
  s.executeUpdate("INSERT INTO employee_experience(employee_id,company,start_month,end_month) VALUES(2,'虚构单位','2012-07','2019-01')");
  s.executeUpdate("INSERT INTO employee_skill(employee_id,name,proficiency) VALUES(2,'虚构技能','熟悉')");
  s.executeUpdate("INSERT INTO employee_certificate(employee_id,name,issuer,obtained_date) VALUES(2,'虚构证书','虚构机构','2020-01-01')");s.executeUpdate("ALTER TABLE employee AUTO_INCREMENT=900");s.executeUpdate("ALTER TABLE position_info AUTO_INCREMENT=700");s.close();
 }
 public static void main(String[] args)throws Exception{
  base=System.getenv("EMS_IT_URL");pw=System.getenv("EMS_IT_PASSWORD");Schema.safeUrl(base);Path work=Path.of(args[0]);
  try{
   for(String version:List.of("113","120")){
    String source=create("l"+version),target=create("t"+version);
    try(var c=db(source)){ScriptUtils.executeSqlScript(c,new EncodedResource(new ClassPathResource("legacy/"+version+".sql"),"UTF-8"));seed(c);}
    boolean refused=false;try{migrate(source);}catch(IllegalStateException e){refused=true;}check(refused,"Nonempty legacy"+version+" never auto-baselines");
    Path raw=work.resolve(version+".zip");
    try(var c=db(source)){Snapshot.write(c,raw);}
    Path encrypted=work.resolve(version+".emsbk"),plain=work.resolve(version+"-decoded.zip");
    BackupCrypto.encrypt(raw,encrypted,"SyntheticBackupPassword123".toCharArray());BackupCrypto.decrypt(encrypted,plain,"SyntheticBackupPassword123".toCharArray());
    Path staging=work.resolve("stage"+version);var manifest=Snapshot.inspect(plain,staging);check(manifest.path("tables").size()==17,"Complete17table backup"+version);
    migrate(target);migrate(target);
    try(var c=db(target)){Snapshot.restore(c,manifest,staging);Schema.relations(c);check(Schema.names(c).size()==19,"18business plus Flyway metadata"+version);
     try(var s=c.createStatement();var rs=s.executeQuery("SELECT actual_salary FROM salary_record WHERE employee_id=2")){rs.next();check(rs.getBigDecimal(1).toPlainString().equals("12345.67"),"Exact salary decimal"+version);}
     try(var s=c.createStatement();var rs=s.executeQuery("SELECT version FROM employee_profile WHERE employee_id=2")){rs.next();check(rs.getLong(1)==7,"Profile optimistic version retained"+version);}
     try(var s=c.createStatement();var rs=s.executeQuery("SELECT password_hash FROM user_account WHERE employee_id=1")){rs.next();check(new BCryptPasswordEncoder().matches("SyntheticPassword123",rs.getString(1)),"Existing BCrypt account retained"+version);}
     try(var statement=c.prepareStatement("SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=? AND table_name='employee'")){statement.setString(1,c.getCatalog());try(var rows=statement.executeQuery()){rows.next();check(rows.getLong(1)==900,"Employee auto increment gap retained"+version);}}
     try(var statement=c.prepareStatement("SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=? AND table_name='position_info'")){statement.setString(1,c.getCatalog());try(var rows=statement.executeQuery()){rows.next();check(rows.getLong(1)==700,"Deleted-position identifier reuse prevented"+version);}}
     try(var s=c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM account_active_session")){rs.next();check(rs.getInt(1)==0,"No session restored"+version);}
    }
    try(var c=db(target);var s=c.createStatement()){s.executeUpdate("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) VALUES(99,'99','future','SQL','V99__future.sql','synthetic',1,1)");}
    boolean futureRefused=false;try{migrate(target);}catch(IllegalStateException exception){futureRefused=true;}check(futureRefused,"Unknown future schema rejected"+version);

    try(var c=db(target);var statement=c.createStatement()){statement.executeUpdate("DELETE FROM flyway_schema_history WHERE installed_rank=99");statement.executeUpdate("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");}
    boolean checksumRefused=false;try{migrate(target);}catch(org.flywaydb.core.api.exception.FlywayValidateException exception){checksumRefused=true;}check(checksumRefused,"Executed migration checksum mismatch rejected"+version);
    String corrupt=create("l"+version);
    try(var c=db(corrupt)){
     ScriptUtils.executeSqlScript(c,new EncodedResource(new ClassPathResource("legacy/"+version+".sql"),"UTF-8"));seed(c);
     try(var statement=c.createStatement()){statement.executeUpdate("UPDATE employee SET user_type=5 WHERE id=2");}
     boolean invalidRole=false;try{Snapshot.write(c,work.resolve("invalid-role"+version+".zip"));}catch(IllegalArgumentException exception){invalidRole=true;}check(invalidRole,"Unknown employee role rejected"+version);
     try(var statement=c.createStatement()){statement.executeUpdate("UPDATE employee SET user_type=0 WHERE id=2");statement.execute("SET FOREIGN_KEY_CHECKS=0");statement.executeUpdate("UPDATE employee SET department_id=999 WHERE id=2");statement.execute("SET FOREIGN_KEY_CHECKS=1");}
     boolean orphan=false;try{Snapshot.write(c,work.resolve("invalid-relation"+version+".zip"));}catch(IllegalArgumentException exception){orphan=true;}check(orphan,"Orphan employee department rejected"+version);
     try(var statement=c.createStatement()){statement.executeUpdate("ALTER TABLE employee ADD unexpected_column INT NULL");}
     boolean unknownShape=false;try{Snapshot.write(c,work.resolve("invalid-shape"+version+".zip"));}catch(IllegalArgumentException exception){unknownShape=true;}check(unknownShape,"Unknown nonempty table shape rejected"+version);
    }
    try(var c=db(source);var s=c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM employee")){rs.next();check(rs.getInt(1)==2,"Legacy source data unchanged"+version);}
   }
   System.out.println("Upgrade database checks passed: "+count);
  }finally{for(String name:created){try(var c=DriverManager.getConnection(base,"root",pw);var s=c.createStatement()){if(!name.matches("ems_up_server_v120_[lt](113|120)[a-f0-9]{10}"))throw new IllegalStateException("Unsafe cleanup");s.executeUpdate("DROP DATABASE "+name);}}}
 }
}
