package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Identifies registered archive projections, not the physical SQL schema of a text export.
 * The sparse official demo describes serialized strings; target JDBC metadata remains
 * authoritative for the P0 typed bind/readback and relationship checks.
 */
public final class BackupVersionCatalog {
 public record Profile(String id,String programVersion,int schemaVersion,boolean serializedTextProjection,Map<String,Map<String,String>> columns){}
 static final Map<String,Profile> PROFILES=new LinkedHashMap<>();
 static {
  for(var source:CompatibilityCatalog.PROFILES.values()){
   var tables=new TreeMap<String,Map<String,String>>();source.tables().forEach((name,shape)->{
    var columns=new TreeMap<String,String>();shape.columns().forEach((field,column)->columns.put(field,column.type().replaceFirst("\\(.*$","")));tables.put(name,Collections.unmodifiableMap(columns));
   });PROFILES.put(source.id(),new Profile(source.id(),source.programVersion(),source.schemaVersion(),false,Collections.unmodifiableMap(tables)));
  }
  try(var stream=BackupVersionCatalog.class.getResourceAsStream("/db/compatibility/official-demo-120-sparse.json")){
   var descriptor=Snapshot.JSON.readTree(stream);PROFILES.put("official-demo-120-sparse",new Profile("official-demo-120-sparse",descriptor.path("programVersion").asText(),descriptor.path("schemaVersion").asInt(),true,description(descriptor.path("tables"),false)));
  }catch(Exception invalid){throw new ExceptionInInitializerError(invalid);}
 }
 private BackupVersionCatalog(){}
 static Map<String,Map<String,String>> description(JsonNode tables,boolean manifest){
  if(!tables.isObject())throw new IllegalArgumentException("Invalid archive table metadata");var result=new TreeMap<String,Map<String,String>>();
  tables.fields().forEachRemaining(entry->{
   if(!Schema.TABLES.contains(entry.getKey()))throw new IllegalArgumentException("Unregistered archive table");
   JsonNode columns=manifest?entry.getValue().path("columns"):entry.getValue();if(!columns.isObject()||columns.isEmpty())throw new IllegalArgumentException("Invalid archive column metadata");
   var fields=new TreeMap<String,String>();columns.fields().forEachRemaining(field->{if(!field.getValue().isTextual())throw new IllegalArgumentException("Invalid archive type declaration");fields.put(field.getKey(),field.getValue().asText());});result.put(entry.getKey(),Collections.unmodifiableMap(fields));
  });return Collections.unmodifiableMap(result);
 }
 public static Profile identify(JsonNode manifest){
  if(manifest.path("format").asInt()!=1)throw new IllegalArgumentException("Unknown archive format");var actual=description(manifest.path("tables"),true);int schema=manifest.path("schemaVersion").asInt();String version=manifest.path("programVersion").asText();
  var matches=PROFILES.values().stream().filter(p->p.schemaVersion()==schema&&p.columns().equals(actual)).toList();
  if(matches.isEmpty())throw new IllegalArgumentException("Unknown archive projection; explicit conversion registration required");
  var declared=matches.stream().filter(p->p.programVersion().equals(version)).toList();
  if(declared.size()==1)return declared.get(0);
  if(version.equals("legacy-unversioned")&&matches.size()==1&&matches.get(0).id().startsWith("legacy-"))return matches.get(0);
  throw new IllegalArgumentException("Archive version is ambiguous or contradicts its registered projection");
 }
}
