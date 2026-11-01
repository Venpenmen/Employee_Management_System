package com.example.ems.upgrade;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Local companion tool. Never attaches to an installation without live managed ownership. */
public final class LegacyCopyMain {
 private LegacyCopyMain(){}
 static String env(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing controlled-copy input");return value;}
 static final class OldSnapshot extends ClassLoader implements AutoCloseable {
  final ZipFile jar;
  OldSnapshot(Path path)throws IOException{super(LegacyCopyMain.class.getClassLoader());jar=new ZipFile(path.toFile());}
  @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
   if(!name.startsWith("com.example.ems.upgrade."))return super.loadClass(name,resolve);
   synchronized(getClassLoadingLock(name)){
    Class<?> type=findLoadedClass(name);
    if(type==null){var entry=jar.getEntry("BOOT-INF/classes/"+name.replace('.','/')+".class");if(entry==null)throw new ClassNotFoundException(name);try(var in=jar.getInputStream(entry)){byte[] bytes=in.readAllBytes();type=defineClass(name,bytes,0,bytes.length);}catch(IOException error){throw new ClassNotFoundException(name,error);}}
    if(resolve)resolveClass(type);return type;
   }
  }
  @Override public InputStream getResourceAsStream(String name){try{var entry=jar.getEntry("BOOT-INF/classes/"+name);return entry==null?super.getResourceAsStream(name):jar.getInputStream(entry);}catch(IOException error){throw new UncheckedIOException(error);}}
  void restore(Connection connection,com.fasterxml.jackson.databind.JsonNode manifest,Path stage)throws Exception{loadClass("com.example.ems.upgrade.Snapshot").getMethod("restore",Connection.class,com.fasterxml.jackson.databind.JsonNode.class,Path.class).invoke(null,connection,manifest,stage);}
  @Override public void close()throws IOException{jar.close();}
 }
 public static void main(String[] args){
  try{execute(args);System.out.println("Controlled-copy operation completed");}
  catch(Exception error){if(error instanceof java.lang.reflect.InvocationTargetException invocation&&invocation.getCause() instanceof Exception cause)error=cause;MaintenanceMain.diagnostic("Controlled-copy",error);System.exit(1);}
 }
 static void execute(String[] args)throws Exception{
  if(args.length!=1||!Set.of("install","check","return").contains(args[0]))throw new IllegalArgumentException("Unknown controlled-copy operation");
  Path root=Path.of(env("EMS_CONTROLLED_ROOT")).toRealPath(),work=Path.of(env("EMS_COMPAT_WORK")).toRealPath();
  if(!work.equals(root.getParent().resolve("private").toRealPath())||Files.isSymbolicLink(work))throw new IllegalArgumentException("Invalid controlled-copy workspace");
  char[] password=env("EMS_BACKUP_PASSWORD").toCharArray();
  try(var connection=DriverManager.getConnection(env("EMS_IT_URL"),"root",env("EMS_IT_PASSWORD"))){
   LineageTracker.owned(connection);
   try(var extension=CompatibilityExtensions.read(work.resolve("extensions.emsbk"),work.resolve("read-"+UUID.randomUUID()),password)){
    if(args[0].equals("install")){
     // The supplied old binary is inside the hash-verified new copy, not an installed program.
     extension.requireOriginalBase(work.resolve("base-v120.emsbk"));
     Path raw=work.resolve("base.zip"),stage=work.resolve("base-stage");Exception primary=null;
     try{
      BackupCrypto.decrypt(work.resolve("base-v120.emsbk"),raw,password);var manifest=Snapshot.inspect(raw,stage);
      if(!BackupVersionCatalog.identify(manifest).id().equals("canonical-120"))throw new IllegalArgumentException("Not a registered old-version base");
      try(var old=new OldSnapshot(root.resolve("app/ems-1.2.0-rc1.jar"))){old.restore(connection,manifest,stage);}
      LineageTracker.install(connection,extension,work.resolve("lineage-receipt.emsbk"),password);
     }catch(Exception failure){primary=failure;throw failure;}finally{MaintenanceMain.cleanup(raw,stage,primary);}
    }else{
     var receipt=LineageTracker.readEncrypted(work.resolve("lineage-receipt.emsbk"),work.resolve("receipt-"+UUID.randomUUID()),password);
     if(args[0].equals("check")){
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
      try{for(String table:CompatibilityArchive.BASE_TABLES)try(var st=connection.createStatement();var rows=st.executeQuery("SELECT 1 FROM "+Snapshot.q(table)+" WHERE 1=0")){}CompatibilityCatalog.requireProfile(connection,"canonical-120");LineageTracker.validateReceipt(connection,extension,receipt);LineageTracker.snapshotHeads(connection,receipt.path("body").path("trackingSchema").asText());}finally{connection.rollback();connection.setAutoCommit(true);}
     }
     else LineageTracker.exportReturn(connection,extension,receipt,work.resolve("return-"+UUID.randomUUID()),password);
    }
   }
  }finally{Arrays.fill(password,'\0');}
 }
}
