package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Pure preview: no database connection and no side effects on a running installation. */
final class ExtensionReconciler {
 static final Set<String> SECURITY_TABLES=Set.of("account_security","secondary_credential","password_change_request","login_security_event","security_notification");
 record Issue(String table,String recordId,String code,boolean blocking){}
 record Plan(Map<String,List<ObjectNode>> restore,List<Issue> issues,int credentialsToReset,String inputBinding,String previewSha256){
  boolean blocked(){return issues.stream().anyMatch(Issue::blocking);}
  Map<String,Object> publicSummary(){var groups=new TreeMap<String,Long>();for(var issue:issues)groups.merge(issue.code(),1L,Long::sum);return Map.of("restorable",restore.values().stream().mapToInt(List::size).sum(),"conflicts",groups,"blocking",blocked(),"secondaryCredentialsToReset",credentialsToReset,"previewSha256",previewSha256);}
  @Override public String toString(){return "ExtensionRestorePlan[private identities and values redacted]";}
 }
 private ExtensionReconciler(){}
 static boolean validId(String id){try{return Long.toString(Long.parseLong(id)).equals(id);}catch(NumberFormatException invalid){return false;}}
 static Map<String,ObjectNode> rows(Path file,String primary)throws Exception{
  var result=new LinkedHashMap<String,ObjectNode>();
  try(var parser=Snapshot.JSON.getFactory().createParser(file.toFile())){
   if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw new IllegalArgumentException("Invalid extension table");
   while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){
    JsonNode value=Snapshot.JSON.readTree(parser);if(value==null||!value.isObject()||result.size()>=250000||!validId(value.path(primary).asText()))throw new IllegalArgumentException("Invalid or excessive extension records");
    if(result.put(value.path(primary).asText(),(ObjectNode)value)!=null)throw new IllegalArgumentException("Duplicate extension identity");
   }
   if(parser.nextToken()!=null)throw new IllegalArgumentException("Unexpected extension data");
  }return result;
 }
 static Map<String,JsonNode> generations(JsonNode data){
  if(!data.isArray()||data.size()>500000)throw new IllegalArgumentException("Invalid lineage inventory");var result=new HashMap<String,JsonNode>();
  for(var item:data){String table=item.path("table").asText(),id=item.path("id").asText(),generation=item.path("generation").asText();
   if(!CompatibilityArchive.BASE_TABLES.contains(table)||!validId(id)||!UUID.fromString(generation).toString().equals(generation)||!item.path("alive").isBoolean()||result.put(table+":"+id,item)!=null)throw new IllegalArgumentException("Invalid lineage record");
  }return result;
 }
 static String continuity(String table,String id,Map<String,ObjectNode> original,Map<String,ObjectNode> returned,Map<String,JsonNode> before,Map<String,JsonNode> after){
  if(!original.containsKey(id))return "NOT_ORIGINAL";
  if(!returned.containsKey(id))return "DELETED"; // Absent rows never resurrect, including cascaded deletes.
  JsonNode initial=before.get(table+":"+id),current=after.get(table+":"+id);
  if(initial==null||!initial.path("alive").asBoolean()||current==null||!current.path("alive").asBoolean())return "TRACKING_INCOMPLETE";
  if(!initial.path("generation").asText().equals(current.path("generation").asText()))return "PRIMARY_KEY_REUSED";
  return "SAME";
 }
 static Map<String,ObjectNode> accountsByEmployee(Map<String,ObjectNode> rows){var out=new HashMap<String,ObjectNode>();for(var row:rows.values())if(out.put(row.path("employee_id").asText(),row)!=null)throw new IllegalArgumentException("Ambiguous account identity");return out;}
 static boolean same(JsonNode left,JsonNode right,String... fields){for(String field:fields)if(!Objects.equals(left.get(field),right.get(field)))return false;return true;}
 static String person(String employee,Map<String,ObjectNode> beforeRows,Map<String,ObjectNode> afterRows,Map<String,JsonNode> before,Map<String,JsonNode> after){
  String result=continuity("employee",employee,beforeRows,afterRows,before,after);
  if(result.equals("SAME")&&!same(beforeRows.get(employee),afterRows.get(employee),"employee_no","identity_number","birth_date"))return "IDENTITY_CHANGED";return result;
 }
 static Plan preview(CompatibilityExtensions extension,Path returnedTables,JsonNode returnedManifest,LineageTracker.VerifiedReturn verified,String inputBinding)throws Exception{
  if(!inputBinding.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Missing preview input binding");
  JsonNode verifiedProof=verified.body();
  if(!extension.batchId().equals(verifiedProof.path("batchId").asText()))throw new IllegalArgumentException("Wrong extension batch");
  if(returnedManifest.path("schemaVersion").asInt()!=2||returnedManifest.path("tables").size()!=17)throw new IllegalArgumentException("Unknown return structure");
  for(String name:CompatibilityArchive.BASE_TABLES){
   if(!BackupCrypto.sha256(returnedTables.resolve(name+".json")).equals(returnedManifest.path("tables").path(name).path("sha256").asText()))throw new IllegalArgumentException("Return stage changed since validation");
   var fields=new HashSet<String>();returnedManifest.path("tables").path(name).path("columns").fieldNames().forEachRemaining(fields::add);
   if(!fields.equals(CompatibilityCatalog.profile("canonical-120").tables().get(name).columns().keySet()))throw new IllegalArgumentException("Return columns are not canonical");
  }
  var before=generations(verifiedProof.path("receipt").path("body").path("baseline"));var after=generations(verifiedProof.path("heads"));
  var oldEmployees=rows(extension.tables().resolve("employee.json"),"id");var newEmployees=rows(returnedTables.resolve("employee.json"),"id");
  var oldAccounts=rows(extension.tables().resolve("user_account.json"),"id");var newAccounts=rows(returnedTables.resolve("user_account.json"),"id");
  var oldAccountByEmployee=accountsByEmployee(oldAccounts);var newAccountByEmployee=accountsByEmployee(newAccounts);
  var restore=new LinkedHashMap<String,List<ObjectNode>>();var issues=new ArrayList<Issue>();int credentialResets=0;
  for(String table:Schema.TABLES.subList(17,22)){
   var records=rows(extension.tables().resolve(table+".json"),"id");var accepted=new ArrayList<ObjectNode>();
   var shape=CompatibilityCatalog.profile("canonical-121").tables().get(table);
   for(var row:records.values()){
    var fields=new HashSet<String>();row.fieldNames().forEachRemaining(fields::add);
    if(row.size()!=shape.columns().size()||!shape.columns().keySet().equals(fields))throw new IllegalArgumentException("Unregistered extension columns");
    String id=row.path("id").asText(),employee=row.path("employee_id").asText(),reason=person(employee,oldEmployees,newEmployees,before,after);
    boolean blocking=false;
    if(reason.equals("SAME")&&table.equals("security_notification"))reason=person(row.path("subject_employee_id").asText(),oldEmployees,newEmployees,before,after);
    if(reason.equals("SAME")&&table.equals("password_change_request")&&!row.path("approver_id").isNull())reason=person(row.path("approver_id").asText(),oldEmployees,newEmployees,before,after);
    if(reason.equals("SAME")&&(table.equals("account_security")||table.equals("secondary_credential"))){
     var origin=oldAccountByEmployee.get(employee);var current=newAccountByEmployee.get(employee);
     if(origin==null||current==null)reason="ACCOUNT_MISSING";
     else if(!origin.path("id").equals(current.path("id"))||!continuity("user_account",origin.path("id").asText(),oldAccounts,newAccounts,before,after).equals("SAME"))reason="ACCOUNT_REPLACED";
     else if(table.equals("secondary_credential")&&(!"2".equals(oldEmployees.get(employee).path("user_type").asText())||!"2".equals(newEmployees.get(employee).path("user_type").asText())||!same(origin,current,"password_hash"))){reason="SECONDARY_REINITIALIZE";credentialResets++;}
    }
    if(table.equals("account_security")&&(row.path("frozen").asInt()!=0||!"NORMAL".equals(row.path("temporary_state").asText()))){reason="UNSUPPORTED_SECURITY_STATE";blocking=true;}
    if(!reason.equals("SAME")){
     blocking|=Set.of("TRACKING_INCOMPLETE","PRIMARY_KEY_REUSED","IDENTITY_CHANGED","ACCOUNT_REPLACED","UNSUPPORTED_SECURITY_STATE").contains(reason);
     issues.add(new Issue(table,id,reason,blocking));continue;
    }
    ObjectNode value=row.deepCopy();
    if(table.equals("password_change_request")&&Set.of("PENDING","APPROVED").contains(value.path("status").asText())){value.put("status","EXPIRED");value.putNull("active_employee_id");}
    if(table.equals("account_security")){value.put("temporary_errors","0");value.put("warning_sent","0");value.putNull("temporary_expires_at");}
    accepted.add(value);
   }restore.put(table,List.copyOf(accepted));
  }
  return new Plan(Collections.unmodifiableMap(restore),List.copyOf(issues),credentialResets,inputBinding,planHash(restore,issues,credentialResets,inputBinding));
 }
 static String planHash(Map<String,List<ObjectNode>> restore,List<Issue> issues,int reset,String binding)throws Exception{return LineageTracker.hash(Snapshot.JSON.valueToTree(Map.of("restore",restore,"issues",issues,"reset",reset,"inputBinding",binding)));}
 static int applyIsolated(Connection target,Plan plan,String confirmedBinding,boolean explicitlySkipConflicts)throws Exception{
  return applyIsolated(target,plan,confirmedBinding,explicitlySkipConflicts,null);
 }
 static int applyIsolated(Connection target,Plan plan,String confirmedBinding,boolean explicitlySkipConflicts,RecoveryTarget allocation)throws Exception{
  // This primitive cannot be used on an active database: the surrounding recovery pipeline
  // must first restore the base into a brand-new generation and only later offer a switch.
  if(allocation==null)LineageTracker.owned(target);else allocation.require(target);Schema.safeUrl(target.getMetaData().getURL());
  if(!target.getAutoCommit()||plan.blocked()||!plan.previewSha256().equals(confirmedBinding)||!plan.previewSha256().equals(planHash(plan.restore(),plan.issues(),plan.credentialsToReset(),plan.inputBinding()))||(!plan.issues().isEmpty()&&!explicitlySkipConflicts))throw new IllegalArgumentException("Preview is blocked, changed, or conflicts were not explicitly acknowledged");
  CompatibilityCatalog.requireProfile(target,"canonical-121");
  try(var st=target.createStatement()){
   for(String table:java.util.stream.Stream.concat(SECURITY_TABLES.stream(),Schema.EPHEMERAL.stream()).toList())try(var rows=st.executeQuery("SELECT COUNT(*) FROM "+Snapshot.q(table))){rows.next();if(rows.getLong(1)!=0)throw new IllegalArgumentException("Extension restore target is not a fresh isolated generation");}
  }
  target.setAutoCommit(false);Exception primary=null;int restored=0;
  try{
   String epoch;try(var st=target.createStatement();var rows=st.executeQuery("SELECT DATE_FORMAT(NOW(6),'%Y-%m-%d %H:%i:%s.%f')")){rows.next();epoch=rows.getString(1);}
   for(String table:Schema.TABLES.subList(17,22)){
    var columns=RestoreMapping.columns(target,table);var names=new ArrayList<>(columns.keySet());
    String sql="INSERT INTO "+Snapshot.q(table)+"("+String.join(",",names.stream().map(Snapshot::q).toList())+") VALUES("+String.join(",",Collections.nCopies(names.size(),"?"))+")";
    String readSql="SELECT "+String.join(",",names.stream().map(Snapshot::q).toList())+" FROM "+Snapshot.q(table)+" WHERE id=?";
    try(var insert=target.prepareStatement(sql);var read=target.prepareStatement(readSql)){
     for(var row:plan.restore().getOrDefault(table,List.of())){
      var expected=new ArrayList<String>();
      for(int i=0;i<names.size();i++){
       String name=names.get(i);JsonNode value=row.get(name);if(value==null||(!value.isNull()&&!value.isTextual()))throw new IllegalArgumentException("Invalid persistent extension value");
       String text=table.equals("account_security")&&name.equals("anomaly_epoch")?epoch:value.isNull()?null:value.asText();
       text=RestoreMapping.canonical(text,columns.get(name),table,name);expected.add(text);RestoreMapping.bind(insert,i+1,text,columns.get(name));
      }
      insert.executeUpdate();read.setString(1,row.path("id").asText());
      try(var actual=read.executeQuery()){
       if(!actual.next())throw new IllegalArgumentException("Restored extension missing");
       for(int i=0;i<names.size();i++)if(!Objects.equals(expected.get(i),RestoreMapping.canonical(actual.getString(i+1),columns.get(names.get(i)),table,names.get(i))))throw new IllegalArgumentException("Restored extension value mismatch");
       if(actual.next())throw new IllegalArgumentException("Duplicate restored extension");
      }restored++;
     }
    }
   }
   Schema.relations(target);target.commit();return restored;
  }catch(Exception failure){primary=failure;try{target.rollback();}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}
  finally{try{target.setAutoCommit(true);}catch(Exception reset){if(primary!=null)primary.addSuppressed(reset);else throw reset;}}
 }
}
