package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Builds a proposed recovery generation. It never changes the active database pointer. */
final class CompatibilityRestore {
 private CompatibilityRestore(){}
 static void preview(String sourceUrl,String user,String password,Path job,char[] archivePassword)throws Exception{
  Schema.safeUrl(sourceUrl);boolean hasExtension=Files.isRegularFile(job.resolve("extensions.emsbk")),hasProof=Files.isRegularFile(job.resolve("return-proof.emsbk"));
  if(hasProof&&!hasExtension)throw new IllegalArgumentException("回程证明必须与原扩展包一起提供");
  var originalInputs=ConfirmationBinding.inputHashes(job);
  Path work=job.resolve("compatibility-"+UUID.randomUUID());Files.createDirectory(work);Exception primary=null;
  try{
   Path raw=work.resolve("input.zip"),stage=work.resolve("input");JobDiagnostics.phase("decrypt");BackupCrypto.decrypt(job.resolve("input.emsbk"),raw,archivePassword);JobDiagnostics.phase("inspect");JsonNode manifest=Snapshot.inspect(raw,stage);var profile=BackupVersionCatalog.identify(manifest);
   if(hasExtension){
    try(var extension=CompatibilityExtensions.read(job.resolve("extensions.emsbk"),work.resolve("extensions"),archivePassword)){
     if(hasProof){
      JobDiagnostics.phase("lineage");var proof=LineageTracker.readEncrypted(job.resolve("return-proof.emsbk"),work.resolve("proof"),archivePassword);var verified=LineageTracker.verifyReturn(extension,job.resolve("input.emsbk"),proof);
      var plan=ExtensionReconciler.preview(extension,stage.resolve("tables"),manifest,verified,BackupCrypto.sha256(job.resolve("input.emsbk")));
      if(plan.blocked()){write(job,manifest,profile.id(),"VERIFIED_RETURN",null,null,plan.publicSummary(),false,plan.issues().size(),true,originalInputs);return;}
      restore(sourceUrl,user,password,job,manifest,stage,profile.id(),"VERIFIED_RETURN",plan,false,originalInputs);
     }else{
      extension.requireOriginalBase(job.resolve("input.emsbk"));
      restore(sourceUrl,user,password,job,extension.manifest(),extension.tables().getParent(),profile.id(),"ORIGINAL_PAIR",null,false,originalInputs);
     }
    }
   }else restore(sourceUrl,user,password,job,manifest,stage,profile.id(),manifest.path("schemaVersion").asInt()<3?"BASE_ONLY":"CURRENT",null,manifest.path("schemaVersion").asInt()<3,originalInputs);
  }catch(Exception failure){
   primary=failure;Path planned=job.resolve("target-plan.json");
   if(Files.exists(planned))try{JsonNode record=Snapshot.JSON.readTree(planned.toFile());Snapshot.JSON.writeValue(job.resolve("failed-target.json").toFile(),Map.of("target",record.path("target").asText(),"state","FAILED_RETAINED","reason","Never activate an incomplete recovery generation"));}catch(Exception diagnostic){failure.addSuppressed(diagnostic);}
   throw failure;
  }finally{try{MaintenanceMain.removeStaging(work);}catch(Exception cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
 }
 private static void restore(String sourceUrl,String user,String password,Path job,JsonNode manifest,Path stage,String profile,String mode,ExtensionReconciler.Plan plan,boolean baseOnly,Map<String,String> inputs)throws Exception{
  RecoveryTarget allocation;
  try(var source=DriverManager.getConnection(sourceUrl,user,password)){
   JobDiagnostics.phase("create-target");allocation=RecoveryTarget.allocate(source,target->Files.write(job.resolve("target-plan.json"),Snapshot.JSON.writeValueAsBytes(Map.of("target",target,"state","PLANNED")),StandardOpenOption.CREATE_NEW));
  }
  JobDiagnostics.phase("migrate-target");Schema.migrate(allocation.url(),user,password);Map<String,Object> mapping;int restored=0;
  try(var target=DriverManager.getConnection(allocation.url(),user,password)){
   JobDiagnostics.phase("restore-data");mapping=Snapshot.restore(target,manifest,stage);
   // The inspect grant permits a proposed isolated result. No skipped choice is
   // committed to the running installation until report-bound confirmation.
   if(plan!=null)restored=ExtensionReconciler.applyIsolated(target,plan,plan.previewSha256(),true,allocation);
  }
  Map<String,Object> summary;
  if(plan!=null){summary=new LinkedHashMap<>(plan.publicSummary());summary.put("restoredInPreview",restored);}
  else{long extensions=0;for(String name:Schema.TABLES.subList(17,22))extensions+=manifest.path("tables").path(name).path("rows").asLong();summary=Map.of("restoredInPreview",extensions,"conflicts",Map.of(),"blocking",false);}
  write(job,manifest,profile,mode,allocation.name(),mapping,summary,baseOnly,plan==null?0:plan.issues().size(),false,inputs);
 }
 private static void write(Path job,JsonNode manifest,String profile,String mode,String target,Map<String,Object> mapping,Map<String,Object> summary,boolean baseOnly,int conflicts,boolean blocking,Map<String,String> originalInputs)throws Exception{
  if(!originalInputs.equals(ConfirmationBinding.inputHashes(job)))throw new IllegalArgumentException("校验期间输入文件发生变化，必须重新上传");
  ObjectNode result=Snapshot.JSON.createObjectNode();if(target!=null)result.put("target",target);result.set("tables",manifest.path("tables"));result.put("state",blocking?"CONFLICTS_BLOCKING":"PREVIEW_READY");result.put("programVersion",manifest.path("programVersion").asText());result.put("schemaVersion",manifest.path("schemaVersion").asInt());result.put("identifiedProfile",profile);result.put("recoveryMode",mode);result.put("blocking",blocking);result.put("baseOnlyRequired",baseOnly);result.put("skippableConflicts",conflicts);result.set("extensions",Snapshot.JSON.valueToTree(summary));if(mapping!=null)result.set("mapping",Snapshot.JSON.valueToTree(mapping));
  JobDiagnostics.phase("confirmation");Files.write(job.resolve("result.json"),Snapshot.JSON.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);ConfirmationBinding.create(job,baseOnly,conflicts,blocking,Instant.now());
 }
}
