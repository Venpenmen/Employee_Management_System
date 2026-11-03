package com.example.ems.upgrade;

import java.net.URI;
import java.sql.*;
import java.util.UUID;

/** Unserializable allocation capability: only a successful CREATE of a fresh generation
 * can produce it. Web form fields, manifests and existing database names cannot do so.
 */
final class RecoveryTarget {
 private final String name,url;
 private RecoveryTarget(String name,String url){this.name=name;this.url=url;}
 String name(){return name;}
 String url(){return url;}
 @FunctionalInterface interface BeforeCreate{void planned(String target)throws Exception;}
 static RecoveryTarget allocate(Connection source)throws Exception{return allocate(source,target->{});}
 static RecoveryTarget allocate(Connection source,BeforeCreate beforeCreate)throws Exception{
  String original=source.getMetaData().getURL();Schema.safeUrl(original);
  if(!source.getAutoCommit())throw new IllegalArgumentException("Allocate recovery targets on a dedicated connection");
  String current=source.getCatalog();if(!current.matches("ems_next_(server|portable|dev)_v121(?:_[a-z0-9]+)?"))throw new IllegalArgumentException("Invalid recovery source");
  String name=current.replaceFirst("_g[a-z0-9]+$","")+"_g"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  String target=original.replaceFirst("/ems_next_[^?]+","/"+name);Schema.safeUrl(target);
  beforeCreate.planned(name);
  try(var statement=source.createStatement()){statement.executeUpdate("CREATE DATABASE "+Snapshot.q(name)+" CHARACTER SET utf8mb4");}
  return new RecoveryTarget(name,target);
 }
 void require(Connection connection)throws SQLException{
  String actual=connection.getMetaData().getURL();Schema.safeUrl(actual);URI expected=URI.create(url.substring(5)),endpoint=URI.create(actual.substring(5));
  if(!name.equals(connection.getCatalog())||!expected.getHost().equals(endpoint.getHost())||expected.getPort()!=endpoint.getPort()||!expected.getPath().equals(endpoint.getPath()))throw new IllegalArgumentException("Recovery connection does not match its newly allocated target");
 }
 @Override public String toString(){return "RecoveryTarget[private isolated allocation]";}
}
