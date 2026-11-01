package com.example.ems.upgrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CompatibilityArchiveTest {
 @TempDir Path work;
 Path snapshot(String state,int frozen)throws Exception{
  var manifest=Snapshot.JSON.createObjectNode();manifest.put("format",1);manifest.put("schemaVersion",3);manifest.put("programVersion","1.2.1");var tables=manifest.putObject("tables");
  Path path=work.resolve("source.zip");try(var zip=new ZipOutputStream(Files.newOutputStream(path))){for(String table:Schema.TABLES){byte[] data=(table.equals("account_security")?"[{\"frozen\":\""+frozen+"\",\"temporary_state\":\""+state+"\"}]":"[]").getBytes(java.nio.charset.StandardCharsets.UTF_8);CompatibilityArchive.add(zip,"tables/"+table+".json",data);var meta=tables.putObject(table);var columns=meta.putObject("columns");CompatibilityCatalog.profile("canonical-121").tables().get(table).columns().forEach((name,column)->columns.put(name,column.type().replaceFirst("\\(.*$","")));meta.put("rows",table.equals("account_security")?1:0);meta.put("sha256",BackupBundle.sha256(data));}CompatibilityArchive.add(zip,"manifest.json",Snapshot.JSON.writeValueAsBytes(manifest));}return path;
 }
 @Test void archiveProjectionEncryptsBothPayloadsAndBindsTheirHashes()throws Exception{
  Path source=snapshot("NORMAL",0);char[] password="SyntheticCompatibilityPassword9".toCharArray();CompatibilityArchive.project(source,work,password);
  Path decoded=work.resolve("base-decoded.zip");BackupCrypto.decrypt(work.resolve("base-v120.emsbk"),decoded,password);var manifest=Snapshot.inspect(decoded,work.resolve("base-validation"));assertEquals(2,manifest.path("schemaVersion").asInt());assertEquals(17,manifest.path("tables").size());
  Path ext=work.resolve("ext-decoded.zip");BackupCrypto.decrypt(work.resolve("extensions.emsbk"),ext,password);
  try(var zip=new ZipFile(ext.toFile());var in=zip.getInputStream(zip.getEntry("descriptor.json"))){var metadata=Snapshot.JSON.readTree(in);assertEquals(CompatibilityArchive.FORMAT,metadata.path("format").asText());assertEquals(BackupCrypto.sha256(work.resolve("base-v120.emsbk")),metadata.path("baseSha256").asText());assertEquals(BackupCrypto.sha256(source),metadata.path("snapshotSha256").asText());assertNotNull(zip.getEntry("snapshot.zip"));}
  assertFalse(Files.exists(work.resolve("base.zip")));assertFalse(Files.exists(work.resolve("extensions.zip")));assertFalse(Files.exists(work.resolve("source-validation")));
 }
 @Test void frozenAccountsBlockExportWithoutProducingPartialBackup()throws Exception{
  Path source=snapshot("NORMAL",1);assertThrows(IllegalArgumentException.class,()->CompatibilityArchive.project(source,work,"SyntheticCompatibilityPassword9".toCharArray()));assertFalse(Files.exists(work.resolve("base-v120.emsbk")));assertFalse(Files.exists(work.resolve("extensions.emsbk")));
 }
 @Test void temporaryCredentialsBlockExport()throws Exception{
  Path source=snapshot("REISSUE",0);assertThrows(IllegalArgumentException.class,()->CompatibilityArchive.project(source,work,"SyntheticCompatibilityPassword9".toCharArray()));assertFalse(Files.exists(work.resolve("base-v120.emsbk")));
 }
 @Test void legacyProjectionHasExactlyOriginalPersistentTables(){assertEquals(17,CompatibilityArchive.BASE_TABLES.size());assertFalse(CompatibilityArchive.BASE_TABLES.contains("account_security"));assertFalse(CompatibilityArchive.BASE_TABLES.contains("account_active_session"));assertFalse(CompatibilityArchive.BASE_TABLES.contains("security_grant"));}
 @Test void proofBindsBatchBaseAndCheckpoint()throws Exception{
  byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);String batch=UUID.randomUUID().toString(),base="a".repeat(64),checkpoint="b".repeat(64);
  String proof=CompatibilityArchive.proof(key,batch,base,checkpoint);
  assertTrue(CompatibilityArchive.verifyProof(key,batch,base,checkpoint,proof));
  assertFalse(CompatibilityArchive.verifyProof(key,UUID.randomUUID().toString(),base,checkpoint,proof));
  assertFalse(CompatibilityArchive.verifyProof(key,batch,"c".repeat(64),checkpoint,proof));
  assertFalse(CompatibilityArchive.verifyProof(key,batch,base,"d".repeat(64),proof));
  byte[] other=key.clone();other[0]^=1;assertFalse(CompatibilityArchive.verifyProof(other,batch,base,checkpoint,proof));
  assertFalse(CompatibilityArchive.verifyProof(key,batch,base,checkpoint,"not-a-proof"));
 }
 @Test void companionReaderRequiresExactPairAndErasesPrivateStaging()throws Exception{
  char[] password="SyntheticCompatibilityPassword9".toCharArray();CompatibilityArchive.project(snapshot("NORMAL",0),work,password);
  Path stage=work.resolve("private-read");
  try(var extension=CompatibilityExtensions.read(work.resolve("extensions.emsbk"),stage,password)){
   extension.requireOriginalBase(work.resolve("base-v120.emsbk"));assertTrue(Files.exists(extension.tables().resolve("account_security.json")));
   Path other=work.resolve("other.emsbk");Files.writeString(other,"unrelated encrypted input");assertThrows(IllegalArgumentException.class,()->extension.requireOriginalBase(other));
   assertFalse(extension.toString().contains(extension.batchId()));
  }
  assertFalse(Files.exists(stage));
 }
 @Test void wrongPasswordAndUnsafeEntriesLeaveNoExtractedSecrets()throws Exception{
  char[] password="SyntheticCompatibilityPassword9".toCharArray();CompatibilityArchive.project(snapshot("NORMAL",0),work,password);
  Path wrong=work.resolve("wrong-read");assertThrows(IllegalArgumentException.class,()->CompatibilityExtensions.read(work.resolve("extensions.emsbk"),wrong,"DifferentSyntheticPassword9".toCharArray()));assertFalse(Files.exists(wrong));
  Path malicious=work.resolve("unsafe.zip");try(var zip=new ZipOutputStream(Files.newOutputStream(malicious))){CompatibilityArchive.add(zip,"../sentinel",new byte[]{1});}
  Path encrypted=work.resolve("unsafe.emsbk");BackupCrypto.encrypt(malicious,encrypted,password);
  Path stage=work.resolve("unsafe-read");assertThrows(IllegalArgumentException.class,()->CompatibilityExtensions.read(encrypted,stage,password));assertFalse(Files.exists(stage));assertFalse(Files.exists(work.resolve("sentinel")));
 }
}
