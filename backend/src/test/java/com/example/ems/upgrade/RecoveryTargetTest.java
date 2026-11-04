package com.example.ems.upgrade;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryTargetTest {
 Connection connection(String database,int port)throws Exception{
  var c=mock(Connection.class);var meta=mock(DatabaseMetaData.class);when(c.getMetaData()).thenReturn(meta);when(meta.getURL()).thenReturn("jdbc:mysql://127.0.0.1:"+port+"/"+database+"?useSSL=false");when(c.getCatalog()).thenReturn(database);when(c.getAutoCommit()).thenReturn(true);return c;
 }
 @Test void capabilityRequiresSuccessfulCreationAndCannotSelectExistingDatabase()throws Exception{
  var source=connection("ems_next_server_v121",16606);var statement=mock(Statement.class);when(source.createStatement()).thenReturn(statement);var target=RecoveryTarget.allocate(source);
  assertTrue(target.name().matches("ems_next_server_v121_g[a-f0-9]{12}"));verify(statement).executeUpdate("CREATE DATABASE "+Snapshot.q(target.name())+" CHARACTER SET utf8mb4");assertThrows(IllegalArgumentException.class,()->target.require(source));target.require(connection(target.name(),16606));assertThrows(IllegalArgumentException.class,()->target.require(connection(target.name(),16607)));
 }
 @Test void failedAllocationDoesNotProduceAnActivationCapability()throws Exception{
  var source=connection("ems_next_server_v121",16606);var statement=mock(Statement.class);when(source.createStatement()).thenReturn(statement);when(statement.executeUpdate(anyString())).thenThrow(new SQLException("Synthetic creation failure","08S01"));assertThrows(SQLException.class,()->RecoveryTarget.allocate(source));verify(statement,never()).executeUpdate(startsWith("DROP"));
 }
 @Test void unsafeConnectionIsRejectedBeforeAnyDdl()throws Exception{
  var source=connection("ems_db",3306);assertThrows(IllegalArgumentException.class,()->RecoveryTarget.allocate(source));verify(source,never()).createStatement();
 }
}
