package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Authenticated, bounded companion reader. All identities and keys remain in private staging. */
public final class CompatibilityExtensions implements AutoCloseable {
 private final Path workspace;
 private final JsonNode descriptor,manifest;
 private final byte[] proofKey;
 private boolean closed;
 private CompatibilityExtensions(Path workspace,JsonNode descriptor,JsonNode manifest,byte[] key){this.workspace=workspace;this.descriptor=descriptor;this.manifest=manifest;this.proofKey=key;}
 public String batchId(){open();return descriptor.path("batchId").asText();}
 public String originalBaseSha256(){open();return descriptor.path("baseSha256").asText();}
 public Path tables(){open();return workspace.resolve("snapshot/tables");}
 JsonNode manifest(){open();return manifest;}
 String authenticate(String baseHash,String checkpoint)throws Exception{open();return CompatibilityArchive.proof(proofKey,batchId(),baseHash,checkpoint);}
 boolean verify(String baseHash,String checkpoint,String authentication)throws Exception{open();return CompatibilityArchive.verifyProof(proofKey,batchId(),baseHash,checkpoint,authentication);}
 private void open(){if(closed)throw new IllegalStateException("Companion already closed");}
 public static CompatibilityExtensions read(Path encrypted,Path workspace,char[] password)throws Exception{
  // CREATE_NEW semantics are required for every extracted path; never reuse an old staging tree.
  Files.createDirectory(workspace);boolean ready=false;byte[] key=null;
  try{
   Path zip=workspace.resolve("extensions.zip");BackupCrypto.decrypt(encrypted,zip,password);
   long total=0;Set<String> names=new HashSet<>();
   try(var source=new ZipFile(zip.toFile())){
    var entries=source.entries();while(entries.hasMoreElements()){
     var entry=entries.nextElement();String name=entry.getName();
     long bound=name.equals("descriptor.json")?65536:4L*1024*1024*1024;
     if(!Set.of("descriptor.json","snapshot.zip").contains(name)||!names.add(name)||entry.isDirectory()||entry.getSize()<0||entry.getSize()>bound)throw new IllegalArgumentException("Invalid companion entry");
     Snapshot.requireSpace(Files.getFileStore(workspace).getUsableSpace(),entry.getSize());
     try(var in=source.getInputStream(entry);var out=Files.newOutputStream(workspace.resolve(name),StandardOpenOption.CREATE_NEW)){
      byte[] buffer=new byte[65536];int count;long bytes=0;
      while((count=in.read(buffer))!=-1){bytes+=count;total+=count;if(bytes>bound||total>4L*1024*1024*1024+65536)throw new IllegalArgumentException("Companion exceeds extraction limits");out.write(buffer,0,count);}
      if(bytes!=entry.getSize())throw new IllegalArgumentException("Companion size mismatch");
     }
    }
   }
   if(names.size()!=2)throw new IllegalArgumentException("Incomplete companion");
   JsonNode d=Snapshot.JSON.readTree(workspace.resolve("descriptor.json").toFile());
   if(!d.isObject()||!CompatibilityArchive.FORMAT.equals(d.path("format").asText())||d.path("extensionVersion").asInt()!=1||!"1.2.1".equals(d.path("sourceVersion").asText())||!"1.2.0".equals(d.path("targetVersion").asText())||d.path("sourceSchemaVersion").asInt()!=3)throw new IllegalArgumentException("Unknown companion version");
   String batch=d.path("batchId").asText();
   if(!UUID.fromString(batch).toString().equals(batch)||!d.path("baseSha256").asText().matches("[a-f0-9]{64}")||!BackupCrypto.sha256(workspace.resolve("snapshot.zip")).equals(d.path("snapshotSha256").asText()))throw new IllegalArgumentException("Companion identity or snapshot mismatch");
   for(String side:List.of("source","target")){
    String id=side.equals("source")?"canonical-121":"canonical-120";
    if(!id.equals(d.path(side+"Profile").asText())||!CompatibilityCatalog.fingerprint(CompatibilityCatalog.profile(id)).equals(d.path(side+"StructureSha256").asText()))throw new IllegalArgumentException("Unregistered companion mapping");
   }
   key=Base64.getDecoder().decode(d.path("proofKey").asText());if(key.length!=32)throw new IllegalArgumentException("Invalid companion key");
   var manifest=Snapshot.inspect(workspace.resolve("snapshot.zip"),workspace.resolve("snapshot"));
   if(manifest.path("schemaVersion").asInt()!=3||manifest.path("tables").size()!=Schema.TABLES.size())throw new IllegalArgumentException("Incomplete companion source");
   ready=true;return new CompatibilityExtensions(workspace,d,manifest,key);
  }finally{if(!ready){if(key!=null)Arrays.fill(key,(byte)0);MaintenanceMain.removeStaging(workspace);}}
 }
 public void requireOriginalBase(Path base)throws Exception{open();if(!BackupCrypto.sha256(base).equals(originalBaseSha256()))throw new IllegalArgumentException("Base and companion are not an original pair; authenticated return proof required");}
 @Override public void close()throws Exception{if(closed)return;closed=true;Arrays.fill(proofKey,(byte)0);MaintenanceMain.removeStaging(workspace);}
 @Override public String toString(){return "CompatibilityExtensions[encrypted contents redacted]";}
}
