package com.example.ems.upgrade;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

/** Public diagnostics contain catalogued text, never exception messages or input values. */
public final class JobDiagnostics {
 private JobDiagnostics(){}
 private static final ThreadLocal<String> PHASE=ThreadLocal.withInitial(()->"preflight");
 private static final Set<String> PHASES=Set.of("preflight","authorization","decrypt","inspect","create-target","migrate-target","restore-data","verify-values","relationships","export-snapshot","encrypt","verify-admin","launcher","timeout","switch","compatibility-export","lineage","confirmation");
 private static final Map<String,String> MESSAGES=Map.ofEntries(
  Map.entry("RESTORE_VALUE_MISMATCH","恢复字段与备份的精确数值或内容不一致"),Map.entry("UNSUPPORTED_COLUMNS","备份包含不支持的字段"),
  Map.entry("INVALID_VALUE","备份字段类型、范围或精度无效，未进行舍入或截断"),Map.entry("REQUIRED_FIELD_MISSING","备份缺少必要字段"),
  Map.entry("BACKUP_INVALID","备份口令、完整性或格式验证失败"),Map.entry("BACKUP_HASH_MISMATCH","备份表文件摘要不一致"),
  Map.entry("DUPLICATE_DATA","备份存在重复主键或唯一字段"),Map.entry("RELATION_INVALID","备份业务关联不完整"),
  Map.entry("DATABASE_UNAVAILABLE","隔离数据库连接中断或不可用"),Map.entry("DATABASE_REJECTED","数据库拒绝恢复数据"),
  Map.entry("AUTHORIZATION_EXPIRED","当前高层授权已失效"),Map.entry("TIMEOUT","维护子进程超时，需检查任务状态"),
  Map.entry("PROCESS_FAILED","维护子进程失败，未提供更详细的诊断"),Map.entry("INTERNAL_FAILURE","维护发生内部错误"));
 public static final class Failure extends IllegalArgumentException {
  public final String code,table,column;
  public Failure(String code,String table,String column){super(MESSAGES.getOrDefault(code,MESSAGES.get("INTERNAL_FAILURE"))+" ["+(Schema.TABLES.contains(table)?table:"")+"."+(column!=null&&column.matches("[a-z_]{0,64}")?column:"")+"]");this.code=code;this.table=table;this.column=column;}
 }
 public static void phase(String phase){PHASE.set(PHASES.contains(phase)?phase:"preflight");}
 public static Map<String,Object> describe(String id,Throwable error,String active){
  String code="INTERNAL_FAILURE",sqlState="";int vendor=0;String table="",column="";var chain=new ArrayList<String>();
  for(Throwable e=error;e!=null&&chain.size()<8;e=e.getCause()){
   chain.add(e.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_$]",""));
   if(e instanceof Failure f){code=MESSAGES.containsKey(f.code)?f.code:"INTERNAL_FAILURE";table=Schema.TABLES.contains(f.table)?f.table:"";column=f.column!=null&&f.column.matches("[a-z_]{1,64}")?f.column:"";}
   else if(e instanceof SQLException s){sqlState=s.getSQLState()!=null&&s.getSQLState().matches("[A-Z0-9]{5}")?s.getSQLState():"";vendor=s.getErrorCode();code=switch(vendor){case 1062->"DUPLICATE_DATA";case 1048,1364->"REQUIRED_FIELD_MISSING";case 1264,1292,1406->"INVALID_VALUE";case 1451,1452->"RELATION_INVALID";case 2006,2013->"DATABASE_UNAVAILABLE";default->sqlState.startsWith("08")?"DATABASE_UNAVAILABLE":"DATABASE_REJECTED";};}
   else if(e instanceof IllegalArgumentException){String m=String.valueOf(e.getMessage());if(m.contains("hash mismatch"))code="BACKUP_HASH_MISMATCH";else if(m.contains("备份口令")||m.contains("backup version")||m.contains("backup format"))code="BACKUP_INVALID";else if(m.contains("authorization expired")||m.contains("session expired"))code="AUTHORIZATION_EXPIRED";else if(m.contains("relation")||m.contains("relationship"))code="RELATION_INVALID";}
  }
  return view(id,code,PHASE.get(),sqlState,vendor,active,table,column,chain);
 }
 private static Map<String,Object> view(String id,String code,String phase,String sql,int vendor,String active,String table,String column,List<String> chain){
  var out=new LinkedHashMap<String,Object>();out.put("errorId",id.matches("[a-f0-9-]{36}")?id:UUID.randomUUID().toString());out.put("code",code);out.put("phase",phase);out.put("message",MESSAGES.get(code));out.put("sqlState",sql);out.put("vendorCode",vendor);out.put("activeDatabase",active);
  out.put("recommendation",code.equals("AUTHORIZATION_EXPIRED")?"重新登录高层账号后重新授权；不要重复确认旧任务":code.equals("TIMEOUT")?"保留任务和隔离目标，确认子进程结束后再重试；不要切换活动库":"保留备份及任务编号，检查诊断与版本兼容说明；仅在新隔离目标重试，勿清空原库或执行repair");
  out.put("table",table);out.put("column",column);out.put("causes",chain);return out;
 }
 public static Map<String,Object> read(Path job,String id){
  try{
   Path file=job.resolve("failure.json");if(Files.size(file)>16384)throw new IllegalArgumentException();var n=Snapshot.JSON.readTree(file.toFile());String code=n.path("code").asText();if(!MESSAGES.containsKey(code))throw new IllegalArgumentException();
   String phase=n.path("phase").asText();String sql=n.path("sqlState").asText();String table=n.path("table").asText(),column=n.path("column").asText();
   var causes=new ArrayList<String>();for(var cause:n.path("causes")){String type=cause.asText();if(causes.size()<8&&type.matches("[A-Za-z0-9_$]{1,100}"))causes.add(type);}
   return view(id,code,PHASES.contains(phase)?phase:"launcher",sql.matches("[A-Z0-9]{5}")?sql:"",n.path("vendorCode").asInt(),"UNCHANGED",Schema.TABLES.contains(table)?table:"",column.matches("[a-z_]{1,64}")?column:"",causes);
  }catch(Exception unavailable){return view(id,"PROCESS_FAILED","launcher","",0,"UNCHANGED","","",List.of());}
 }
 public static void persist(Throwable error){
  try{
   Path root=Path.of(MaintenanceMain.env("EMS_MAINT_ROOT")).toRealPath(),job=Path.of(MaintenanceMain.env("EMS_MAINT_JOB")).toRealPath();
   if(!job.startsWith(root.resolve("data"))||!job.getFileName().toString().matches("[a-f0-9-]{36}"))return;
   Path part=job.resolve("failure.tmp");Snapshot.JSON.writeValue(part.toFile(),describe(job.getFileName().toString(),error,"UNCHANGED"));Files.move(part,job.resolve("failure.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  }catch(Exception ignored){System.err.println("Diagnostic persistence unavailable; task retained");}
 }
}
