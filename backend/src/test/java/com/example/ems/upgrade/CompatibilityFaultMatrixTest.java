package com.example.ems.upgrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompatibilityFaultMatrixTest {
 @TempDir Path work;
 @Test void temporaryPasswordExplicitlyBlocksCompatibilityExport()throws Exception{
  var fixture=new CompatibilityArchiveTest();fixture.work=work;Path source=fixture.snapshot("TEMP",0);assertThrows(IllegalArgumentException.class,()->CompatibilityArchive.project(source,work,"SyntheticFaultPassword91".toCharArray()));assertFalse(Files.exists(work.resolve("base-v120.emsbk")));assertFalse(Files.exists(work.resolve("extensions.emsbk")));
 }
 @Test void insufficientSpaceFailsBeforeRestoreStaging(){assertThrows(IllegalArgumentException.class,()->Snapshot.requireSpace(256L*1024*1024-1,0));}
 @Test void missingCompanionNeverBecomesASuccessfulBaseOnlyRestore(){
  Path stage=work.resolve("missing-stage");assertThrows(Exception.class,()->CompatibilityExtensions.read(work.resolve("missing.emsbk"),stage,"SyntheticFaultPassword91".toCharArray()));assertFalse(Files.exists(stage));
 }
 @Test void unknownCompanionVersionIsRejectedWithoutFallback()throws Exception{
  var fixture=new CompatibilityArchiveTest();fixture.work=work;var password="SyntheticFaultPassword91".toCharArray();CompatibilityArchive.project(fixture.snapshot("NORMAL",0),work,password);
  Path raw=work.resolve("decoded.zip");BackupCrypto.decrypt(work.resolve("extensions.emsbk"),raw,password);Path changed=work.resolve("unknown.zip");
  try(var source=new ZipFile(raw.toFile());var target=new ZipOutputStream(Files.newOutputStream(changed))){var entries=source.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();try(var input=source.getInputStream(entry)){byte[] data=input.readAllBytes();if(entry.getName().equals("descriptor.json")){var descriptor=(com.fasterxml.jackson.databind.node.ObjectNode)Snapshot.JSON.readTree(data);descriptor.put("extensionVersion",999);data=Snapshot.JSON.writeValueAsBytes(descriptor);}CompatibilityArchive.add(target,entry.getName(),data);}}}
  Path encrypted=work.resolve("unknown.emsbk");BackupCrypto.encrypt(changed,encrypted,password);Path stage=work.resolve("unknown-stage");assertThrows(IllegalArgumentException.class,()->CompatibilityExtensions.read(encrypted,stage,password));assertFalse(Files.exists(stage));
 }
 @Test void databaseReadInterruptionPreservesOriginalFailureAndAttemptsAllCleanup()throws Exception{
  Connection connection=mock(Connection.class);DatabaseMetaData metadata=mock(DatabaseMetaData.class);Statement statement=mock(Statement.class);
  when(connection.getMetaData()).thenReturn(metadata);when(metadata.getURL()).thenReturn("jdbc:mysql://127.0.0.1:16606/ems_next_server_v121");when(connection.getAutoCommit()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);when(connection.createStatement()).thenReturn(statement);
  when(statement.executeQuery(anyString())).thenThrow(new SQLException("Synthetic connection interruption","08S01"));doThrow(new SQLException("Synthetic rollback interruption","08006")).when(connection).rollback();
  Path output=work.resolve("interrupted");SQLException failure=assertThrows(SQLException.class,()->CompatibilityArchive.export(connection,output,"SyntheticFaultPassword91".toCharArray()));assertEquals("08S01",failure.getSQLState());assertTrue(Arrays.stream(failure.getSuppressed()).anyMatch(e->e instanceof SQLException s&&"08006".equals(s.getSQLState())));verify(connection).setAutoCommit(true);verify(connection).setReadOnly(false);assertFalse(Files.exists(output.resolve("base-v120.emsbk")));assertFalse(Files.exists(output.resolve("source.zip")));
 }
}
