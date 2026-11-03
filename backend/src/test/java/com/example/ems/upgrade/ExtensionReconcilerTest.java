package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionReconcilerTest {
 @TempDir Path work;
 ObjectNode head(String table,String id,String generation,boolean alive){var node=Snapshot.JSON.createObjectNode();node.put("table",table);node.put("id",id);node.put("generation",generation);node.put("alive",alive);return node;}
 @Test void duplicateOrUnknownLineageIsRejected(){
  var rows=Snapshot.JSON.createArrayNode();var row=head("employee","1",UUID.randomUUID().toString(),true);rows.add(row);rows.add(row.deepCopy());assertThrows(IllegalArgumentException.class,()->ExtensionReconciler.generations(rows));
  rows.removeAll();rows.add(head("unknown_table","1",UUID.randomUUID().toString(),true));assertThrows(IllegalArgumentException.class,()->ExtensionReconciler.generations(rows));
 }
 @Test void absentRowsNeverResurrectAndReusedKeysAreNotIdentities(){
  String generation=UUID.randomUUID().toString();var row=Snapshot.JSON.createObjectNode();row.put("id","1");var source=Map.of("1",row);var before=Map.<String,com.fasterxml.jackson.databind.JsonNode>of("employee:1",head("employee","1",generation,true));
  assertEquals("DELETED",ExtensionReconciler.continuity("employee","1",source,Map.of(),before,before));
  assertEquals("PRIMARY_KEY_REUSED",ExtensionReconciler.continuity("employee","1",source,source,before,Map.of("employee:1",head("employee","1",UUID.randomUUID().toString(),true))));
  assertEquals("TRACKING_INCOMPLETE",ExtensionReconciler.continuity("employee","1",source,source,before,Map.of()));
  assertEquals("SAME",ExtensionReconciler.continuity("employee","1",source,source,before,before));
 }
 @Test void changedStableIdentityCannotKeepExtensions(){
  var old=Snapshot.JSON.createObjectNode();old.put("employee_no","260001");old.put("identity_number","SyntheticIdentity");old.put("birth_date","2000-01-01");var current=old.deepCopy();current.put("identity_number","ChangedSyntheticIdentity");
  var heads=Map.<String,com.fasterxml.jackson.databind.JsonNode>of("employee:1",head("employee","1",UUID.randomUUID().toString(),true));
  assertEquals("IDENTITY_CHANGED",ExtensionReconciler.person("1",Map.of("1",old),Map.of("1",current),heads,heads));
 }
 @Test void previewDigestIncludesPrivateValuesConflictChoicesAndInputs()throws Exception{
  var row=Snapshot.JSON.createObjectNode();row.put("id","1");row.put("password_hash","SyntheticHashA");var values=Map.of("secondary_credential",List.of(row));
  String initial=ExtensionReconciler.planHash(values,List.of(),0,"a".repeat(64));row.put("password_hash","SyntheticHashB");assertNotEquals(initial,ExtensionReconciler.planHash(values,List.of(),0,"a".repeat(64)));
  assertNotEquals(initial,ExtensionReconciler.planHash(values,List.of(new ExtensionReconciler.Issue("secondary_credential","1","SECONDARY_REINITIALIZE",false)),1,"b".repeat(64)));
 }
 @Test void failedCreateDoesNotRemoveSomeoneElsesPlaintextSentinel()throws Exception{
  Path destination=work.resolve("receipt.emsbk"),sentinel=work.resolve("receipt.emsbk.json.partial");Files.writeString(sentinel,"Synthetic protected sentinel");
  assertThrows(FileAlreadyExistsException.class,()->LineageTracker.writeEncrypted(Snapshot.JSON.createObjectNode(),destination,"SyntheticPassword91".toCharArray()));assertEquals("Synthetic protected sentinel",Files.readString(sentinel));assertFalse(Files.exists(destination));
 }
 @Test void duplicateAccountOwnershipAndOversizedIdsAreRejected()throws Exception{
  var first=Snapshot.JSON.createObjectNode();first.put("employee_id","1");var second=first.deepCopy();assertThrows(IllegalArgumentException.class,()->ExtensionReconciler.accountsByEmployee(Map.of("1",first,"2",second)));
  Path file=work.resolve("rows.json");Files.writeString(file,"[{\"id\":\"1\"},{\"id\":\"1\"}]");assertThrows(IllegalArgumentException.class,()->ExtensionReconciler.rows(file,"id"));
 }
 @Test void signedIdentitiesAreExactAndOverflowIsRefused(){assertTrue(ExtensionReconciler.validId("-9223372036854775808"));assertTrue(ExtensionReconciler.validId("9223372036854775807"));assertFalse(ExtensionReconciler.validId("9223372036854775808"));assertFalse(ExtensionReconciler.validId("01"));}
 @Test void decryptionDoesNotDeleteAnExistingStagingSentinel()throws Exception{
  Path sentinel=work.resolve("existing.json");Files.writeString(sentinel,"Protected synthetic sentinel");assertThrows(FileAlreadyExistsException.class,()->LineageTracker.readEncrypted(work.resolve("missing.emsbk"),sentinel,"SyntheticPassword91".toCharArray()));assertEquals("Protected synthetic sentinel",Files.readString(sentinel));
 }
}
