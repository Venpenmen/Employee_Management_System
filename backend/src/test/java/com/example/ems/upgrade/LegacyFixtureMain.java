package com.example.ems.upgrade;
import java.sql.*;import org.springframework.jdbc.datasource.init.ScriptUtils;import org.springframework.core.io.*;import org.springframework.core.io.support.EncodedResource;
public final class LegacyFixtureMain {
 public static void main(String[] args)throws Exception{
  String base=System.getenv("EMS_IT_URL");Schema.safeUrl(base);
  if(!java.util.Set.of("113","120").contains(args[0]))throw new IllegalArgumentException("Fixture label invalid");
  String schema=args[0].equals("113")?"ems_db":"ems_server_v120";
  String password=System.getenv("EMS_IT_PASSWORD");
  try(var c=DriverManager.getConnection(base,"root",password);var s=c.createStatement()){s.executeUpdate("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
  try(var c=DriverManager.getConnection(base.replaceFirst("/ems_up_[^?]+","/"+schema),"root",password)){
   ScriptUtils.executeSqlScript(c,new EncodedResource(new ClassPathResource("legacy/"+args[0]+".sql"),"UTF-8"));UpgradeDatabaseIT.seed(c);try(var statement=c.createStatement()){statement.executeUpdate("UPDATE salary_record SET status=3,published_time=\'2030-02-01 00:00:00\'");}
  }
  System.out.println("Synthetic legacy"+args[0]+" database prepared");
 }
}
