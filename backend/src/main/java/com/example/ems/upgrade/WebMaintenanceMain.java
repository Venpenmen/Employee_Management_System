package com.example.ems.upgrade;
import java.sql.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
/** Web jobs always require a current executive identity; missing credentials fail closed. */
public final class WebMaintenanceMain {
 public static void main(String[] args){
  try{
   String url=MaintenanceMain.env("EMS_MAINT_URL");Schema.safeUrl(url);
   try(var c=DriverManager.getConnection(url,MaintenanceMain.env("EMS_MAINT_USER"),MaintenanceMain.env("EMS_MAINT_PASSWORD"))){
    c.setAutoCommit(false);
    MaintenanceAuthorization.require(c,Long.parseLong(MaintenanceMain.env("EMS_MAINT_ACTOR_ID")),MaintenanceMain.env("EMS_MAINT_ACTOR_HASH"));c.commit();
   }
   if(!args[0].equals("verify-current"))MaintenanceMain.execute(args);
   System.out.println("Authorized web maintenance completed");
  }catch(Exception e){MaintenanceMain.diagnostic("Web maintenance",e);System.exit(1);}
 }
}
