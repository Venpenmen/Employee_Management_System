package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** A confirmation binds the private report, exact encrypted inputs, expiry and decisions. */
final class ConfirmationBinding {
 private static final Set<String> INPUTS=Set.of("input.emsbk","extensions.emsbk","return-proof.emsbk");
 private ConfirmationBinding(){}
 static Map<String,String> inputHashes(Path job)throws Exception{
  var result=new TreeMap<String,String>();for(String name:INPUTS)if(Files.exists(job.resolve(name))){if(!Files.isRegularFile(job.resolve(name),LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Invalid recovery input");result.put(name,BackupCrypto.sha256(job.resolve(name)));}
  if(!result.containsKey("input.emsbk"))throw new IllegalArgumentException("Recovery input missing");return result;
 }
 static JsonNode create(Path job,boolean baseOnlyRequired,int skippableConflicts,boolean blocking,Instant now)throws Exception{
  if(skippableConflicts<0)throw new IllegalArgumentException("Invalid conflict count");
  var record=Snapshot.JSON.createObjectNode();record.put("format",1);record.put("reportSha256",BackupCrypto.sha256(job.resolve("result.json")));record.set("inputSha256",Snapshot.JSON.valueToTree(inputHashes(job)));record.put("expiresAt",now.plus(Duration.ofMinutes(15)).toEpochMilli());record.put("baseOnlyRequired",baseOnlyRequired);record.put("skippableConflicts",skippableConflicts);record.put("blocking",blocking);
  Files.write(job.resolve("confirmation.json"),Snapshot.JSON.writeValueAsBytes(record),StandardOpenOption.CREATE_NEW);return record;
 }
 static JsonNode validate(Path job,boolean acknowledgeBaseOnly,boolean skipConflicts,Instant now)throws Exception{
  JsonNode record=Snapshot.JSON.readTree(job.resolve("confirmation.json").toFile());
  if(record.path("format").asInt()!=1||record.path("blocking").asBoolean()||record.path("expiresAt").asLong()<=now.toEpochMilli())throw new IllegalArgumentException("预览过期或存在安全冲突，请重新校验");
  if(!record.path("reportSha256").asText().equals(BackupCrypto.sha256(job.resolve("result.json")))||!record.path("inputSha256").equals(Snapshot.JSON.valueToTree(inputHashes(job))))throw new IllegalArgumentException("输入文件或预览报告已改变，请重新校验");
  if(record.path("baseOnlyRequired").asBoolean()&&!acknowledgeBaseOnly)throw new IllegalArgumentException("缺少扩展或血缘证明，必须明确确认仅基础升级，不能标为无损恢复");
  if(record.path("skippableConflicts").asInt()>0&&!skipConflicts)throw new IllegalArgumentException("必须取消或明确确认跳过列出的冲突扩展");
  if(skipConflicts&&record.path("skippableConflicts").asInt()==0)throw new IllegalArgumentException("跳过选择与预览不符");
  return record;
 }
 static String object(String id,JsonNode receipt,boolean acknowledgeBaseOnly,boolean skipConflicts)throws Exception{
  if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid recovery job");
  return id+":"+LineageTracker.hash(receipt)+":"+(acknowledgeBaseOnly?"BASE":"FULL")+":"+(skipConflicts?"SKIP":"ALL");
 }
}
