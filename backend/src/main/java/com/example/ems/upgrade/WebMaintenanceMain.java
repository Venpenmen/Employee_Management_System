package com.example.ems.upgrade;
import java.sql.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
/** Web jobs always require a current executive identity; missing credentials fail closed. */
public final class WebMaintenanceMain {
 public static void main(String[] args){
  try{
   String url=MaintenanceMain.env("EMS_MAINT_URL");Schema.safeUrl(url);
   try(var c=DriverManager.getConnection(url,MaintenanceMain.env("EMS_MAINT_USER"),MaintenanceMain.env("EMS_MAINT_PASSWORD"))){
    c.setAutoCommit(false);
    try(var s=c.prepareStatement("SELECT a.account_status,e.employment_status,e.user_type,x.token_hash FROM user_account a JOIN employee e ON e.id=a.employee_id JOIN account_active_session x ON x.employee_id=e.id WHERE e.id=? FOR UPDATE")){
     s.setLong(1,Long.parseLong(MaintenanceMain.env("EMS_MAINT_ACTOR_ID")));
     try(var rs=s.executeQuery()){if(!rs.next()||rs.getInt(1)!=1||rs.getInt(2)!=1||rs.getInt(3)!=2||!MessageDigest.isEqual(rs.getString(4).getBytes(StandardCharsets.US_ASCII),MaintenanceMain.env("EMS_MAINT_ACTOR_HASH").getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException("Executive session expired");}
    }c.commit();
   }
   if(!args[0].equals("verify-current"))MaintenanceMain.execute(args);
   System.out.println("Authorized web maintenance completed");
  }catch(Exception e){MaintenanceMain.diagnostic("Web maintenance",e);System.exit(1);}
 }
}
