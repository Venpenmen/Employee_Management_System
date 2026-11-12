package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic authenticated archive model; actual SQL/runtime acceptance is separate. */
class ExtensionConflictMatrixTest {
 @TempDir Path work;
 final char[] password="SyntheticConflictMatrix91".toCharArray();
 final Map<String,ArrayNode> original=new LinkedHashMap<>(),returned=new LinkedHashMap<>();
 CompatibilityExtensions extension;ArrayNode baseline,heads;
 ObjectNode row(String table,String id){var r=Snapshot.JSON.createObjectNode();CompatibilityCatalog.profile("canonical-121").tables().get(table).columns().keySet().forEach(k->r.putNull(k));r.put("id",id);return r;}
 void add(String table,ObjectNode row){original.get(table).add(row);}
 ObjectNode person(String id,String role){var r=row("employee",id);r.put("employee_no","26000"+id);r.put("identity_number","SyntheticIdentity"+id);r.put("birth_date","1990-01-01");r.put("user_type",role);r.put("department_id","1");r.put("position_id","1");return r;}
 ObjectNode account(String id){var r=row("user_account",id);r.put("employee_id",id);r.put("password_hash","SyntheticHash"+id);r.put("account_status","1");r.put("failed_login_count","0");return r;}
 @BeforeEach void setup()throws Exception{
  CompatibilityCatalog.profile("canonical-121").tables().keySet().forEach(t->original.put(t,Snapshot.JSON.createArrayNode()));
  add("employee",person("1","2"));add("employee",person("2","0"));add("user_account",account("1"));add("user_account",account("2"));
  for(String table:Schema.TABLES.subList(17,22)){var r=row(table,"1");r.put("employee_id",table.equals("secondary_credential")||table.equals("security_notification")?"1":"2");
   if(table.equals("account_security")){r.put("frozen","0");r.put("temporary_state","NORMAL");}
   if(table.equals("secondary_credential"))r.put("password_hash","SyntheticSecondaryHash");
   if(table.equals("security_notification"))r.put("subject_employee_id","2");
   if(table.equals("password_change_request")){r.put("status","APPROVED");r.put("active_employee_id","2");}
   add(table,r);
  }
  var manifest=Snapshot.JSON.createObjectNode();manifest.put("format",1);manifest.put("schemaVersion",3);manifest.put("programVersion","1.2.1");var meta=manifest.putObject("tables");
  Path source=work.resolve("source.zip");try(var zip=new ZipOutputStream(Files.newOutputStream(source))){for(String table:CompatibilityCatalog.profile("canonical-121").tables().keySet()){byte[] bytes=Snapshot.JSON.writeValueAsBytes(original.get(table));CompatibilityArchive.add(zip,"tables/"+table+".json",bytes);var m=meta.putObject(table);var columns=m.putObject("columns");CompatibilityCatalog.profile("canonical-121").tables().get(table).columns().forEach((k,v)->columns.put(k,v.type().replaceFirst("\\(.*$","")));m.put("rows",original.get(table).size());m.put("sha256",BackupBundle.sha256(bytes));}CompatibilityArchive.add(zip,"manifest.json",Snapshot.JSON.writeValueAsBytes(manifest));}
  CompatibilityArchive.project(source,work,password);extension=CompatibilityExtensions.read(work.resolve("extensions.emsbk"),work.resolve("private-extension"),password);
  baseline=Snapshot.JSON.createArrayNode();for(String table:CompatibilityArchive.BASE_TABLES){returned.put(table,original.get(table).deepCopy());for(var r:original.get(table)){var h=baseline.addObject();h.put("table",table);h.put("id",r.path("id").asText());h.put("generation",UUID.randomUUID().toString());h.put("alive",true);}}heads=baseline.deepCopy();
 }
 @AfterEach void close()throws Exception{if(extension!=null)extension.close();Arrays.fill(password,'\0');}
 ExtensionReconciler.Plan preview()throws Exception{
  Path stage=work.resolve("returned-tables");Files.createDirectory(stage);var manifest=Snapshot.JSON.createObjectNode();manifest.put("format",1);manifest.put("schemaVersion",2);var tables=manifest.putObject("tables");
  Path raw=work.resolve("returned.zip"),base=work.resolve("returned.emsbk");
  try(var zip=new ZipOutputStream(Files.newOutputStream(raw))){for(String table:CompatibilityArchive.BASE_TABLES){byte[] bytes=Snapshot.JSON.writeValueAsBytes(returned.get(table));Files.write(stage.resolve(table+".json"),bytes);CompatibilityArchive.add(zip,"tables/"+table+".json",bytes);var m=tables.putObject(table);var columns=m.putObject("columns");CompatibilityCatalog.profile("canonical-120").tables().get(table).columns().forEach((k,v)->columns.put(k,v.type().replaceFirst("\\(.*$","")));m.put("rows",returned.get(table).size());m.put("sha256",BackupBundle.sha256(bytes));}CompatibilityArchive.add(zip,"manifest.json",Snapshot.JSON.writeValueAsBytes(manifest));}
  BackupCrypto.encrypt(raw,base,password);
  var anchor=Snapshot.JSON.createObjectNode();anchor.put("format","EMS-P1-LINEAGE-RECEIPT-1");anchor.put("batchId",extension.batchId());anchor.set("baseline",baseline);var receipt=Snapshot.JSON.createObjectNode();receipt.set("body",anchor);receipt.put("authentication",extension.authenticate(extension.originalBaseSha256(),LineageTracker.hash(anchor)));
  var body=Snapshot.JSON.createObjectNode();body.put("format","EMS-P1-RETURN-PROOF-1");body.put("batchId",extension.batchId());body.put("returnBaseSha256",BackupCrypto.sha256(base));body.set("receipt",receipt);body.set("heads",heads);var envelope=Snapshot.JSON.createObjectNode();envelope.set("body",body);envelope.put("authentication",extension.authenticate(BackupCrypto.sha256(base),LineageTracker.hash(body)));
  var verified=LineageTracker.verifyReturn(extension,base,envelope);return ExtensionReconciler.preview(extension,stage,manifest,verified,BackupCrypto.sha256(base));
 }
 @Test void deletedPersonIsNotRevivedOrReassignedToANewPerson()throws Exception{
  returned.get("employee").remove(1);returned.get("user_account").remove(1);returned.get("employee").add(person("3","0"));returned.get("user_account").add(account("3"));var plan=preview();assertFalse(plan.blocked());assertTrue(plan.issues().stream().allMatch(i->i.code().equals("DELETED")));assertEquals(1,plan.restore().values().stream().mapToInt(List::size).sum());assertEquals("secondary_credential",plan.restore().entrySet().stream().filter(e->!e.getValue().isEmpty()).findFirst().orElseThrow().getKey());
 }
 @Test void sameKeyWithDifferentGenerationIsBlocking()throws Exception{
  for(var head:heads)if(head.path("table").asText().equals("employee")&&head.path("id").asText().equals("2"))((ObjectNode)head).put("generation",UUID.randomUUID().toString());assertTrue(preview().blocked());
 }
 @Test void demotionRequiresSecondaryCredentialReinitialization()throws Exception{
  ((ObjectNode)returned.get("employee").get(0)).put("user_type","0");var plan=preview();assertEquals(1,plan.credentialsToReset());assertTrue(plan.restore().get("secondary_credential").isEmpty());assertFalse(plan.blocked());
 }
 @Test void independentSecurityRecordsDoNotOverwriteDepartmentChanges()throws Exception{
  ((ObjectNode)returned.get("employee").get(1)).put("department_id","9").put("position_id","10");var plan=preview();assertTrue(plan.issues().isEmpty());assertFalse(plan.restore().containsKey("employee"));assertEquals("9",returned.get("employee").get(1).path("department_id").asText());
 }
 @Test void missingAccountIsSkippedAndNeverRecreatedByExtensions()throws Exception{
  returned.get("user_account").remove(1);var plan=preview();assertTrue(plan.issues().stream().anyMatch(i->i.code().equals("ACCOUNT_MISSING")));assertTrue(plan.restore().get("account_security").isEmpty());assertFalse(plan.restore().containsKey("user_account"));
 }
 @Test void returnedLoginRestrictionsArePreservedAndResetEligibilityExpires()throws Exception{
  var account=(ObjectNode)returned.get("user_account").get(1);account.put("account_status","0");account.put("failed_login_count","3");account.put("locked_until","2099-01-01 00:00:00");var plan=preview();assertFalse(plan.restore().containsKey("user_account"));assertEquals("0",account.path("account_status").asText());var request=plan.restore().get("password_change_request").get(0);assertEquals("EXPIRED",request.path("status").asText());assertTrue(request.path("active_employee_id").isNull());
 }
}
