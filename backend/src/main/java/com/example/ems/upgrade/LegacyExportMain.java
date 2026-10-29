package com.example.ems.upgrade;
import java.sql.*;import java.nio.file.*;import java.util.*;
public final class LegacyExportMain {
 public static void main(String[] args){
  try{
   int port=Integer.parseInt(System.getenv("EMS_LEGACY_PORT"));String schema=System.getenv("EMS_LEGACY_SCHEMA");
   if(port<16506||port>16530||!Set.of("ems_db","ems_server_v120","ems_portable_v120","ems_dev_v120").contains(schema))throw new IllegalArgumentException("Unsafe legacy copy endpoint");
   Path root=Path.of(System.getenv("EMS_MAINT_ROOT")).toRealPath(),job=Path.of(System.getenv("EMS_MAINT_JOB")).toRealPath();
   if(!job.startsWith(root.resolve("data")))throw new IllegalArgumentException("Unsafe output");
   Path raw=job.resolve("legacy.zip");char[] pass=System.getenv("EMS_BACKUP_PASSWORD").toCharArray();
   try{
    try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true","root",System.getenv("EMS_LEGACY_PASSWORD"))){var m=Snapshot.write(c,raw);Snapshot.JSON.writeValue(job.resolve("legacy-manifest.json").toFile(),m);}
    BackupCrypto.encrypt(raw,job.resolve("input.emsbk"),pass);
   }finally{Files.deleteIfExists(raw);Arrays.fill(pass,'\0');}
   System.out.println("Legacy cold-copy logical export completed");
  }catch(Exception e){System.err.println("Legacy export failed ["+UUID.randomUUID()+"]: "+e.getClass().getSimpleName());System.exit(1);}
 }
}
