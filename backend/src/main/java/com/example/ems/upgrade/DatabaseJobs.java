package com.example.ems.upgrade;
import org.springframework.stereotype.Service;import org.springframework.core.env.Environment;import org.springframework.web.multipart.MultipartFile;
import com.example.ems.model.SessionUser;import com.example.ems.service.LogService;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.io.*;
@Service public class DatabaseJobs {
 private final Environment env;private final LogService logs;private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<String,Map<String,Object>> jobs=new ConcurrentHashMap<>();
 public DatabaseJobs(Environment e,LogService l){env=e;logs=l;}
 public boolean rc2(){return env.getProperty("ems.rc2.enabled",Boolean.class,false);}
 public String namespace(){return rc2()?"complete-rc2":"complete";}
 public Path directory(){return root().resolve("data/"+namespace()+"/"+mode());}
 public Path root(){
  String marker=rc2()?"app/version-complete-rc2.json":"app/version-complete.json",build=rc2()?"v1.2.2-local":"v1.2.1-complete-rc1";
  String configured=System.getenv("EMS_UPGRADE_ROOT");Path root=Path.of(configured==null?System.getProperty("user.dir"):configured).toAbsolutePath().normalize();
  if(!Files.exists(root.resolve(marker))&&Files.exists(root.resolve("../"+marker)))root=root.getParent();
  try{if(!Snapshot.JSON.readTree(root.resolve(marker).toFile()).path("buildId").asText().equals(build))throw new IllegalStateException();}catch(Exception e){throw new IllegalStateException("数据库管理需要本修正版独立安装上下文");}
  return root;
 }
 public String mode(){return Arrays.asList(env.getActiveProfiles()).contains("server")?"server":Arrays.asList(env.getActiveProfiles()).contains("portable")?"portable":"dev";}
 public Path job(String id){if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("任务编号错误");return directory().resolve("database-jobs/"+id);}
 void save(String id,String status,String message){
  var value=jobs.get(id);value.put("state",status);value.put("message",message);
  try{Path part=job(id).resolve("status.tmp");Snapshot.JSON.writeValue(part.toFile(),value);Files.move(part,job(id).resolve("status.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){throw new IllegalStateException("任务状态保存失败");}
 }
 String create(String action,SessionUser actor)throws Exception{
  String id=UUID.randomUUID().toString();Files.createDirectories(job(id).getParent());Files.createDirectory(job(id));
  Map<String,Object> value=new ConcurrentHashMap<>();value.put("id",id);value.put("action",action);value.put("actor",actor.id());value.put("state","QUEUED");jobs.put(id,value);save(id,"QUEUED","任务已排队");logs.log(actor.id(),"DATABASE_"+action.toUpperCase(),"DATABASE",null,"数据库任务："+id);return id;
 }
 void run(String id,String action,Map<String,String> secrets){
  worker.submit(()->{try{save(id,"RUNNING","正在处理");invoke(id,action,secrets);if(action.equals("restore")){
 var result=Snapshot.JSON.readTree(job(id).resolve("result.json").toFile());Map<String,Long> counts=new TreeMap<>();result.path("tables").fields().forEachRemaining(e->counts.put(e.getKey(),e.getValue().path("rows").asLong()));
 var preview=new LinkedHashMap<String,Object>();preview.put("programVersion",result.path("programVersion").asText("unknown"));preview.put("tables",counts);preview.put("compatible",!result.path("blocking").asBoolean());
 for(String key:List.of("identifiedProfile","recoveryMode","baseOnlyRequired","skippableConflicts","blocking","extensions","mapping"))if(result.has(key))preview.put(key,Snapshot.JSON.convertValue(result.get(key),Object.class));
 if(Files.exists(job(id).resolve("confirmation.json"))){var receipt=Snapshot.JSON.readTree(job(id).resolve("confirmation.json").toFile());preview.put("receiptDigest",LineageTracker.hash(receipt));preview.put("expiresAt",receipt.path("expiresAt").asLong());}
 jobs.get(id).put("preview",preview);
 }boolean blocked=action.equals("restore")&&Snapshot.JSON.readTree(job(id).resolve("result.json").toFile()).path("blocking").asBoolean();save(id,blocked?"CONFLICTS_BLOCKING":action.equals("restore")?"PREVIEW_READY":"SUCCEEDED",blocked?"存在安全或身份冲突，原库未改变，不能切换":action.equals("restore")?"校验通过，等待确认切换":"已完成");}catch(Exception e){var detail=JobDiagnostics.read(job(id),id);jobs.get(id).put("diagnostic",detail);save(id,"FAILED",detail.get("message")+"。原库未切换。错误编号："+id);org.slf4j.LoggerFactory.getLogger(DatabaseJobs.class).error("Database job {} failed: code={} phase={} SQLState={} vendorCode={}",id,detail.get("code"),detail.get("phase"),detail.get("sqlState"),detail.get("vendorCode"));}finally{secrets.clear();}});
 }
 public synchronized String export(SessionUser u,String pass,String hash)throws Exception{notSwitching();password(pass);String id=create("export",u);run(id,"export",new HashMap<>(Map.of("EMS_BACKUP_PASSWORD",pass,"EMS_MAINT_ACTOR_ID",u.id().toString(),"EMS_MAINT_ACTOR_HASH",hash)));return id;}
 boolean compatibilityEnabled(){return env.getProperty("ems.compatibility.enabled",Boolean.class,false);}
 void requireCompatibility(){if(!compatibilityEnabled())throw new IllegalArgumentException("兼容转换尚未在此候选版本启用");}
 public synchronized String export(SessionUser u,String pass,String hash,String target)throws Exception{
  validateTarget(target);if("current".equals(target))return export(u,pass,hash);requireCompatibility();notSwitching();password(pass);String id=create("export",u);jobs.get(id).put("targetVersion",target);run(id,"export",new HashMap<>(Map.of("EMS_BACKUP_PASSWORD",pass,"EMS_MAINT_ACTOR_ID",u.id().toString(),"EMS_MAINT_ACTOR_HASH",hash,"EMS_EXPORT_TARGET_VERSION",target)));return id;
 }
 static void validateTarget(String target){if(!Set.of("current","1.2.0").contains(target))throw new IllegalArgumentException("不支持的目标版本");}
 static void copyUpload(MultipartFile file,Path target)throws IOException{
  if(file==null||file.isEmpty()||file.getSize()>512L*1024*1024)throw new IllegalArgumentException("备份文件须为非空且不超过512MiB");
  try(var in=file.getInputStream();var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[8192];long total=0;int n;while((n=in.read(buffer))!=-1){total+=n;if(total>512L*1024*1024)throw new IOException("Upload exceeds limit");out.write(buffer,0,n);}if(total==0)throw new IOException("Empty upload");}
 }
 public synchronized String preview(SessionUser u,MultipartFile file,MultipartFile extension,MultipartFile proof,String pass,String hash)throws Exception{
  if(!compatibilityEnabled()){if((extension!=null&&!extension.isEmpty())||(proof!=null&&!proof.isEmpty()))throw new IllegalArgumentException("扩展恢复尚未启用");return preview(u,file,pass,hash);}
  notSwitching();password(pass);if(proof!=null&&!proof.isEmpty()&&(extension==null||extension.isEmpty()))throw new IllegalArgumentException("回程证明必须与扩展包一起上传");
  long size=file.getSize()+(extension==null?0:extension.getSize())+(proof==null?0:proof.getSize());if(size>1024L*1024*1024)throw new IllegalArgumentException("上传合计不能超过1GiB");
  String id=create("import",u);try{copyUpload(file,job(id).resolve("input.emsbk"));if(extension!=null&&!extension.isEmpty())copyUpload(extension,job(id).resolve("extensions.emsbk"));if(proof!=null&&!proof.isEmpty())copyUpload(proof,job(id).resolve("return-proof.emsbk"));}
  catch(Exception failure){for(String name:List.of("input.emsbk","extensions.emsbk","return-proof.emsbk"))try{Files.deleteIfExists(job(id).resolve(name));}catch(IOException cleanup){failure.addSuppressed(cleanup);}save(id,"FAILED","文件接收失败，原库未改变");throw new IllegalArgumentException("文件接收失败，请重新上传",failure);}
  run(id,"restore",new HashMap<>(Map.of("EMS_BACKUP_PASSWORD",pass,"EMS_MAINT_ACTOR_ID",u.id().toString(),"EMS_MAINT_ACTOR_HASH",hash,"EMS_COMPATIBILITY_PREVIEW","true")));return id;
 }
 public synchronized String preview(SessionUser u,MultipartFile file,String pass,String hash)throws Exception{
  notSwitching();password(pass);if(file.isEmpty()||file.getSize()>512L*1024*1024)throw new IllegalArgumentException("备份文件须为非空且不超过512MiB");
  String id=create("import",u);
  try(var in=file.getInputStream()){Files.copy(in,job(id).resolve("input.emsbk"));}
  catch(IOException failure){
   try{Files.deleteIfExists(job(id).resolve("input.emsbk"));}
   catch(IOException cleanup){failure.addSuppressed(cleanup);}
   save(id,"FAILED","备份文件接收失败，原数据库未改变");
   throw new IllegalArgumentException("备份文件接收失败，请重新上传",failure);
  }
  run(id,"restore",new HashMap<>(Map.of("EMS_BACKUP_PASSWORD",pass,"EMS_MAINT_ACTOR_ID",u.id().toString(),"EMS_MAINT_ACTOR_HASH",hash)));return id;
 }
 void notSwitching()throws Exception{
  if(Files.exists(directory().resolve("maintenance.flag")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"数据库维护中");
  for(String id:jobs.keySet())if("SWITCHING".equals(status(id).get("state")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"数据库正在切换");
 }
 static void password(String p){if(p==null||p.length()<12||p.length()>256)throw new IllegalArgumentException("备份口令应为12至256字符");}
 public Map<String,Object> status(String id)throws Exception{
  Path file=job(id).resolve("status.json");
  if(Files.exists(file)){Map<String,Object> value=Snapshot.JSON.readValue(file.toFile(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});return value;}
  throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"任务不存在");
 }
 static void requireImportOwner(Map<String,Object> task,Long actorId){
  if(!"import".equals(task.get("action"))||!Objects.equals(String.valueOf(task.get("actor")),String.valueOf(actorId)))
   throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"恢复任务不属于当前账号或任务类型不符");
 }
 public synchronized void commit(String id,SessionUser actor,String no,String password)throws Exception{
  if(Files.exists(job(id).resolve("confirmation.json")))throw new IllegalArgumentException("本任务需要绑定预览的明确选择");
  commitChecked(id,actor,no,password,Map.of());
 }
 String confirmationObject(String id,boolean baseOnly,boolean skip)throws Exception{
  var receipt=ConfirmationBinding.validate(job(id),baseOnly,skip,java.time.Instant.now());return ConfirmationBinding.object(id,receipt,baseOnly,skip);
 }
 public synchronized void commit(String id,SessionUser actor,String no,String password,boolean baseOnly,boolean skip)throws Exception{
  var receipt=ConfirmationBinding.validate(job(id),baseOnly,skip,java.time.Instant.now());commitChecked(id,actor,no,password,Map.of("EMS_CONFIRM_RECEIPT",LineageTracker.hash(receipt),"EMS_CONFIRM_BASE_ONLY",String.valueOf(baseOnly),"EMS_CONFIRM_SKIP",String.valueOf(skip)));
 }
 private void commitChecked(String id,SessionUser actor,String no,String password,Map<String,String> confirmation)throws Exception{
  for(String other:jobs.keySet()){if(!other.equals(id)&&Set.of("QUEUED","RUNNING","SWITCHING").contains(status(other).get("state")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"另一个数据库任务尚未结束");}
  var status=status(id);
  requireImportOwner(status,actor.id());
  if(!status.get("state").equals("PREVIEW_READY"))throw new IllegalArgumentException("任务尚未校验完成或已经提交");
  invoke(id,"verify-admin",new HashMap<>(Map.of("EMS_RESTORE_NO",no,"EMS_RESTORE_PASSWORD",password)));
  if(!jobs.containsKey(id))jobs.put(id,new ConcurrentHashMap<>(status));save(id,"SWITCHING","进入维护并切换；完成后请使用备份账号重新登录");
  logs.log(actor.id(),"DATABASE_IMPORT_COMMIT","DATABASE",null,"确认数据库切换："+id);
  ProcessBuilder pb=builder(id,"commit");pb.environment().putAll(confirmation);pb.redirectOutput(job(id).resolve("broker.log").toFile()).redirectErrorStream(true);pb.start();
 }
 ProcessBuilder builder(String id,String action){
  Path ps=Path.of(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe");
  ProcessBuilder builder=new ProcessBuilder(ps.toString(),"-NoProfile","-ExecutionPolicy","Bypass","-File",root().resolve("server-"+namespace()+"/database-job.ps1").toString(),"-Mode",mode(),"-Action",action,"-Job",job(id).toString()).directory(root().toFile());
  // Java does not apply PowerShell's native PS7 -> Windows PowerShell module-path
  // compatibility adjustment. Let this child reconstruct its own system modules.
  windowsShellEnvironment(builder.environment());
  var attrs=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
  if(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes a){String observed=DatabaseAccess.observedHash(a.getRequest());Object observedId=a.getRequest().getAttribute("ems.auth.observedUserId");builder.environment().put("EMS_MAINT_ACTOR_ID",observedId.toString());builder.environment().put("EMS_MAINT_ACTOR_HASH",observed);}
  return builder;
 }
 static void windowsShellEnvironment(Map<String,String> environment){
  Set<String> scoped=Set.of("PSMODULEPATH","PSMODULEANALYSISCACHEPATH","EMS_BACKUP_PASSWORD","EMS_EXPORT_TARGET_VERSION","EMS_COMPATIBILITY_PREVIEW","EMS_CONFIRM_RECEIPT","EMS_CONFIRM_BASE_ONLY","EMS_CONFIRM_SKIP","EMS_RESTORE_NO","EMS_RESTORE_PASSWORD","EMS_MAINT_ACTOR_ID","EMS_MAINT_ACTOR_HASH");
  environment.keySet().removeIf(key->scoped.contains(key.toUpperCase(Locale.ROOT)));
 }
 void invoke(String id,String action,Map<String,String> secrets)throws Exception{
  var pb=builder(id,action);pb.environment().putAll(secrets);pb.redirectOutput(job(id).resolve(action+".log").toFile()).redirectErrorStream(true);
  Process p=pb.start();if(!p.waitFor(650,TimeUnit.SECONDS)){JobDiagnostics.phase("timeout");Snapshot.JSON.writeValue(job(id).resolve("failure.json").toFile(),JobDiagnostics.describe(id,new JobDiagnostics.Failure("TIMEOUT","",""),"UNCHANGED"));throw new IllegalStateException("Maintenance timed out; keep task for inspection");}if(p.exitValue()!=0){var detail=JobDiagnostics.read(job(id),id);throw new IllegalArgumentException(detail.get("message")+"；阶段："+detail.get("phase")+"；错误编号："+id);}
 }
 public Path download(String id)throws Exception{var state=status(id);if(!"export".equals(state.get("action"))||!"SUCCEEDED".equals(state.get("state")))throw new IllegalArgumentException("导出尚未完成");Path result=job(id).resolve("backup.emsbk");if(!Files.isRegularFile(result))throw new IllegalArgumentException("文件不可用");return result;}
 public synchronized Path downloadBundle(String id)throws Exception{var state=status(id);if("1.2.0".equals(state.get("targetVersion"))){if(!"export".equals(state.get("action"))||!"SUCCEEDED".equals(state.get("state")))throw new IllegalArgumentException("导出尚未完成");return BackupBundle.compatibility(job(id).resolve("compatibility-export"),job(id).resolve("EMS-Compatibility.zip"));}Path backup=download(id);Path zip=job(id).resolve("EMS-Backup.zip");return BackupBundle.create(backup,zip);}
 @jakarta.annotation.PreDestroy public void close(){worker.shutdown();}
}
