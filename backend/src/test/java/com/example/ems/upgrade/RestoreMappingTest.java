package com.example.ems.upgrade;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.sql.*;import java.nio.file.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RestoreMappingTest {
 @TempDir Path temp;
 @Test void exactDecimalNormalizationWithoutRounding(){
  var c=new RestoreMapping.Column(Types.DECIMAL,2,12,false,false,"0");
  assertEquals("5000.00",RestoreMapping.canonical("5000",c,"department","base_salary"));
  assertThrows(JobDiagnostics.Failure.class,()->RestoreMapping.canonical("0.001",c,"department","base_salary"));
  assertThrows(JobDiagnostics.Failure.class,()->RestoreMapping.canonical("10000000000",c,"department","base_salary"));
 }
 @Test void timestampRepresentationsPreservePrecision(){
  var c=new RestoreMapping.Column(Types.TIMESTAMP,6,26,false,false,"CURRENT_TIMESTAMP");
  assertEquals("2030-01-01 09:00:00.0",RestoreMapping.canonical("2030-01-01 09:00:00",c,"employee","create_time"));
  assertEquals("2030-01-01 09:00:00.0",RestoreMapping.canonical("2030-01-01T09:00",c,"employee","create_time"));
  assertThrows(JobDiagnostics.Failure.class,()->RestoreMapping.canonical("2030-01-01 09:00:00.0000001",c,"employee","create_time"));
 }
 @Test void unknownFieldsAndMissingReferencedIdentitiesFailClosed(){
  var id=new RestoreMapping.Column(Types.BIGINT,0,19,false,true,null);
  var value=new RestoreMapping.Column(Types.VARCHAR,0,50,false,false,null);
  var columns=Map.of("id",id,"name",value);
  assertThrows(JobDiagnostics.Failure.class,()->RestoreMapping.validate("employee",List.of("name"),columns));
  assertThrows(JobDiagnostics.Failure.class,()->RestoreMapping.validate("employee",List.of("id","unknown"),columns));
  assertDoesNotThrow(()->RestoreMapping.validate("employee_skill",List.of("name"),columns));
 }
 @Test void sqlDiagnosticsExposeCodesNotSqlValuesOrPaths()throws Exception{
  JobDiagnostics.phase("restore-data");String id=UUID.randomUUID().toString();
  var failure=new RuntimeException("password=SecretToken C:\\private\\data SELECT identity_number",new SQLException("INSERT secret phone 13999999999","23000",1062));
  var report=JobDiagnostics.describe(id,failure,"UNCHANGED");String json=Snapshot.JSON.writeValueAsString(report);
  assertEquals("DUPLICATE_DATA",report.get("code"));assertEquals("23000",report.get("sqlState"));assertEquals(1062,report.get("vendorCode"));
  for(String secret:List.of("SecretToken","private","13999999999","INSERT","SELECT"))assertFalse(json.contains(secret));
  Snapshot.JSON.writeValue(temp.resolve("failure.json").toFile(),report);
  assertEquals("DUPLICATE_DATA",JobDiagnostics.read(temp,id).get("code"));
 }
 @Test void tamperedDiagnosticCannotInjectPublicMessages()throws Exception{
  Files.writeString(temp.resolve("failure.json"),"{\"code\":\"DATABASE_REJECTED\",\"message\":\"secret-value\",\"phase\":\"C:/private\",\"sqlState\":\"password=foo\"}");
  String json=Snapshot.JSON.writeValueAsString(JobDiagnostics.read(temp,UUID.randomUUID().toString()));
  assertFalse(json.contains("secret-value"));assertFalse(json.contains("C:/private"));assertFalse(json.contains("password=foo"));
 }
 @Test void cleanupFailureDoesNotReplaceOriginalDiagnostic()throws Exception{
  Path blocked=temp.resolve("nonempty");Files.createDirectory(blocked);Files.writeString(blocked.resolve("keep.txt"),"synthetic");
  var primary=new SQLException("original","08006",2013);
  assertDoesNotThrow(()->MaintenanceMain.cleanup(blocked,null,primary));assertEquals(1,primary.getSuppressed().length);
  JobDiagnostics.phase("export-snapshot");assertEquals("DATABASE_UNAVAILABLE",JobDiagnostics.describe(UUID.randomUUID().toString(),primary,"UNCHANGED").get("code"));
 }
}
