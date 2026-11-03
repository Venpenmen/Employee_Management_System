package com.example.ems.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Controlled-copy primitive, deliberately not a command for existing installations.
 * Its caller must own the instance, keep the application stopped during installation,
 * and retain the encrypted receipt outside the application's writable directories.
 */
final class LineageTracker {
 private LineageTracker(){}
 static void owned(Connection connection)throws Exception{
  if(System.getenv("EMS_DEVELOPMENT_TASK_ID")==null)throw new IllegalStateException("Owned controlled-copy task required");
  String url=connection.getMetaData().getURL();
  if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(?:165(?:0[6-9]|[12][0-9]|30)|166(?:0[6-9]|[12][0-9]|30))/(?:ems_up_(?:server|portable|dev)_v120|ems_next_(?:server|portable|dev)_v121)(?:_[a-z0-9]+)?(?:\\?.*)?"))throw new IllegalArgumentException("Unsafe controlled-copy connection");
  String configured=System.getenv("EMS_CONTROLLED_ROOT"),scope=System.getenv("EMS_DEVELOPMENT_TASK_ID");
  if(configured==null||!com.example.ems.config.IsolatedFixture.valid(Path.of(configured),scope))throw new IllegalArgumentException("Controlled-copy owner cannot be proved");
  Path root=Path.of(configured).toRealPath();int port=java.net.URI.create(url.substring(5)).getPort();boolean matched=false;
  for(String mode:List.of("server","portable","dev")){
   Path file=root.resolve("data/"+mode+"/instance-upgrade.json");if(!Files.isRegularFile(file)||Files.size(file)>65536)continue;
   JsonNode state=Snapshot.JSON.readTree(file.toFile());if(state.path("dbPort").asInt()!=port||!mode.equals(state.path("mode").asText())||!connection.getCatalog().matches("(?:ems_up|ems_next)_"+mode+"_v(?:120|121)(?:_[a-z0-9]+)?"))continue;
   var identity=state.path("database");var process=ProcessHandle.of(identity.path("id").asLong()).orElseThrow();var info=process.info();
   var start=info.startInstant().orElseThrow();long ticks=621355968000000000L+start.getEpochSecond()*10000000L+start.getNano()/100;
   if(process.isAlive()&&ticks/10000==Long.parseLong(identity.path("ticks").asText())/10000&&Files.isSameFile(Path.of(info.command().orElseThrow()),root.resolve("runtime/mysql/bin/mysqld.exe"))&&Files.isSameFile(Path.of(identity.path("exe").asText()),root.resolve("runtime/mysql/bin/mysqld.exe")))matched=true;
  }
  if(!matched)throw new IllegalArgumentException("Connection is not bound to the owned database process");
 }
 static byte[] canonical(JsonNode value)throws Exception{return Snapshot.JSON.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}
 static String hash(JsonNode value)throws Exception{return BackupBundle.sha256(canonical(value));}
 static void writeEncrypted(JsonNode value,Path output,char[] password)throws Exception{
  Path raw=output.resolveSibling(output.getFileName()+".json.partial");
  boolean created=false;
  try{try(var stream=Files.newOutputStream(raw,StandardOpenOption.CREATE_NEW)){created=true;stream.write(canonical(value));}BackupCrypto.encrypt(raw,output,password);}finally{if(created)Files.deleteIfExists(raw);}
 }
 static JsonNode readEncrypted(Path input,Path stage,char[] password)throws Exception{
  // A newly created private directory is the ownership boundary. An existing caller
  // path must never be deleted just because decryption failed before creating output.
  Files.createDirectory(stage);Path raw=stage.resolve("payload.json");
  Exception primary=null;
  try{BackupCrypto.decrypt(input,raw,password);if(Files.size(raw)>64L*1024*1024)throw new IllegalArgumentException("Lineage proof exceeds limit");return Snapshot.JSON.readTree(raw.toFile());}catch(Exception failure){primary=failure;throw failure;}finally{try{MaintenanceMain.removeStaging(stage);}catch(Exception cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
 }
 static void exactOriginal(Connection connection,CompatibilityExtensions extension)throws Exception{
  for(String table:CompatibilityArchive.BASE_TABLES){
   var columns=RestoreMapping.columns(connection,table);String pk=CompatibilityCatalog.primary(table);
   try(var parser=Snapshot.JSON.getFactory().createParser(extension.tables().resolve(table+".json").toFile());var st=connection.createStatement();var rows=st.executeQuery("SELECT * FROM "+Snapshot.q(table)+" ORDER BY "+Snapshot.q(pk))){
    if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_ARRAY)throw new IllegalArgumentException("Invalid baseline rows");
    while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY){JsonNode baseline=Snapshot.JSON.readTree(parser);if(!rows.next()||baseline.size()!=columns.size())throw new IllegalArgumentException("Controlled copy differs from original base");
     for(var column:columns.entrySet()){
      JsonNode input=baseline.get(column.getKey());if(input==null)throw new IllegalArgumentException("Baseline missing a common column");
      String expected=RestoreMapping.canonical(input.isNull()?null:input.asText(),column.getValue(),table,column.getKey());
      String actual=RestoreMapping.canonical(rows.getString(column.getKey()),column.getValue(),table,column.getKey());
      if(!Objects.equals(expected,actual))throw new IllegalArgumentException("Controlled copy differs from original base");
     }
    }
    if(rows.next()||parser.nextToken()!=null)throw new IllegalArgumentException("Controlled copy has additional rows");
   }
  }
 }
 static JsonNode triggers(Connection connection)throws Exception{
  var array=Snapshot.JSON.createArrayNode();
  try(var query=connection.prepareStatement("SELECT TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_TIMING,ACTION_STATEMENT,DEFINER,CREATED FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? ORDER BY TRIGGER_NAME")){
   query.setString(1,connection.getCatalog());try(var rows=query.executeQuery()){while(rows.next()){var record=array.addObject();for(int i=1;i<=7;i++)record.put(rows.getMetaData().getColumnLabel(i),rows.getString(i));}}
  }return array;
 }
 static JsonNode heads(Connection connection,String tracking)throws Exception{
  var array=Snapshot.JSON.createArrayNode();
  try(var st=connection.createStatement();var rows=st.executeQuery("SELECT table_name,row_id,generation,alive FROM "+Snapshot.q(tracking)+".heads ORDER BY table_name,row_id")){
   while(rows.next()){var row=array.addObject();row.put("table",rows.getString(1));row.put("id",rows.getString(2));row.put("generation",rows.getString(3));row.put("alive",rows.getBoolean(4));}
  }return array;
 }
 static String triggerName(String table,char event){return "ems_p1_"+table+"_"+event;}
 static JsonNode snapshotHeads(Connection connection,String tracking)throws Exception{
  var inventory=heads(connection,tracking);
  // A valid trigger inventory alone does not prove row continuity. Check every live
  // business identity in the same snapshot, including records without extensions.
  for(String table:CompatibilityArchive.BASE_TABLES){
   String pk=Snapshot.q(CompatibilityCatalog.primary(table));
   try(var st=connection.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM "+Snapshot.q(table)+" b LEFT JOIN "+Snapshot.q(tracking)+".heads h ON h.table_name='"+table+"' AND h.row_id=b."+pk+" WHERE h.row_id IS NULL OR h.alive<>1")){
    rows.next();if(rows.getLong(1)!=0)throw new IllegalArgumentException("Tracking lacks a live business identity");
   }
   // MySQL does not run child DELETE triggers for cascades. Actual absence is
   // authoritative; a stale ledger flag must never revive a deleted child.
   var absent=new HashSet<String>();
   try(var st=connection.createStatement();var rows=st.executeQuery("SELECT h.row_id FROM "+Snapshot.q(tracking)+".heads h LEFT JOIN "+Snapshot.q(table)+" b ON b."+pk+"=h.row_id WHERE h.table_name='"+table+"' AND b."+pk+" IS NULL")){while(rows.next())absent.add(rows.getString(1));}
   for(var record:inventory)if(table.equals(record.path("table").asText())&&absent.contains(record.path("id").asText()))((ObjectNode)record).put("alive",false);
  }
  return inventory;
 }
 static String triggerBody(String tracking,String table,char event){
  String q=Snapshot.q(tracking),pk=Snapshot.q(CompatibilityCatalog.primary(table));
  String insert="SET g=UUID(); INSERT INTO "+q+".heads(table_name,row_id,generation,alive) VALUES('"+table+"',NEW."+pk+",g,1) ON DUPLICATE KEY UPDATE generation=g,alive=1; INSERT INTO "+q+".events(table_name,row_id,generation,operation) VALUES('"+table+"',NEW."+pk+",g,'I'); ";
  String old="SELECT generation INTO g FROM "+q+".heads WHERE table_name='"+table+"' AND row_id=OLD."+pk+" AND alive=1 FOR UPDATE; IF g IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='EMS lineage continuity unavailable'; END IF; ";
  String deleted="UPDATE "+q+".heads SET alive=0 WHERE table_name='"+table+"' AND row_id=OLD."+pk+"; INSERT INTO "+q+".events(table_name,row_id,generation,operation) VALUES('"+table+"',OLD."+pk+",g,'D'); ";
  return "BEGIN DECLARE g CHAR(36) DEFAULT NULL; "+switch(event){case 'i'->insert;case 'd'->old+deleted;case 'u'->old+"IF NOT(OLD."+pk+" <=> NEW."+pk+") THEN "+deleted+insert+" ELSE INSERT INTO "+q+".events(table_name,row_id,generation,operation) VALUES('"+table+"',NEW."+pk+",g,'U'); END IF; ";default->throw new IllegalArgumentException("Invalid trigger");}+"END";
 }
 static void install(Connection connection,CompatibilityExtensions extension,Path receipt,char[] password)throws Exception{
  owned(connection);if(!connection.getAutoCommit())throw new IllegalArgumentException("Installation requires a dedicated connection");
  if(Files.exists(receipt)||Files.exists(receipt.resolveSibling(receipt.getFileName()+".json.partial")))throw new FileAlreadyExistsException("Receipt already exists; no installation performed");
  CompatibilityCatalog.requireProfile(connection,"canonical-120");if(triggers(connection).size()!=0)throw new IllegalArgumentException("Existing triggers: controlled copy is not fresh");
  exactOriginal(connection,extension);
  String tracking="ems_p1_track_"+UUID.randomUUID().toString().replace("-",""),epoch=UUID.randomUUID().toString(),definer="ems_p1_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
  String business=Snapshot.q(connection.getCatalog()),q=Snapshot.q(tracking);
  // DDL implicitly commits. A partial installation is retained and must never start the old app.
  // No existing schema/user/trigger is replaced, and the old application's grants are untouched.
  try(var st=connection.createStatement()){
   st.execute("CREATE DATABASE "+q+" CHARACTER SET utf8mb4");
   st.execute("CREATE TABLE "+q+".metadata(id INT PRIMARY KEY,epoch CHAR(36) NOT NULL,batch_id CHAR(36) NOT NULL,base_sha CHAR(64) NOT NULL) ENGINE=InnoDB");
   st.execute("CREATE TABLE "+q+".heads(table_name VARCHAR(64) NOT NULL,row_id BIGINT NOT NULL,generation CHAR(36) NOT NULL,alive TINYINT NOT NULL,PRIMARY KEY(table_name,row_id)) ENGINE=InnoDB");
   st.execute("CREATE TABLE "+q+".events(seq BIGINT PRIMARY KEY AUTO_INCREMENT,table_name VARCHAR(64) NOT NULL,row_id BIGINT NOT NULL,generation CHAR(36) NOT NULL,operation CHAR(1) NOT NULL) ENGINE=InnoDB");
   st.execute("CREATE USER '"+definer+"'@'localhost' ACCOUNT LOCK");
   st.execute("GRANT SELECT,INSERT,UPDATE ON "+q+".* TO '"+definer+"'@'localhost'");
   st.execute("GRANT SELECT,TRIGGER ON "+business+".* TO '"+definer+"'@'localhost'");
   try(var query=connection.prepareStatement("INSERT INTO "+q+".metadata VALUES(1,?,?,?)")){query.setString(1,epoch);query.setString(2,extension.batchId());query.setString(3,extension.originalBaseSha256());query.executeUpdate();}
   for(String table:CompatibilityArchive.BASE_TABLES){
    st.executeUpdate("INSERT INTO "+q+".heads(table_name,row_id,generation,alive) SELECT '"+table+"',"+Snapshot.q(CompatibilityCatalog.primary(table))+",UUID(),1 FROM "+Snapshot.q(table));
    for(char event:new char[]{'i','u','d'})st.execute("CREATE DEFINER='"+definer+"'@'localhost' TRIGGER "+Snapshot.q(triggerName(table,event))+" AFTER "+(event=='i'?"INSERT":event=='u'?"UPDATE":"DELETE")+" ON "+Snapshot.q(table)+" FOR EACH ROW "+triggerBody(tracking,table,event));
   }
  }
  ObjectNode body=Snapshot.JSON.createObjectNode();body.put("format","EMS-P1-LINEAGE-RECEIPT-1");body.put("purpose","initial-lineage");body.put("batchId",extension.batchId());body.put("trackingSchema",tracking);body.put("businessSchema",connection.getCatalog());body.put("epoch",epoch);body.set("triggers",triggers(connection));body.set("baseline",heads(connection,tracking));
  var envelope=Snapshot.JSON.createObjectNode();envelope.set("body",body);envelope.put("authentication",extension.authenticate(extension.originalBaseSha256(),hash(body)));writeEncrypted(envelope,receipt,password);
 }
 static JsonNode validateReceipt(Connection connection,CompatibilityExtensions extension,JsonNode envelope)throws Exception{
  JsonNode body=envelope.path("body");
  if(!"EMS-P1-LINEAGE-RECEIPT-1".equals(body.path("format").asText())||!extension.batchId().equals(body.path("batchId").asText())||!extension.verify(extension.originalBaseSha256(),hash(body),envelope.path("authentication").asText()))throw new IllegalArgumentException("Untrusted lineage receipt");
  String tracking=body.path("trackingSchema").asText();
  if(!tracking.matches("ems_p1_track_[a-f0-9]{32}")||!connection.getCatalog().equals(body.path("businessSchema").asText()))throw new IllegalArgumentException("Wrong controlled copy");
  if(!body.path("triggers").equals(triggers(connection))||body.path("triggers").size()!=51)throw new IllegalArgumentException("Tracking was interrupted or replaced");
  try(var st=connection.createStatement();var rows=st.executeQuery("SELECT epoch,batch_id,base_sha FROM "+Snapshot.q(tracking)+".metadata WHERE id=1")){
   if(!rows.next()||!body.path("epoch").asText().equals(rows.getString(1))||!extension.batchId().equals(rows.getString(2))||!extension.originalBaseSha256().equals(rows.getString(3))||rows.next())throw new IllegalArgumentException("Tracking generation was replaced");
  }return body;
 }
 static void exportReturn(Connection connection,CompatibilityExtensions extension,JsonNode receipt,Path workspace,char[] password)throws Exception{
  owned(connection);if(!connection.getAutoCommit())throw new IllegalArgumentException("Return export requires a dedicated connection");Files.createDirectory(workspace);
  Path raw=workspace.resolve("return.zip");ObjectNode proof=Snapshot.JSON.createObjectNode();Exception primary=null;
  try{
   connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
   for(String table:CompatibilityArchive.BASE_TABLES)try(var st=connection.createStatement();var rows=st.executeQuery("SELECT 1 FROM "+Snapshot.q(table)+" WHERE 1=0")){}
   CompatibilityCatalog.requireProfile(connection,"canonical-120");var initial=validateReceipt(connection,extension,receipt);
   Snapshot.write(connection,raw,()->{
    // Ledger and all business rows use the same RR snapshot. DDL is blocked by metadata locks.
    proof.put("format","EMS-P1-RETURN-PROOF-1");proof.put("purpose","return-lineage");proof.put("batchId",extension.batchId());proof.set("receipt",receipt);proof.set("heads",snapshotHeads(connection,initial.path("trackingSchema").asText()));
    try(var st=connection.createStatement();var rows=st.executeQuery("SELECT COALESCE(MAX(seq),0),COUNT(*) FROM "+Snapshot.q(initial.path("trackingSchema").asText())+".events")){rows.next();proof.put("checkpoint",rows.getString(1));proof.put("events",rows.getString(2));}
   });
   BackupCrypto.encrypt(raw,workspace.resolve("return-v120.emsbk"),password);
   proof.put("returnBaseSha256",BackupCrypto.sha256(workspace.resolve("return-v120.emsbk")));
   var envelope=Snapshot.JSON.createObjectNode();envelope.set("body",proof);envelope.put("authentication",extension.authenticate(proof.path("returnBaseSha256").asText(),hash(proof)));writeEncrypted(envelope,workspace.resolve("return-proof.emsbk"),password);
  }catch(Exception failure){primary=failure;throw failure;}finally{
   Exception cleanup=null;
   for(CompatibilityArchive.CheckedCleanup action:List.<CompatibilityArchive.CheckedCleanup>of(connection::rollback,()->connection.setAutoCommit(true),()->Files.deleteIfExists(raw))){try{action.run();}catch(Exception failure){if(cleanup==null)cleanup=failure;else cleanup.addSuppressed(failure);}}
   if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
  }
 }
 static final class VerifiedReturn {
  private final JsonNode value;
  private VerifiedReturn(JsonNode value){this.value=value.deepCopy();}
  JsonNode body(){return value.deepCopy();}
  @Override public String toString(){return "VerifiedReturn[private proof redacted]";}
 }
 static VerifiedReturn verifyReturn(CompatibilityExtensions extension,Path base,JsonNode envelope)throws Exception{
  JsonNode body=envelope.path("body");String sha=BackupCrypto.sha256(base);
  if(!"EMS-P1-RETURN-PROOF-1".equals(body.path("format").asText())||!extension.batchId().equals(body.path("batchId").asText())||!sha.equals(body.path("returnBaseSha256").asText())||!extension.verify(sha,hash(body),envelope.path("authentication").asText()))throw new IllegalArgumentException("Return backup lacks authenticated lineage");
  JsonNode receipt=body.path("receipt");if(!extension.verify(extension.originalBaseSha256(),hash(receipt.path("body")),receipt.path("authentication").asText()))throw new IllegalArgumentException("Invalid original lineage anchor");return new VerifiedReturn(body);
 }
}
