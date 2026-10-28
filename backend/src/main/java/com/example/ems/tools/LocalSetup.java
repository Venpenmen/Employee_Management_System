package com.example.ems.tools;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public final class LocalSetup {
 private static String env(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalArgumentException("缺少必填信息，请重新执行初始化");return v;}
 private static void check(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
 private static LocalDate date(String key,String label){
  String value=env(key).trim();
  check(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),label+"格式错误，请使用 yyyy-MM-dd，例如 2000-01-01");
  try{return LocalDate.parse(value);}catch(DateTimeParseException e){throw new IllegalArgumentException(label+"不是有效日期，请检查年月日");}
 }
 public static void main(String[] args){
  try{setup();System.out.println("首次高层管理员账号创建成功。");}
  catch(IllegalArgumentException|IllegalStateException e){System.err.println("初始化失败："+e.getMessage());System.exit(1);}
  catch(Exception e){System.err.println("初始化失败：数据库操作未完成，请检查数据库状态和日志；未提交的数据已回滚。");System.exit(1);}
 }
 private static void setup() throws Exception{
  String no=env("EMS_SETUP_NO").trim(),name=env("EMS_SETUP_NAME").trim(),id=env("EMS_SETUP_ID").trim().toUpperCase(),phone=env("EMS_SETUP_PHONE").trim(),pass=env("EMS_SETUP_PASSWORD");
  check(no.matches("[0-9]{6}"),"员工工号必须是6位数字");
  check(!name.isBlank()&&name.length()<=50,"姓名不能为空且不能超过50个字符");
  check(id.matches("[0-9]{17}[0-9X]"),"身份证号必须为18位，最后一位可以是X");
  check(phone.matches("1[3-9][0-9]{9}"),"手机号必须为有效格式的11位中国大陆手机号");
  check(pass.length()>=8&&pass.length()<=72&&pass.matches("(?s).*[A-Za-z].*")&&pass.matches("(?s).*[0-9].*"),"密码必须为8～72位且同时包含英文字母和数字");
  LocalDate birth=date("EMS_SETUP_BIRTH","出生日期"),hire=date("EMS_SETUP_HIRE","入职日期");
  check(!birth.isAfter(LocalDate.now()),"出生日期不能晚于今天");
  check(!hire.isBefore(birth),"入职日期不能早于出生日期");
  try(Connection c=DriverManager.getConnection(env("EMS_SETUP_URL"),"ems_app",env("EMS_DB_PASSWORD"))){
   try(Statement s=c.createStatement();ResultSet lock=s.executeQuery("SELECT GET_LOCK('ems-first-setup',10)")){lock.next();if(lock.getInt(1)!=1)throw new IllegalStateException("初始化锁获取失败，请稍后重试");}
   c.setAutoCommit(false);
   try{
    try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM user_account")){rs.next();if(rs.getInt(1)!=0)throw new IllegalStateException("数据库中已有账号，禁止重复初始化");}
    long employee;
    try(PreparedStatement s=c.prepareStatement("INSERT INTO employee(employee_no,name,identity_number,birth_date,phone,user_type,hire_date,employment_status) VALUES(?,?,?,?,?,2,?,1)",Statement.RETURN_GENERATED_KEYS)){
     s.setString(1,no);s.setString(2,name);s.setString(3,id);s.setObject(4,birth);s.setString(5,phone);s.setObject(6,hire);s.executeUpdate();try(ResultSet keys=s.getGeneratedKeys()){keys.next();employee=keys.getLong(1);}
    }
    try(PreparedStatement s=c.prepareStatement("INSERT INTO user_account(employee_id,password_hash) VALUES(?,?)")){s.setLong(1,employee);s.setString(2,new BCryptPasswordEncoder().encode(pass));s.executeUpdate();}
    try(PreparedStatement s=c.prepareStatement("INSERT INTO operation_log(operator_id,operation_type,target_type,target_id,summary) VALUES(?,'INITIAL_SETUP','ACCOUNT',?,'本地主机首次设置')")){s.setLong(1,employee);s.setLong(2,employee);s.executeUpdate();}
    c.commit();
   }catch(Exception e){c.rollback();throw e;}
   finally{try(Statement s=c.createStatement()){s.execute("SELECT RELEASE_LOCK('ems-first-setup')");}}
  }
 }
}
