package com.example.ems.upgrade;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CompatibilityCatalogTest {
 @Test void registeredVersionsAreExplicit(){
  assertEquals(17,CompatibilityCatalog.profile("canonical-120").tables().size());assertEquals(22,CompatibilityCatalog.profile("canonical-121").tables().size());
  assertEquals("1.1.3",CompatibilityCatalog.profile("legacy-113").programVersion());assertThrows(IllegalArgumentException.class,()->CompatibilityCatalog.profile("future-999"));
 }
 @Test void columnConstraintsAndDefaultsAreRecorded(){
  var current=CompatibilityCatalog.profile("canonical-121");var employee=current.tables().get("employee");
  assertEquals("id",employee.primaryKey());assertTrue(employee.columns().get("id").generated());assertFalse(employee.columns().get("id").nullable());assertTrue(employee.columns().get("department_id").nullable());
  assertEquals("0",current.tables().get("department").columns().get("base_salary").defaultValue());assertTrue(employee.unique().contains("employee_no"));assertTrue(employee.foreign().contains("department_id->department.id"));
  assertEquals("INNODB",employee.engine());assertEquals("utf8mb4_0900_ai_ci",employee.collation());assertEquals("CURRENT_TIMESTAMP",employee.columns().get("update_time").onUpdate());assertEquals("ascii_bin",current.ephemeralTables().get("security_grant").columns().get("token_hash").collation());
 }
 @Test void foreignRulesArePartOfTheRegisteredShape(){
  var security=CompatibilityCatalog.profile("canonical-121").tables().get("account_security");
  assertEquals("delete=CASCADE;update=RESTRICT",security.foreignRules().get("employee_id->user_account.employee_id"));
 }
 @Test void sourceAndTargetShareTheExactBaseDefinitions(){
  var old=CompatibilityCatalog.profile("canonical-120");var current=CompatibilityCatalog.profile("canonical-121");
  old.tables().forEach((name,table)->assertEquals(table,current.tables().get(name),name));assertFalse(old.tables().containsKey("account_security"));
 }
 @Test void defaultNormalizationDoesNotChangeTextMeaning(){
  assertEquals("0",CompatibilityCatalog.defaultValue("0.00","DECIMAL(12,2)"));assertEquals("CURRENT_TIMESTAMP",CompatibilityCatalog.defaultValue("current_timestamp()","DATETIME"));
  assertEquals("01",CompatibilityCatalog.defaultValue("'01'","VARCHAR(10)"));assertEquals("Normal",CompatibilityCatalog.defaultValue("'Normal'","VARCHAR(10)"));
 }
 @Test void shapeFingerprintsAreStableAndVersionSpecific()throws Exception{
  assertEquals(CompatibilityCatalog.fingerprint(CompatibilityCatalog.profile("canonical-120")),CompatibilityCatalog.fingerprint(CompatibilityCatalog.profile("canonical-120")));
  assertNotEquals(CompatibilityCatalog.fingerprint(CompatibilityCatalog.profile("canonical-120")),CompatibilityCatalog.fingerprint(CompatibilityCatalog.profile("canonical-121")));
 }
 @Test void conversionsAreExplicitAndPreserveAllCommonFields(){
  var down=CompatibilityPlan.between("canonical-121","canonical-120");assertEquals(17,down.mappings().size());assertEquals(5,down.extensionTables().size());
  down.mappings().forEach((name,mapping)->{assertEquals(CompatibilityCatalog.profile("canonical-120").tables().get(name).columns().keySet(),mapping.exactColumns());assertTrue(mapping.targetDefaults().isEmpty());assertTrue(mapping.extensionColumns().isEmpty());});
  var upgrade=CompatibilityPlan.between("legacy-113","canonical-121");assertEquals(17,upgrade.mappings().size());assertEquals("SIGNED_INT_TO_BIGINT",upgrade.mappings().get("employee_profile").wideningRules().get("version"));
  assertThrows(IllegalArgumentException.class,()->CompatibilityPlan.between("canonical-121","future-999"));
 }
}
