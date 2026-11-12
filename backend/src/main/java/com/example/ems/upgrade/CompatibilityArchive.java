package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.Connection;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** P1 archive core. Deliberately not exposed through the web until old-runtime acceptance passes. */
public final class CompatibilityArchive {
 public static final String FORMAT="EMS-COMPATIBILITY-EXTENSIONS-1";
 static final List<String> BASE_TABLES=List.copyOf(Schema.TABLES.subList(0,17));
 private CompatibilityArchive(){}
 public static void export(Connection source,Path workspace,char[] password)throws Exception{
  Schema.safeUrl(source.getMetaData().getURL());
  if(!source.getAutoCommit())throw new IllegalArgumentException("Compatibility export requires a dedicated connection");
  Files.createDirectory(workspace);
  Path snapshot=workspace.resolve("source.zip");
  int isolation=source.getTransactionIsolation();boolean readOnly=source.isReadOnly();
  Exception primary=null;
  try{
   source.setReadOnly(true);source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);source.setAutoCommit(false);
   // Metadata read locks are held until the snapshot commits; application DML may continue.
   for(String table:Schema.TABLES)try(var st=source.createStatement();var rows=st.executeQuery("SELECT 1 FROM "+Snapshot.q(table)+" WHERE 1=0")){}
   if(!CompatibilityCatalog.identify(source).id().equals("canonical-121"))throw new IllegalArgumentException("Compatibility export requires the registered current structure");
   if(LineageTracker.triggers(source).size()!=0)throw new IllegalArgumentException("Unregistered source triggers; compatibility conversion refused");
   for(String table:BASE_TABLES)if(!table.equals("employee_profile"))try(var st=source.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM "+Snapshot.q(table)+" WHERE id=0")){rows.next();if(rows.getLong(1)!=0)throw new IllegalArgumentException("Legacy importer cannot preserve a zero auto-increment identity");}
   Snapshot.write(source,snapshot);project(snapshot,workspace,password);
  }catch(Exception failure){primary=failure;throw failure;}finally{
   // Every cleanup is attempted; a reset error must not strand plaintext or hide the cause.
   Exception cleanup=null;
   for(CheckedCleanup action:List.<CheckedCleanup>of(source::rollback,()->source.setAutoCommit(true),()->source.setTransactionIsolation(isolation),()->source.setReadOnly(readOnly),()->Files.deleteIfExists(snapshot))){
    try{action.run();}catch(Exception failure){if(cleanup==null)cleanup=failure;else cleanup.addSuppressed(failure);}
   }
   if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
  }
 }
 @FunctionalInterface interface CheckedCleanup{void run()throws Exception;}
 static void project(Path snapshot,Path workspace,char[] password)throws Exception{
  Path stage=workspace.resolve("source-validation"),base=workspace.resolve("base.zip"),extension=workspace.resolve("extensions.zip");
  try{
   JsonNode manifest=Snapshot.inspect(snapshot,stage);
   if(!BackupVersionCatalog.identify(manifest).id().equals("canonical-121"))throw new IllegalArgumentException("Compatibility export requires the registered current archive projection");
   if(manifest.path("schemaVersion").asInt()!=3)throw new IllegalArgumentException("P1 requires a validated current-schema snapshot");
   if(!manifest.path("tables").isObject()||manifest.path("tables").size()!=CompatibilityCatalog.profile("canonical-121").tables().size())throw new IllegalArgumentException("P1 requires complete persistent tables");
   try(var parser=Snapshot.JSON.getFactory().createParser(stage.resolve("tables/account_security.json").toFile())){
    if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw new IllegalArgumentException("Invalid security records");
    while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){JsonNode row=Snapshot.JSON.readTree(parser);if(row==null)throw new IllegalArgumentException("Invalid security records");if(row.path("frozen").asInt()!=0||!"NORMAL".equals(row.path("temporary_state").asText()))throw new IllegalArgumentException("Compatibility export blocked by frozen or temporary-password accounts");}
   }
   ObjectNode old=manifest.deepCopy();old.put("programVersion","1.2.0");old.put("buildId","v1.2.0-rc1");old.put("schemaVersion",2);old.put("sourceKind","flyway");
   ObjectNode oldTables=Snapshot.JSON.createObjectNode();for(String table:BASE_TABLES)oldTables.set(table,manifest.path("tables").path(table));old.set("tables",oldTables);
   try(var zip=new ZipOutputStream(Files.newOutputStream(base,StandardOpenOption.CREATE_NEW))){for(String table:BASE_TABLES)add(zip,"tables/"+table+".json",stage.resolve("tables/"+table+".json"));add(zip,"manifest.json",Snapshot.JSON.writeValueAsBytes(old));}
   BackupCrypto.encrypt(base,workspace.resolve("base-v120.emsbk"),password);
   byte[] key=new byte[32];new SecureRandom().nextBytes(key);
   try{
    ObjectNode descriptor=Snapshot.JSON.createObjectNode();descriptor.put("format",FORMAT);descriptor.put("extensionVersion",1);descriptor.put("batchId",UUID.randomUUID().toString());descriptor.put("sourceVersion","1.2.1");descriptor.put("targetVersion","1.2.0");descriptor.put("sourceSchemaVersion",3);descriptor.put("baseSha256",BackupCrypto.sha256(workspace.resolve("base-v120.emsbk")));descriptor.put("snapshotSha256",BackupCrypto.sha256(snapshot));descriptor.put("proofKey",Base64.getEncoder().encodeToString(key));descriptor.put("createdAt",java.time.Instant.now().toString());
    descriptor.put("sourceProfile","canonical-121");descriptor.put("sourceStructureSha256",CompatibilityCatalog.fingerprint(CompatibilityCatalog.profile("canonical-121")));descriptor.put("targetProfile","canonical-120");descriptor.put("targetStructureSha256",CompatibilityCatalog.fingerprint(CompatibilityCatalog.profile("canonical-120")));
    // Baselines, persistent security data and future lineage anchors remain inside the encrypted snapshot.
    try(var zip=new ZipOutputStream(Files.newOutputStream(extension,StandardOpenOption.CREATE_NEW))){add(zip,"descriptor.json",Snapshot.JSON.writeValueAsBytes(descriptor));add(zip,"snapshot.zip",snapshot);}
    BackupCrypto.encrypt(extension,workspace.resolve("extensions.emsbk"),password);
   }finally{Arrays.fill(key,(byte)0);}
   Snapshot.JSON.writeValue(workspace.resolve("conversion-report.json").toFile(),Map.of("format",FORMAT,"sourceVersion","1.2.1","targetVersion","1.2.0","baseTables",17,"persistentExtensionTables",5,"ephemeralCredentialsExported",false,"roundtripRequiresLineageProof",true));
  }catch(Exception error){Files.deleteIfExists(workspace.resolve("base-v120.emsbk"));Files.deleteIfExists(workspace.resolve("extensions.emsbk"));throw error;}
  finally{Files.deleteIfExists(base);Files.deleteIfExists(extension);MaintenanceMain.removeStaging(stage);}
 }
 static void add(ZipOutputStream zip,String name,Path file)throws IOException{zip.putNextEntry(new ZipEntry(name));Files.copy(file,zip);zip.closeEntry();}
 static void add(ZipOutputStream zip,String name,byte[] value)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(value);zip.closeEntry();}
 public static String proof(byte[] key,String batch,String baseHash,String checkpointHash)throws Exception{
  if(key.length!=32||!batch.matches("[a-f0-9-]{36}")||!baseHash.matches("[a-f0-9]{64}")||!checkpointHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid lineage proof input");
  Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(("EMS-P1-PROOF-1\n"+batch+"\n"+baseHash+"\n"+checkpointHash).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
 }
 public static boolean verifyProof(byte[] key,String batch,String baseHash,String checkpointHash,String proof)throws Exception{
  return proof!=null&&proof.matches("[a-f0-9]{64}")&&MessageDigest.isEqual(HexFormat.of().parseHex(proof(key,batch,baseHash,checkpointHash)),HexFormat.of().parseHex(proof));
 }
}
