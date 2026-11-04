package com.example.ems.upgrade;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** One-way, exact legacy-to-current value mapping. No downgrade or unknown-field preservation. */
final class RestoreMapping {
 record Column(int type,int scale,int size,boolean nullable,boolean generated,String defaultValue){}
 static Map<String,Column> columns(Connection c,String table)throws SQLException{
  var temporal=new HashMap<String,Integer>();try(var st=c.prepareStatement("SELECT COLUMN_NAME,DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND DATA_TYPE IN('datetime','timestamp','time')")){st.setString(1,c.getCatalog());st.setString(2,table);try(var rs=st.executeQuery()){while(rs.next())temporal.put(rs.getString(1),rs.getInt(2));}}
  var out=new LinkedHashMap<String,Column>();try(var rs=c.getMetaData().getColumns(c.getCatalog(),null,table,"%")){while(rs.next())out.put(rs.getString("COLUMN_NAME"),new Column(rs.getInt("DATA_TYPE"),temporal.getOrDefault(rs.getString("COLUMN_NAME"),rs.getInt("DECIMAL_DIGITS")),rs.getInt("COLUMN_SIZE"),rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,"YES".equals(rs.getString("IS_AUTOINCREMENT")),rs.getString("COLUMN_DEF")));}return out;
 }
 static String canonical(String value,Column c,String table,String name){
  if(value==null){if(!c.nullable)throw new JobDiagnostics.Failure("REQUIRED_FIELD_MISSING",table,name);return null;}
  try{
   return switch(c.type){
    case Types.DECIMAL,Types.NUMERIC->{var n=new BigDecimal(value).setScale(c.scale,RoundingMode.UNNECESSARY);if(n.precision()>c.size)throw new ArithmeticException();yield n.toPlainString();}
    case Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.BIT,Types.BOOLEAN->new BigInteger(value).toString();
    case Types.DATE->LocalDate.parse(value).toString();
    case Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE->{var d=LocalDateTime.parse(value.replace(' ','T'));int precision=Math.max(0,Math.min(9,c.scale));if(d.getNano()%(int)Math.pow(10,9-precision)!=0)throw new ArithmeticException();yield Timestamp.valueOf(d).toString();}
    case Types.TIME->{var t=LocalTime.parse(value);if(t.getNano()!=0)throw new ArithmeticException();yield java.sql.Time.valueOf(t).toString();}
    default->{if(value.length()>c.size&&c.size>0)throw new ArithmeticException();yield value;}
   };
  }catch(RuntimeException invalid){if(invalid instanceof JobDiagnostics.Failure f)throw f;throw new JobDiagnostics.Failure("INVALID_VALUE",table,name);}
 }
 static void bind(PreparedStatement st,int i,String value,Column col)throws SQLException {
  if(value==null){st.setNull(i,col.type);return;}
  switch(col.type){case Types.DECIMAL,Types.NUMERIC->st.setBigDecimal(i,new BigDecimal(value));case Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE->st.setTimestamp(i,Timestamp.valueOf(value));case Types.DATE->st.setDate(i,java.sql.Date.valueOf(value));case Types.TIME->st.setTime(i,java.sql.Time.valueOf(value));default->st.setString(i,value);}
 }
 static void validate(String table,List<String> source,Map<String,Column> target){
  if(source.isEmpty()||source.size()>100||!target.keySet().containsAll(source))throw new JobDiagnostics.Failure("UNSUPPORTED_COLUMNS",table,"");
  for(var e:target.entrySet())if(!source.contains(e.getKey())&&!e.getValue().nullable&&!e.getValue().generated&&e.getValue().defaultValue==null)throw new JobDiagnostics.Failure("REQUIRED_FIELD_MISSING",table,e.getKey());
  // Only leaf/detail IDs may be synthesized. All referenced identities must be present.
  if(!source.contains("id")&&!table.equals("employee_profile")&&!Set.of("employee_change_record","salary_record","operation_log","attendance_record","leave_approval_record","employee_education","employee_experience","employee_skill","employee_certificate").contains(table))throw new JobDiagnostics.Failure("REQUIRED_FIELD_MISSING",table,"id");
 }
}
