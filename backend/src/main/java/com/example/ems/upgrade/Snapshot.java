package com.example.ems.upgrade;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.core.*;
import java.sql.*;import java.nio.file.*;import java.io.*;import java.util.*;import java.util.zip.*;import java.security.*;
public final class Snapshot {
 public static final ObjectMapper JSON=new ObjectMapper();
 public static String q(String s){if(!s.matches("[a-z_][a-z0-9_]*"))throw new IllegalArgumentException("Invalid identifier");return ((char)96)+s+((char)96);}
 static class KeepOpen extends FilterOutputStream{KeepOpen(OutputStream o){super(o);}public void close()throws IOException{flush();}}
 public static Map<String,Object> write(Connection c,Path out)throws Exception{
  Schema.known(c);Schema.relations(c);boolean auto=c.getAutoCommit();c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
  Map<String,Object> manifest=new LinkedHashMap<>(),tables=new LinkedHashMap<>();
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
   manifest.put("format",1);manifest.put("programVersion",Schema.names(c).contains("flyway_schema_history")?"1.2.0":System.getenv().getOrDefault("EMS_SOURCE_VERSION","legacy-unversioned"));manifest.put("buildId",Schema.names(c).contains("flyway_schema_history")?"v1.2.0-rc1":"legacy-unversioned");manifest.put("schemaVersion",2);manifest.put("sourceKind",Schema.names(c).contains("flyway_schema_history")?"flyway":"legacy-unversioned");manifest.put("tables",tables);
   zip.putNextEntry(new ZipEntry("manifest.json"));JSON.writeValue(new KeepOpen(zip),manifest);zip.closeEntry();c.commit();return manifest;
  }catch(Exception e){c.rollback();Files.deleteIfExists(out);throw e;}finally{c.setAutoCommit(auto);}
 }
 public static JsonNode inspect(Path zipPath,Path staging)throws Exception{
  long declared=0;int files=0;
  try(var central=new ZipFile(zipPath.toFile())){var all=central.entries();while(all.hasMoreElements()){var e=all.nextElement();if(++files>18||e.getSize()<0)throw new IllegalArgumentException("Invalid backup size metadata");declared+=e.getSize();if(declared>4L*1024*1024*1024)throw new IllegalArgumentException("Backup exceeds parse limits");}}
  if(Files.getFileStore(staging.getParent()).getUsableSpace()<declared*2+256L*1024*1024)throw new IllegalArgumentException("Insufficient free space for isolated restore");
  Files.createDirectory(staging);Set<String> entries=new HashSet<>();long total=0;
  try(var zip=new ZipInputStream(Files.newInputStream(zipPath))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
   String name=entry.getName();if(!entries.add(name)||!(name.equals("manifest.json")||name.matches("tables/[a-z_]+\\.json"))||entry.isDirectory())throw new IllegalArgumentException("Unsafe backup entry");
   if(name.startsWith("tables/")&&!Schema.TABLES.contains(name.substring(7,name.length()-5)))throw new IllegalArgumentException("Unexpected business table");
   Path dest=staging.resolve(name);Files.createDirectories(dest.getParent());long bytes=0;try(var out=Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW)){byte[] b=new byte[65536];int n;while((n=zip.read(b))!=-1){bytes+=n;total+=n;if(total>4L*1024*1024*1024||(name.equals("manifest.json")&&bytes>1048576))throw new IllegalArgumentException("Backup exceeds parse limits");out.write(b,0,n);}}
  }}
  JsonNode m=JSON.readTree(staging.resolve("manifest.json").toFile());if(m.path("format").asInt()!=1||m.path("schemaVersion").asInt()>2)throw new IllegalArgumentException("Incompatible backup version");
  var fields=m.path("tables").fields();Set<String> present=new HashSet<>();
  while(fields.hasNext()){var e=fields.next();String table=e.getKey();if(!Schema.TABLES.contains(table))throw new IllegalArgumentException("Unexpected table");present.add(table);Path file=staging.resolve("tables/"+table+".json");var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}if(!HexFormat.of().formatHex(digest.digest()).equals(e.getValue().path("sha256").asText()))throw new IllegalArgumentException("Table hash mismatch");}
  if(!present.containsAll(Schema.TABLES.subList(0,12))||entries.size()!=present.size()+1)throw new IllegalArgumentException("Incomplete business backup");return m;
 }
 public static void restore(Connection c,JsonNode m,Path staging)throws Exception{
  var names=Schema.names(c);if(!names.contains("flyway_schema_history"))throw new IllegalArgumentException("Target must be migrated first");
  if(!Schema.TABLES.stream().allMatch(t->names.contains(t)))throw new IllegalArgumentException("Target schema incomplete");
  try(var statement=c.createStatement();var existing=statement.executeQuery("SELECT COUNT(*) FROM employee")){existing.next();if(existing.getLong(1)!=0)throw new IllegalArgumentException("Restore target must have no employees");}
  c.setAutoCommit(false);
  try(var st=c.createStatement()){
   st.execute("SET FOREIGN_KEY_CHECKS=0");for(String t:Schema.TABLES)st.executeUpdate("DELETE FROM "+q(t));st.executeUpdate("DELETE FROM account_active_session");
   for(String table:Schema.TABLES){var meta=m.path("tables").get(table);if(meta==null)continue;
    var columns=new ArrayList<String>();meta.path("columns").fieldNames().forEachRemaining(columns::add);var allowed=Schema.columns(c,table);
    if(!allowed.keySet().containsAll(columns)||columns.size()>100)throw new IllegalArgumentException("Unknown columns in "+table);
    String sql="INSERT INTO "+q(table)+"("+String.join(",",columns.stream().map(Snapshot::q).toList())+") VALUES("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";long count=0;
    try(var parser=JSON.getFactory().createParser(staging.resolve("tables/"+table+".json").toFile());var insert=c.prepareStatement(sql)){
     if(parser.nextToken()!=JsonToken.START_ARRAY)throw new IllegalArgumentException("Invalid table data");
     while(parser.nextToken()!=JsonToken.END_ARRAY){JsonNode row=JSON.readTree(parser);if(!row.isObject()||row.size()!=columns.size())throw new IllegalArgumentException("Invalid row");
      for(int i=0;i<columns.size();i++){var v=row.get(columns.get(i));if(v==null||(!v.isNull()&&!v.isTextual()))throw new IllegalArgumentException("Invalid scalar");if(v.isNull())insert.setNull(i+1,Types.NULL);else insert.setString(i+1,v.asText());}insert.executeUpdate();count++;
     }if(parser.nextToken()!=null||count!=meta.path("rows").asLong(-1))throw new IllegalArgumentException("Row count mismatch");
    }
    if(!columns.contains("create_time")&&allowed.containsKey("create_time")&&allowed.containsKey("update_time"))st.executeUpdate("UPDATE "+q(table)+" SET create_time=update_time");
   }st.execute("SET FOREIGN_KEY_CHECKS=1");Schema.relations(c);
   try(var rs=st.executeQuery("SELECT COUNT(*) FROM employee e JOIN user_account a ON a.employee_id=e.id WHERE e.user_type=2 AND e.employment_status=1 AND a.account_status=1")){rs.next();if(rs.getInt(1)==0)throw new IllegalArgumentException("备份必须包含有效高层账号");}
   c.commit();
  }catch(Exception e){c.rollback();throw e;}finally{try(var st=c.createStatement()){st.execute("SET FOREIGN_KEY_CHECKS=1");}c.setAutoCommit(true);}
  // Data and relationships committed successfully in this isolated target. MySQL DDL is
  // deliberately performed afterwards; any failure still prevents activation of the target.
  for(String table:Schema.TABLES){
   var meta=m.path("tables").get(table);if(meta==null||meta.path("autoIncrement").isNull()||meta.path("autoIncrement").isMissingNode())continue;
   String value=meta.path("autoIncrement").asText();
   if(!value.matches("[0-9]{1,20}"))throw new IllegalArgumentException("Invalid auto increment metadata");
   if(!Schema.columns(c,table).containsKey("id"))throw new IllegalArgumentException("Unexpected auto increment table");
   try(var statement=c.createStatement()){statement.executeUpdate("ALTER TABLE "+q(table)+" AUTO_INCREMENT="+value);}
  }
 }
}
