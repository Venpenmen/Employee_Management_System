package com.example.ems.upgrade;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.core.*;
import java.sql.*;import java.nio.file.*;import java.io.*;import java.util.*;import java.util.zip.*;import java.security.*;
public final class Snapshot {
 public static final ObjectMapper JSON=new ObjectMapper();
 public static String q(String s){if(!s.matches("[a-z_][a-z0-9_]*"))throw new IllegalArgumentException("Invalid identifier");return ((char)96)+s+((char)96);}
 static class KeepOpen extends FilterOutputStream{KeepOpen(OutputStream o){super(o);}public void close()throws IOException{flush();}}
 private static int sourceVersion(Connection c)throws SQLException{
  if(!Schema.names(c).contains("flyway_schema_history"))return 1;
  try(var s=c.createStatement();var rows=s.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){if(!rows.next())throw new IllegalArgumentException("Missing schema version");return Integer.parseInt(rows.getString(1));}
 }
 public static Map<String,Object> write(Connection c,Path out)throws Exception{
  return write(c,out,()->{});
 }
 @FunctionalInterface interface SnapshotBoundary{void capture()throws Exception;}
 static Map<String,Object> write(Connection c,Path out,SnapshotBoundary boundary)throws Exception{
  Schema.known(c);Schema.relations(c);boolean auto=c.getAutoCommit();
  if(auto){c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);}
  else if(c.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)throw new IllegalArgumentException("Snapshot transaction must use repeatable read");
  Map<String,Object> manifest=new LinkedHashMap<>(),tables=new LinkedHashMap<>();Exception primary=null;
  try(var zip=new ZipOutputStream(Files.newOutputStream(out,StandardOpenOption.CREATE_NEW))){
   for(String table:Schema.TABLES){if(!Schema.names(c).contains(table))continue;
    Map<String,String> columns=Schema.columns(c,table);String order=columns.containsKey("id")?"id":columns.containsKey("employee_id")?"employee_id":columns.keySet().iterator().next();
    zip.putNextEntry(new ZipEntry("tables/"+table+".json"));var hash=MessageDigest.getInstance("SHA-256");long count=0;
    var digest=new DigestOutputStream(new KeepOpen(zip),hash);
    try(var generator=JSON.getFactory().createGenerator(digest);var st=c.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
     st.setFetchSize(Integer.MIN_VALUE);generator.writeStartArray();
     try(var rows=st.executeQuery("SELECT * FROM "+q(table)+" ORDER BY "+q(order))){
      while(rows.next()){generator.writeStartObject();for(String col:columns.keySet()){Object value=rows.getObject(col);generator.writeFieldName(col);if(value==null)generator.writeNull();else if(value instanceof Boolean b)generator.writeString(b?"1":"0");else if(value instanceof java.math.BigDecimal n)generator.writeString(n.toPlainString());else if(value instanceof byte[])throw new IllegalArgumentException("Unsupported binary business value");else generator.writeString(value.toString());}generator.writeEndObject();count++;}
     }generator.writeEndArray();
    }zip.closeEntry();
    Map<String,Object> description=new LinkedHashMap<>();description.put("columns",columns);description.put("rows",count);description.put("sha256",HexFormat.of().formatHex(hash.digest()));
    var structure=new LinkedHashMap<String,Object>();
    for(String part:List.of("columns","primaryKeys","indexes","foreignKeys")){
     var details=new ArrayList<Map<String,Object>>();
     try(var meta=switch(part){case "columns"->c.getMetaData().getColumns(c.getCatalog(),null,table,"%");case "primaryKeys"->c.getMetaData().getPrimaryKeys(c.getCatalog(),null,table);case "indexes"->c.getMetaData().getIndexInfo(c.getCatalog(),null,table,false,false);default->c.getMetaData().getImportedKeys(c.getCatalog(),null,table);}){
      while(meta.next()){
       var fields=new LinkedHashMap<String,Object>();
       String[] keys=switch(part){case "columns"->new String[]{"COLUMN_NAME","TYPE_NAME","COLUMN_SIZE","DECIMAL_DIGITS","NULLABLE","COLUMN_DEF","IS_AUTOINCREMENT"};case "primaryKeys"->new String[]{"COLUMN_NAME","KEY_SEQ","PK_NAME"};case "indexes"->new String[]{"INDEX_NAME","NON_UNIQUE","COLUMN_NAME","ORDINAL_POSITION","TYPE"};default->new String[]{"FK_NAME","FKCOLUMN_NAME","PKTABLE_NAME","PKCOLUMN_NAME","UPDATE_RULE","DELETE_RULE"};};
       for(String key:keys){Object value=meta.getObject(key);fields.put(key,value==null?null:value.toString());}details.add(fields);
      }
     }structure.put(part,details);
    }
    try(var status=c.prepareStatement("SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=? AND table_name=?")){status.setString(1,c.getCatalog());status.setString(2,table);try(var row=status.executeQuery()){if(row.next()){Object next=row.getObject(1);description.put("autoIncrement",next==null?null:next.toString());}}}
    description.put("structure",structure);tables.put(table,description);
   }
   boolean versioned=Schema.names(c).contains("flyway_schema_history");int schemaVersion=sourceVersion(c);
   manifest.put("format",1);manifest.put("programVersion",versioned?(schemaVersion==2?"1.2.0":"1.2.1"):System.getenv().getOrDefault("EMS_SOURCE_VERSION","legacy-unversioned"));manifest.put("buildId",versioned?(schemaVersion==2?"v1.2.0-rc1":"v1.2.1-complete-rc1"):"legacy-unversioned");manifest.put("schemaVersion",schemaVersion);manifest.put("sourceKind",versioned?"flyway":"legacy-unversioned");manifest.put("tables",tables);
   zip.putNextEntry(new ZipEntry("manifest.json"));JSON.writeValue(new KeepOpen(zip),manifest);zip.closeEntry();boundary.capture();c.commit();return manifest;
  }catch(Exception e){primary=e;try{c.rollback();}catch(Exception rollback){e.addSuppressed(rollback);}MaintenanceMain.cleanup(out,null,e);throw e;}finally{try{if(!c.isClosed())c.setAutoCommit(auto);}catch(Exception cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
 }
 static void requireSpace(long available,long declared){if(available<declared*2+256L*1024*1024)throw new IllegalArgumentException("Insufficient free space for isolated restore");}
 public static JsonNode inspect(Path zipPath,Path staging)throws Exception{
  long declared=0;int files=0;
  try(var central=new ZipFile(zipPath.toFile())){var all=central.entries();while(all.hasMoreElements()){var e=all.nextElement();if(++files>Schema.TABLES.size()+1||e.getSize()<0)throw new IllegalArgumentException("Invalid backup size metadata");declared+=e.getSize();if(declared>4L*1024*1024*1024)throw new IllegalArgumentException("Backup exceeds parse limits");}}
  requireSpace(Files.getFileStore(staging.getParent()).getUsableSpace(),declared);
  Files.createDirectory(staging);Set<String> entries=new HashSet<>();long total=0;
  try(var zip=new ZipInputStream(Files.newInputStream(zipPath))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
   String name=entry.getName();if(!entries.add(name)||!(name.equals("manifest.json")||name.matches("tables/[a-z_]+\\.json"))||entry.isDirectory())throw new IllegalArgumentException("Unsafe backup entry");
   if(name.startsWith("tables/")&&!Schema.TABLES.contains(name.substring(7,name.length()-5)))throw new IllegalArgumentException("Unexpected business table");
   Path dest=staging.resolve(name);Files.createDirectories(dest.getParent());long bytes=0;try(var out=Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW)){byte[] b=new byte[65536];int n;while((n=zip.read(b))!=-1){bytes+=n;total+=n;if(total>4L*1024*1024*1024||(name.equals("manifest.json")&&bytes>1048576))throw new IllegalArgumentException("Backup exceeds parse limits");out.write(b,0,n);}}
  }}
  JsonNode m=JSON.readTree(staging.resolve("manifest.json").toFile());if(m.path("format").asInt()!=1||m.path("schemaVersion").asInt()<1||m.path("schemaVersion").asInt()>3)throw new IllegalArgumentException("Incompatible backup version");
  var fields=m.path("tables").fields();Set<String> present=new HashSet<>();
  while(fields.hasNext()){var e=fields.next();String table=e.getKey();if(!Schema.TABLES.contains(table))throw new IllegalArgumentException("Unexpected table");present.add(table);Path file=staging.resolve("tables/"+table+".json");var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}if(!HexFormat.of().formatHex(digest.digest()).equals(e.getValue().path("sha256").asText()))throw new IllegalArgumentException("Table hash mismatch");}
  if(!present.containsAll(Schema.TABLES.subList(0,12))||entries.size()!=present.size()+1||(m.path("schemaVersion").asInt()==3&&!present.containsAll(Schema.TABLES)))throw new IllegalArgumentException("Incomplete business backup");return m;
 }
 public static Map<String,Object> restore(Connection c,JsonNode m,Path staging)throws Exception{
  var names=Schema.names(c);if(!names.contains("flyway_schema_history"))throw new IllegalArgumentException("Target must be migrated first");
  if(!Schema.TABLES.stream().allMatch(t->names.contains(t)))throw new IllegalArgumentException("Target schema incomplete");
  try(var statement=c.createStatement();var existing=statement.executeQuery("SELECT COUNT(*) FROM employee")){existing.next();if(existing.getLong(1)!=0)throw new IllegalArgumentException("Restore target must have no employees");}
  var report=new LinkedHashMap<String,Object>();
  // Recheck authenticated file bytes before any target DML; never replace file integrity
  // with a semantic comparison or trust caller-supplied column type descriptions.
  for(String table:Schema.TABLES){var meta=m.path("tables").get(table);if(meta!=null&&!BackupCrypto.sha256(staging.resolve("tables/"+table+".json")).equals(meta.path("sha256").asText()))throw new JobDiagnostics.Failure("BACKUP_HASH_MISMATCH",table,"");}
  c.setAutoCommit(false);Exception primary=null;
  try(var st=c.createStatement()){
   st.execute("SET SESSION sql_mode=CONCAT_WS(',',@@SESSION.sql_mode,'STRICT_ALL_TABLES')");st.execute("SET FOREIGN_KEY_CHECKS=0");for(String t:Schema.TABLES)st.executeUpdate("DELETE FROM "+q(t));st.executeUpdate("DELETE FROM account_active_session");st.executeUpdate("DELETE FROM security_grant");
   for(String table:Schema.TABLES){var meta=m.path("tables").get(table);if(meta==null)continue;
    var columns=new ArrayList<String>();meta.path("columns").fieldNames().forEachRemaining(columns::add);var allowed=Schema.columns(c,table);
    var target=RestoreMapping.columns(c,table);RestoreMapping.validate(table,columns,target);
    String sql="INSERT INTO "+q(table)+"("+String.join(",",columns.stream().map(Snapshot::q).toList())+") VALUES("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";long count=0;
    String pk=table.equals("employee_profile")?"employee_id":"id";long generated=0,normalized=0;
    var digest=MessageDigest.getInstance("SHA-256");
    var projection="SELECT "+String.join(",",columns.stream().map(Snapshot::q).toList())+" FROM "+q(table)+" WHERE "+q(pk)+"=?";
    JobDiagnostics.phase("restore-data");
    try(var parser=JSON.getFactory().createParser(staging.resolve("tables/"+table+".json").toFile());var insert=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);var read=c.prepareStatement(projection);
        var stream=new DigestOutputStream(OutputStream.nullOutputStream(),digest);var verified=JSON.getFactory().createGenerator(stream)){
     if(parser.nextToken()!=JsonToken.START_ARRAY)throw new IllegalArgumentException("Invalid table data");
     verified.writeStartArray();
     while(parser.nextToken()!=JsonToken.END_ARRAY){JsonNode row=JSON.readTree(parser);if(row==null||!row.isObject()||row.size()!=columns.size())throw new IllegalArgumentException("Invalid row");
      var expected=new ArrayList<String>();
      for(int i=0;i<columns.size();i++){String col=columns.get(i);var v=row.get(col);if(v==null||(!v.isNull()&&!v.isTextual()))throw new JobDiagnostics.Failure("INVALID_VALUE",table,col);String original=v.isNull()?null:v.asText(),value=RestoreMapping.canonical(original,target.get(col),table,col);if(!Objects.equals(original,value))normalized++;expected.add(value);RestoreMapping.bind(insert,i+1,value,target.get(col));}
      insert.executeUpdate();count++;
      if(columns.contains(pk))read.setString(1,expected.get(columns.indexOf(pk)));
      else{try(var key=insert.getGeneratedKeys()){if(!key.next())throw new JobDiagnostics.Failure("REQUIRED_FIELD_MISSING",table,pk);read.setLong(1,key.getLong(1));generated++;}}
      JobDiagnostics.phase("verify-values");verified.writeStartObject();
      try(var actual=read.executeQuery()){
       if(!actual.next())throw new JobDiagnostics.Failure("RESTORE_VALUE_MISMATCH",table,pk);
       for(int i=0;i<columns.size();i++){String col=columns.get(i);String value=RestoreMapping.canonical(actual.getString(i+1),target.get(col),table,col);if(!Objects.equals(expected.get(i),value))throw new JobDiagnostics.Failure("RESTORE_VALUE_MISMATCH",table,col);verified.writeFieldName(col);if(value==null)verified.writeNull();else verified.writeString(value);}
       if(actual.next())throw new JobDiagnostics.Failure("DUPLICATE_DATA",table,pk);
      }verified.writeEndObject();JobDiagnostics.phase("restore-data");
     }if(parser.nextToken()!=null||count!=meta.path("rows").asLong(-1))throw new IllegalArgumentException("Row count mismatch");
     verified.writeEndArray();
    }
    try(var rows=st.executeQuery("SELECT COUNT(*) FROM "+q(table))){rows.next();if(rows.getLong(1)!=count)throw new JobDiagnostics.Failure("RESTORE_VALUE_MISMATCH",table,pk);}
    var defaults=new ArrayList<>(allowed.keySet());defaults.removeAll(columns);
    report.put(table,Map.of("rows",count,"generatedIds",generated,"normalizedValues",normalized,"defaultedColumns",defaults,"verifiedSemanticSha256",HexFormat.of().formatHex(digest.digest())));
    if(!columns.contains("create_time")&&allowed.containsKey("create_time")&&allowed.containsKey("update_time"))st.executeUpdate("UPDATE "+q(table)+" SET create_time=update_time,update_time=update_time");
   }st.executeUpdate("UPDATE account_security SET temporary_state=IF(temporary_state='NORMAL','NORMAL','REISSUE'),temporary_expires_at=NULL,temporary_errors=0,anomaly_epoch=NOW(6),warning_sent=0");
   st.executeUpdate("UPDATE password_change_request SET status='EXPIRED',active_employee_id=NULL WHERE status='APPROVED'");
   st.execute("SET FOREIGN_KEY_CHECKS=1");JobDiagnostics.phase("relationships");Schema.relations(c);
   try(var rs=st.executeQuery("SELECT COUNT(*) FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.user_type=2 AND e.employment_status=1 AND a.account_status=1")){rs.next();if(rs.getInt(1)==0)throw new IllegalArgumentException("备份必须包含有效高层账号");}
   c.commit();
  }catch(Exception e){primary=e;try{c.rollback();}catch(Exception rollback){e.addSuppressed(rollback);}throw e;}finally{try{try(var reset=c.createStatement()){reset.execute("SET FOREIGN_KEY_CHECKS=1");}c.setAutoCommit(true);}catch(Exception cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
  // Data and relationships committed successfully in this isolated target. MySQL DDL is
  // deliberately performed afterwards; any failure still prevents activation of the target.
  for(String table:Schema.TABLES){
   var meta=m.path("tables").get(table);if(meta==null||meta.path("autoIncrement").isNull()||meta.path("autoIncrement").isMissingNode())continue;
   String value=meta.path("autoIncrement").asText();
   if(!value.matches("[0-9]{1,20}"))throw new IllegalArgumentException("Invalid auto increment metadata");
   if(!Schema.columns(c,table).containsKey("id"))throw new IllegalArgumentException("Unexpected auto increment table");
   try(var statement=c.createStatement()){statement.executeUpdate("ALTER TABLE "+q(table)+" AUTO_INCREMENT="+value);}
  }
  return report;
 }
}
