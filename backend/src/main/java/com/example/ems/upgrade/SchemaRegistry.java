package com.example.ems.upgrade;
import java.sql.*;import java.util.*;import java.util.regex.*;import java.nio.charset.StandardCharsets;
public final class SchemaRegistry {
 record Shape(Map<String,String> columns,Set<String> unique,Set<String> indexes,Set<String> foreign){}
 static final Map<String,List<Shape>> SHAPES=new HashMap<>();
 static {
  try{for(String resource:List.of("/db/legacy/113.sql","/db/legacy/120.sql","/db/upgrade/V1__canonical_business_schema.sql")){
   String sql;try(var in=SchemaRegistry.class.getResourceAsStream(resource)){sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
   var tables=Pattern.compile("CREATE TABLE IF NOT EXISTS\\s+([a-z_]+)\\s*\\((.*?)\\)\\s*ENGINE",Pattern.DOTALL|Pattern.CASE_INSENSITIVE).matcher(sql);
   while(tables.find()){
    String name=tables.group(1),body=tables.group(2);Map<String,String> cols=new LinkedHashMap<>();Set<String> unique=new TreeSet<>(),idx=new TreeSet<>(),fk=new TreeSet<>();
    var fields=Pattern.compile("(?:^|,)\\s*([a-z_]+)\\s+((?:BIGINT|INT|TINYINT|VARCHAR|CHAR|DECIMAL|DATETIME|DATE|TIME|TEXT)(?:\\(\\d+(?:\\s*,\\s*\\d+)?\\))?)([^,\\n]*)",Pattern.MULTILINE|Pattern.CASE_INSENSITIVE).matcher(body);
    while(fields.find()){cols.put(fields.group(1),fields.group(2).replace(" ","").toUpperCase(Locale.ROOT));if(fields.group(3).toUpperCase(Locale.ROOT).contains("UNIQUE"))unique.add(fields.group(1));}
    var keys=Pattern.compile("(UNIQUE\\s+)?KEY\\s+[a-z_]+\\s*\\(([^)]+)\\)",Pattern.CASE_INSENSITIVE).matcher(body);
    while(keys.find()){String columns=keys.group(2).replace(" ","");(keys.group(1)==null?idx:unique).add(columns);}
    var foreign=Pattern.compile("FOREIGN KEY\\s*\\(([^)]+)\\)\\s*REFERENCES\\s+([a-z_]+)\\s*\\(([^)]+)\\)",Pattern.CASE_INSENSITIVE).matcher(body);
    while(foreign.find())fk.add(foreign.group(1).trim()+"->"+foreign.group(2)+"."+foreign.group(3).trim());
    String pk=name.equals("employee_profile")?"employee_id":"id";
    for(String link:fk){String column=link.split("->")[0];boolean covered=pk.equals(column)||java.util.stream.Stream.concat(unique.stream(),idx.stream()).anyMatch(v->v.equals(column)||v.startsWith(column+","));if(!covered)idx.add(column);}
    SHAPES.computeIfAbsent(name,k->new ArrayList<>()).add(new Shape(cols,unique,idx,fk));
   }
  }}catch(Exception e){throw new ExceptionInInitializerError(e);}
 }
 public static void validate(Connection c)throws SQLException{
  for(String table:Schema.TABLES){if(!Schema.names(c).contains(table))continue;
   Map<String,String> cols=new LinkedHashMap<>();try(var rs=c.getMetaData().getColumns(c.getCatalog(),null,table,"%")){while(rs.next()){String type=rs.getString("TYPE_NAME").toUpperCase(Locale.ROOT);if(type.equals("VARCHAR")||type.equals("CHAR"))type+="("+rs.getInt("COLUMN_SIZE")+")";else if(type.equals("DECIMAL"))type+="("+rs.getInt("COLUMN_SIZE")+","+rs.getInt("DECIMAL_DIGITS")+")";cols.put(rs.getString("COLUMN_NAME"),type);}}
   Set<String> unique=new TreeSet<>(),idx=new TreeSet<>(),fk=new TreeSet<>();Map<String,TreeMap<Integer,String>> indexes=new HashMap<>();Map<String,Boolean> nonunique=new HashMap<>();
   try(var rs=c.getMetaData().getIndexInfo(c.getCatalog(),null,table,false,false)){while(rs.next()){String name=rs.getString("INDEX_NAME"),column=rs.getString("COLUMN_NAME");if(name==null||column==null||name.equalsIgnoreCase("PRIMARY"))continue;indexes.computeIfAbsent(name,k->new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"),column);nonunique.put(name,rs.getBoolean("NON_UNIQUE"));}}
   for(var entry:indexes.entrySet()){String columns=String.join(",",entry.getValue().values());(nonunique.get(entry.getKey())?idx:unique).add(columns);}
   try(var rs=c.getMetaData().getImportedKeys(c.getCatalog(),null,table)){while(rs.next())fk.add(rs.getString("FKCOLUMN_NAME")+"->"+rs.getString("PKTABLE_NAME")+"."+rs.getString("PKCOLUMN_NAME"));}
   List<String> pk=new ArrayList<>();try(var rs=c.getMetaData().getPrimaryKeys(c.getCatalog(),null,table)){while(rs.next())pk.add(rs.getString("COLUMN_NAME"));}
   if(pk.size()!=1||!pk.get(0).equals(table.equals("employee_profile")?"employee_id":"id"))throw new IllegalArgumentException("Unknown primary key: "+table);
   if(SHAPES.getOrDefault(table,List.of()).stream().noneMatch(shape->shape.columns.equals(cols)&&shape.unique.equals(unique)&&shape.indexes.equals(idx)&&shape.foreign.equals(fk)))throw new IllegalArgumentException("Unsupported historical table structure: "+table);
  }
 }
}
