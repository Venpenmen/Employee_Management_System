package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Registered semantic structures, independent of display version or presence of Flyway. */
public final class CompatibilityCatalog {
 public record Column(String type,boolean nullable,boolean generated,String defaultValue,String characterSet,String collation,String onUpdate,String generationExpression){}
 public record Table(Map<String,Column> columns,String primaryKey,Set<String> unique,Set<String> indexes,Set<String> foreign,Map<String,String> foreignRules,String engine,String collation,Map<String,Integer> indexMultiplicity){}
 public record Profile(String id,String programVersion,int schemaVersion,Map<String,Table> tables,Map<String,Table> ephemeralTables){}
 static final Map<String,Profile> PROFILES=new LinkedHashMap<>();
 static {
  register("legacy-113","1.1.3",1,"/db/legacy/113.sql");register("legacy-120","1.2.0",1,"/db/legacy/120.sql");
  register("canonical-120","1.2.0",2,"/db/upgrade/V1__canonical_business_schema.sql");
  var current=new LinkedHashMap<>(profile("canonical-120").tables());current.putAll(read("/db/upgrade/V3__account_security.sql"));current.remove("security_grant");
  var ephemeral=new LinkedHashMap<>(profile("canonical-120").ephemeralTables());ephemeral.put("security_grant",read("/db/upgrade/V3__account_security.sql").get("security_grant"));
  PROFILES.put("canonical-121",new Profile("canonical-121","1.2.1",3,Collections.unmodifiableMap(current),Collections.unmodifiableMap(ephemeral)));
  var current122=new LinkedHashMap<>(profile("canonical-121").tables());current122.putAll(read("/db/upgrade/V4__communications.sql"));
  PROFILES.put("canonical-122",new Profile("canonical-122","1.2.2",4,Collections.unmodifiableMap(current122),profile("canonical-121").ephemeralTables()));
  var current122handover=new LinkedHashMap<>(current122);current122handover.putAll(read("/db/upgrade/V5__announcement_handover.sql"));
  PROFILES.put("canonical-122-handover",new Profile("canonical-122-handover","1.2.2",5,Collections.unmodifiableMap(current122handover),profile("canonical-121").ephemeralTables()));
  var attendance=new LinkedHashMap<>(current122handover);attendance.putAll(read("/db/upgrade/V6__attendance_workflow.sql"));
  PROFILES.put("canonical-122-attendance",new Profile("canonical-122-attendance","1.2.2",6,Collections.unmodifiableMap(attendance),profile("canonical-121").ephemeralTables()));
 }
 private CompatibilityCatalog(){}
 static String primary(String table){return table.equals("employee_profile")||table.equals("account_active_session")?"employee_id":"id";}
 static void register(String id,String version,int schema,String resource){
  var all=read(resource);var persistent=new LinkedHashMap<String,Table>();var ephemeral=new LinkedHashMap<String,Table>();
  all.forEach((name,table)->{if(Schema.EPHEMERAL.contains(name))ephemeral.put(name,table);else persistent.put(name,table);});
  if(id.equals("canonical-120"))ephemeral.putAll(read("/db/upgrade/V2__active_sessions.sql"));
  PROFILES.put(id,new Profile(id,version,schema,Collections.unmodifiableMap(persistent),Collections.unmodifiableMap(ephemeral)));
 }
 public static Profile profile(String id){var value=PROFILES.get(id);if(value==null)throw new IllegalArgumentException("Unregistered compatibility profile");return value;}
 static String defaultValue(String raw,String type){
  if(raw==null||raw.equalsIgnoreCase("NULL"))return null;
  String value=raw;
  if(value.startsWith("'")&&value.endsWith("'"))value=value.substring(1,value.length()-1).replace("''","'");
  if(type.startsWith("DATETIME")||type.startsWith("TIMESTAMP")){if(value.toUpperCase(Locale.ROOT).matches("CURRENT_TIMESTAMP(?:\\(\\d*\\))?"))return value.toUpperCase(Locale.ROOT).replace("()","");}
  if(type.matches("(?:BIGINT|INT|TINYINT|SMALLINT|DECIMAL).*"))try{return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();}catch(NumberFormatException invalid){throw new IllegalArgumentException("Unsupported registered default");}
  return value;
 }
 static String rule(String value){return value.equals("NO ACTION")?"RESTRICT":value;}
 static String jdbcRule(int value){return switch(value){case DatabaseMetaData.importedKeyCascade->"CASCADE";case DatabaseMetaData.importedKeySetNull->"SET NULL";case DatabaseMetaData.importedKeyRestrict,DatabaseMetaData.importedKeyNoAction->"RESTRICT";default->throw new IllegalArgumentException("Unsupported foreign-key rule");};}
 static Map<String,Table> read(String resource){
  try{
   String sql;try(var stream=CompatibilityCatalog.class.getResourceAsStream(resource)){sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
   var result=new LinkedHashMap<String,Table>();var tables=Pattern.compile("CREATE TABLE IF NOT EXISTS\\s+([a-z_]+)\\s*\\((.*?)\\)\\s*ENGINE\\s*=\\s*([a-z_]+)([^;]*);",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(sql);
   while(tables.find()){
    String name=tables.group(1),body=tables.group(2);var shape=SchemaRegistry.REGISTERED.get(resource).get(name);var columns=new LinkedHashMap<String,Column>();
    // These descriptors target the pinned MySQL8 runtime, whose utf8mb4 default is 0900_ai_ci.
    String tableCollation="utf8mb4_0900_ai_ci";var tableCollationMatch=Pattern.compile("COLLATE\\s*=?\\s*([a-z0-9_]+)",Pattern.CASE_INSENSITIVE).matcher(tables.group(4));if(tableCollationMatch.find())tableCollation=tableCollationMatch.group(1).toLowerCase(Locale.ROOT);
    var fields=Pattern.compile("(?:^|,)\\s*([a-z_][a-z0-9_]*)\\s+((?:BIGINT|INT|TINYINT|VARCHAR|CHAR|DECIMAL|DATETIME|DATE|TIME|TEXT)(?:\\(\\d+(?:\\s*,\\s*\\d+)?\\))?)([^,\\n]*)",Pattern.CASE_INSENSITIVE|Pattern.MULTILINE).matcher(body);
    while(fields.find()){
     String column=fields.group(1),type=shape.columns().get(column),flags=fields.group(3).split("(?i)COMMENT",2)[0];
     var d=Pattern.compile("\\bDEFAULT\\s+('(?:''|[^'])*'|CURRENT_TIMESTAMP(?:\\(\\d*\\))?|NULL|-?\\d+(?:\\.\\d+)?)",Pattern.CASE_INSENSITIVE).matcher(flags);
     boolean text=type.startsWith("CHAR")||type.startsWith("VARCHAR")||type.equals("TEXT");String characterSet=text?"utf8mb4":null,collation=text?tableCollation:null;
     var cs=Pattern.compile("CHARACTER SET\\s+([a-z0-9_]+)",Pattern.CASE_INSENSITIVE).matcher(flags);if(cs.find())characterSet=cs.group(1).toLowerCase(Locale.ROOT);
     var co=Pattern.compile("COLLATE\\s+([a-z0-9_]+)",Pattern.CASE_INSENSITIVE).matcher(flags);if(co.find())collation=co.group(1).toLowerCase(Locale.ROOT);
     var update=Pattern.compile("ON UPDATE\\s+(CURRENT_TIMESTAMP(?:\\(\\d*\\))?)",Pattern.CASE_INSENSITIVE).matcher(flags);
     columns.put(column,new Column(type,!column.equals(primary(name))&&!flags.toUpperCase(Locale.ROOT).contains("NOT NULL"),flags.toUpperCase(Locale.ROOT).contains("AUTO_INCREMENT"),defaultValue(d.find()?d.group(1):null,type),characterSet,collation,update.find()?defaultValue(update.group(1),type):null,null));
    }
    var rules=new TreeMap<String,String>();var foreign=Pattern.compile("FOREIGN KEY\\s*\\(([^)]+)\\)\\s*REFERENCES\\s+([a-z_]+)\\s*\\(([^)]+)\\)([^,\\n]*)",Pattern.CASE_INSENSITIVE).matcher(body);
    while(foreign.find()){
     String link=foreign.group(1).trim()+"->"+foreign.group(2)+"."+foreign.group(3).trim(),flags=foreign.group(4).toUpperCase(Locale.ROOT);
     var delete=Pattern.compile("ON DELETE (CASCADE|RESTRICT|NO ACTION|SET NULL)").matcher(flags);var update=Pattern.compile("ON UPDATE (CASCADE|RESTRICT|NO ACTION|SET NULL)").matcher(flags);
     rules.put(link,"delete="+(delete.find()?rule(delete.group(1)):"RESTRICT")+";update="+(update.find()?rule(update.group(1)):"RESTRICT"));
    }
    var multiplicity=new TreeMap<String,Integer>();shape.unique().forEach(key->multiplicity.put("U:"+key,1));shape.indexes().forEach(key->multiplicity.put("N:"+key,1));
    result.put(name,new Table(Collections.unmodifiableMap(columns),primary(name),shape.unique(),shape.indexes(),shape.foreign(),Collections.unmodifiableMap(rules),tables.group(3).toUpperCase(Locale.ROOT),tableCollation,Collections.unmodifiableMap(multiplicity)));
   }return result;
  }catch(Exception error){throw new IllegalStateException("Registered compatibility definitions unavailable",error);}
 }
 public static Map<String,Table> describe(Connection connection)throws SQLException {
  Schema.known(connection);var out=new LinkedHashMap<String,Table>();
  for(String name:java.util.stream.Stream.concat(Schema.TABLES.stream(),Schema.EPHEMERAL.stream()).distinct().toList()){if(!Schema.names(connection).contains(name))continue;
   var typed=RestoreMapping.columns(connection,name);var columns=new LinkedHashMap<String,Column>();
   var details=new HashMap<String,List<String>>();
   try(var st=connection.prepareStatement("SELECT COLUMN_NAME,CHARACTER_SET_NAME,COLLATION_NAME,EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){st.setString(1,connection.getCatalog());st.setString(2,name);try(var rows=st.executeQuery()){while(rows.next())details.put(rows.getString(1),Arrays.asList(rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5)));}}
   try(var rs=connection.getMetaData().getColumns(connection.getCatalog(),null,name,"%")){while(rs.next()){
    String column=rs.getString("COLUMN_NAME"),type=rs.getString("TYPE_NAME").toUpperCase(Locale.ROOT);var meta=typed.get(column);
    if(type.equals("CHAR")||type.equals("VARCHAR"))type+="("+meta.size()+")";else if(type.equals("DECIMAL"))type+="("+meta.size()+","+meta.scale()+")";else if((type.equals("DATETIME")||type.equals("TIME")||type.equals("TIMESTAMP"))&&meta.scale()>0)type+="("+meta.scale()+")";
    var flags=details.get(column);var update=Pattern.compile("on update (CURRENT_TIMESTAMP(?:\\(\\d*\\))?)",Pattern.CASE_INSENSITIVE).matcher(flags.get(2));
    columns.put(column,new Column(type,meta.nullable(),meta.generated(),defaultValue(rs.getString("COLUMN_DEF"),type),flags.get(0),flags.get(1),update.find()?defaultValue(update.group(1),type):null,flags.get(3)==null||flags.get(3).isEmpty()?null:flags.get(3)));
   }}
   var unique=new TreeSet<String>();var indexes=new TreeSet<String>();var groups=new LinkedHashMap<String,SortedMap<Integer,String>>();var nonunique=new HashMap<String,Boolean>();
   try(var rs=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,name,false,false)){while(rs.next()){String key=rs.getString("INDEX_NAME"),column=rs.getString("COLUMN_NAME");if(key==null||column==null||key.equalsIgnoreCase("PRIMARY"))continue;groups.computeIfAbsent(key,k->new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"),column);nonunique.put(key,rs.getBoolean("NON_UNIQUE"));}}
   var multiplicity=new TreeMap<String,Integer>();for(var entry:groups.entrySet()){String key=String.join(",",entry.getValue().values());(nonunique.get(entry.getKey())?indexes:unique).add(key);multiplicity.merge((nonunique.get(entry.getKey())?"N:":"U:")+key,1,Integer::sum);}
   var foreign=new TreeSet<String>();var rules=new TreeMap<String,String>();try(var rs=connection.getMetaData().getImportedKeys(connection.getCatalog(),null,name)){while(rs.next()){String link=rs.getString("FKCOLUMN_NAME")+"->"+rs.getString("PKTABLE_NAME")+"."+rs.getString("PKCOLUMN_NAME");foreign.add(link);rules.put(link,"delete="+jdbcRule(rs.getInt("DELETE_RULE"))+";update="+jdbcRule(rs.getInt("UPDATE_RULE")));}}
   var pk=new TreeMap<Integer,String>();try(var rs=connection.getMetaData().getPrimaryKeys(connection.getCatalog(),null,name)){while(rs.next())pk.put(rs.getInt("KEY_SEQ"),rs.getString("COLUMN_NAME"));}
   String engine,collation;try(var st=connection.prepareStatement("SELECT ENGINE,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){st.setString(1,connection.getCatalog());st.setString(2,name);try(var rows=st.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Missing registered table");engine=rows.getString(1).toUpperCase(Locale.ROOT);collation=rows.getString(2);}}
   try(var st=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_TYPE='CHECK'")){st.setString(1,connection.getCatalog());st.setString(2,name);try(var rows=st.executeQuery()){rows.next();if(rows.getInt(1)!=0)throw new IllegalArgumentException("Unregistered check constraint");}}
   out.put(name,new Table(columns,String.join(",",pk.values()),unique,indexes,foreign,rules,engine,collation,multiplicity));
  }return out;
 }
 public static Profile identify(Connection connection)throws SQLException {
  var actual=describe(connection);var matches=PROFILES.values().stream().filter(p->combined(p).equals(actual)).toList();
  if(matches.isEmpty())throw new IllegalArgumentException("Unregistered database structure; compatibility conversion refused");
  boolean history=Schema.names(connection).contains("flyway_schema_history");
  if(history){String version;try(var st=connection.createStatement();var rs=st.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){if(!rs.next())throw new IllegalArgumentException("Missing migration version");version=rs.getString(1);}
   Profile result=matches.stream().filter(p->String.valueOf(p.schemaVersion()).equals(version)&&p.id().startsWith("canonical-")).findFirst().orElseThrow(()->new IllegalArgumentException("Declared schema contradicts registered structure"));validateHistory(connection,result.schemaVersion());return result;
  }
  var legacy=matches.stream().filter(p->p.id().startsWith("legacy-")).toList();
  if(legacy.size()!=1)throw new IllegalArgumentException("Ambiguous unversioned structure; verified source program identity required");return legacy.get(0);
 }
 static Map<String,Table> combined(Profile profile){var all=new LinkedHashMap<>(profile.tables());all.putAll(profile.ephemeralTables());return all;}
 static final List<String> MIGRATIONS=List.of("V1__canonical_business_schema.sql","V2__active_sessions.sql","V3__account_security.sql","V4__communications.sql","V5__announcement_handover.sql","V6__attendance_workflow.sql");
 static int migrationChecksum(String script){
  if(!MIGRATIONS.contains(script))throw new IllegalArgumentException("Unregistered migration");
  var checksum=new java.util.zip.CRC32();
  // Match the pinned Flyway version's normalized UTF-8 line checksum without giving
  // it ownership of the caller's snapshot connection or invoking migrate/repair.
  try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(CompatibilityCatalog.class.getResourceAsStream("/db/upgrade/"+script),StandardCharsets.UTF_8))){
   String line;boolean first=true;while((line=reader.readLine())!=null){if(first&&line.startsWith("\uFEFF"))line=line.substring(1);first=false;checksum.update(line.getBytes(StandardCharsets.UTF_8));}return (int)checksum.getValue();
  }catch(java.io.IOException error){throw new IllegalStateException("Migration definitions unavailable",error);}
 }
 static void validateHistory(Connection connection,int latest)throws SQLException{
  var seen=new HashSet<String>();
  try(var st=connection.createStatement();var rows=st.executeQuery("SELECT version,script,type,checksum,success FROM flyway_schema_history WHERE version IS NOT NULL")){
   while(rows.next()){
    String version=rows.getString(1);int index;try{index=Integer.parseInt(version)-1;}catch(NumberFormatException invalid){throw new IllegalArgumentException("Unregistered migration history");}
    if(index<0||index>=latest||!seen.add(version)||!MIGRATIONS.get(index).equals(rows.getString(2))||!"SQL".equals(rows.getString(3))||rows.getObject(4)==null||migrationChecksum(MIGRATIONS.get(index))!=rows.getInt(4)||!rows.getBoolean(5))throw new IllegalArgumentException("Migration identity or checksum differs from immutable history");
   }
  }
  if(seen.size()!=latest)throw new IllegalArgumentException("Incomplete migration history");
 }
 public static Profile requireProfile(Connection connection,String id)throws SQLException {
  Profile expected=profile(id);if(!combined(expected).equals(describe(connection)))throw new IllegalArgumentException("Actual structure contradicts the verified source profile");
  if(Schema.names(connection).contains("flyway_schema_history")){
   try(var st=connection.createStatement();var rows=st.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")){if(!rows.next()||!String.valueOf(expected.schemaVersion()).equals(rows.getString(1)))throw new IllegalArgumentException("Migration history contradicts the verified profile");}
   validateHistory(connection,expected.schemaVersion());
  }else if(id.startsWith("canonical-"))throw new IllegalArgumentException("Canonical profile requires validated migration history");
  return expected;
 }
 public static String fingerprint(Profile profile)throws Exception {
  // Sorted map keys prevent metadata iteration order from masquerading as a structural change.
  byte[] bytes=Snapshot.JSON.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(combined(profile));
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
 }
}
