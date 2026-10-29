package com.example.ems.upgrade;
import org.springframework.stereotype.Service;import org.springframework.core.env.Environment;import org.springframework.web.multipart.MultipartFile;
import com.example.ems.model.SessionUser;import com.example.ems.service.LogService;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.io.*;
@Service public class DatabaseJobs {
 private final Environment env;private final LogService logs;private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<String,Map<String,Object>> jobs=new ConcurrentHashMap<>();
 public DatabaseJobs(Environment e,LogService l){env=e;logs=l;}
 public Path root(){
  String configured=System.getenv("EMS_UPGRADE_ROOT");Path root=Path.of(configured==null?System.getProperty("user.dir"):configured).toAbsolutePath().normalize();
  if(!Files.exists(root.resolve("app/version.json"))&&Files.exists(root.resolve("../app/version.json")))root=root.getParent();
  try{if(!Snapshot.JSON.readTree(root.resolve("app/version.json").toFile()).path("buildId").asText().equals("v1.2.0-rc1"))throw new IllegalStateException();}catch(Exception e){throw new IllegalStateException("数据库管理需要本修正版独立安装上下文");}
  return root;
 }
 public String mode(){return Arrays.asList(env.getActiveProfiles()).contains("server")?"server":Arrays.asList(env.getActiveProfiles()).contains("portable")?"portable":"dev";}
 public Path job(String id){if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("任务编号错误");return root().resolve("data/"+mode()+"/database-jobs/"+id);}
 void save(String id,String status,String message){
  var value=jobs.get(id);value.put("state",status);value.put("message",message);
  try{Path part=job(id).resolve("status.tmp");Snapshot.JSON.writeValue(part.toFile(),value);Files.move(part,job(id).resolve("status.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){throw new IllegalStateException("任务状态保存失败");}
 }
 String create(String action,SessionUser actor)throws Exception{
  String id=UUID.randomUUID().toString();Files.createDirectories(job(id));
  Map<String,Object> value=new ConcurrentHashMap<>();value.put("id",id);value.put("action",action);value.put("actor",actor.id());value.put("state","QUEUED");jobs.put(id,value);save(id,"QUEUED","任务已排队");logs.log(actor.id(),"DATABASE_"+action.toUpperCase(),"DATABASE",null,"数据库任务："+id);return id;
 }
 void run(String id,String action,Map<String,String> secrets){
  worker.submit(()->{try{save(id,"RUNNING","正在处理");invoke(id,action,secrets);if(action.equals("restore")){
 var result=Snapshot.JSON.readTree(job(id).resolve("result.json").toFile());Map<String,Long> counts=new TreeMap<>();result.path("tables").fields().forEachRemaining(e->counts.put(e.getKey(),e.getValue().path("rows").asLong()));
 jobs.get(id).put("preview",Map.of("programVersion",result.path("programVersion").asText("unknown"),"tables",counts,"compatible",true));
 }save(id,action.equals("restore")?"PREVIEW_READY":"SUCCEEDED",action.equals("restore")?"校验通过，等待确认切换":"已完成");}catch(Exception e){save(id,"FAILED","操作失败，原库保持不变。错误编号："+id);org.slf4j.LoggerFactory.getLogger(DatabaseJobs.class).error("Database job {} failed [{}]; see the job restore.log for sanitized cause chain",id,e.getClass().getSimpleName());}finally{secrets.clear();}});
 }
 public synchronized String export(SessionUser u,String pass,String hash)throws Exception{notSwitching();password(pass);String id=create("export",u);run(id,"export",new HashMap<>(Map.of("EMS_BACKUP_PASSWORD",pass,"EMS_MAINT_ACTOR_ID",u.id().toString(),"EMS_MAINT_ACTOR_HASH",hash)));return id;}
 public synchronized String preview(SessionUser u,MultipartFile file,String pass,String hash)throws Exception{
  notSwitching();password(pass);if(file.isEmpty()||file.getSize()>512L*1024*1024)throw new IllegalArgumentException("备份文件须为非空且不超过512MiB");
  String id=create("import",u);try(var in=file.getInputStream()){Files.copy(in,job(id).resolve("input.emsbk"));}
  run(id,"restore",new HashMap<>(Map.of("EMS_BACKUP_PASSWORD",pass,"EMS_MAINT_ACTOR_ID",u.id().toString(),"EMS_MAINT_ACTOR_HASH",hash)));return id;
 }
 void notSwitching()throws Exception{
  if(Files.exists(root().resolve("data/"+mode()+"/maintenance.flag")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"数据库维护中");
  for(String id:jobs.keySet())if("SWITCHING".equals(status(id).get("state")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"数据库正在切换");
 }
 static void password(String p){if(p==null||p.length()<12||p.length()>256)throw new IllegalArgumentException("备份口令应为12至256字符");}
 public Map<String,Object> status(String id)throws Exception{
  Path file=job(id).resolve("status.json");
  if(Files.exists(file)){Map<String,Object> value=Snapshot.JSON.readValue(file.toFile(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});return value;}
  throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"任务不存在");
 }
 public synchronized void commit(String id,SessionUser actor,String no,String password)throws Exception{
  for(String other:jobs.keySet()){if(!other.equals(id)&&Set.of("QUEUED","RUNNING","SWITCHING").contains(status(other).get("state")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"另一个数据库任务尚未结束");}
  var status=status(id);if(!status.get("state").equals("PREVIEW_READY"))throw new IllegalArgumentException("任务尚未校验完成或已经提交");
  invoke(id,"verify-admin",new HashMap<>(Map.of("EMS_RESTORE_NO",no,"EMS_RESTORE_PASSWORD",password)));
  if(!jobs.containsKey(id))jobs.put(id,new ConcurrentHashMap<>(status));save(id,"SWITCHING","进入维护并切换；完成后请使用备份账号重新登录");
  logs.log(actor.id(),"DATABASE_IMPORT_COMMIT","DATABASE",null,"确认数据库切换："+id);
  ProcessBuilder pb=builder(id,"commit");pb.redirectOutput(job(id).resolve("broker.log").toFile()).redirectErrorStream(true);pb.start();
 }
 ProcessBuilder builder(String id,String action){
  Path ps=Path.of(System.getenv("SystemRoot"),"System32/WindowsPowerShell/v1.0/powershell.exe");
  ProcessBuilder builder=new ProcessBuilder(ps.toString(),"-NoProfile","-ExecutionPolicy","Bypass","-File",root().resolve("server/database-job.ps1").toString(),"-Mode",mode(),"-Action",action,"-Job",job(id).toString()).directory(root().toFile());
  var attrs=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
  if(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes a){var session=a.getRequest().getSession(false);if(session!=null && session.getAttribute("user") instanceof SessionUser u){builder.environment().put("EMS_MAINT_ACTOR_ID",u.id().toString());builder.environment().put("EMS_MAINT_ACTOR_HASH",com.example.ems.service.SessionService.digest(com.example.ems.service.SessionService.token(session)));}}
  return builder;
 }
 void invoke(String id,String action,Map<String,String> secrets)throws Exception{
  var pb=builder(id,action);pb.environment().putAll(secrets);pb.redirectOutput(job(id).resolve(action+".log").toFile()).redirectErrorStream(true);
  Process p=pb.start();if(!p.waitFor(650,TimeUnit.SECONDS))throw new IllegalStateException("Maintenance timed out; keep task for inspection");if(p.exitValue()!=0)throw new IllegalStateException("Maintenance failed");
 }
 public Path download(String id)throws Exception{var state=status(id);if(!"export".equals(state.get("action"))||!"SUCCEEDED".equals(state.get("state")))throw new IllegalArgumentException("导出尚未完成");Path result=job(id).resolve("backup.emsbk");if(!Files.isRegularFile(result))throw new IllegalArgumentException("文件不可用");return result;}
 public synchronized Path downloadBundle(String id)throws Exception{Path backup=download(id);Path zip=job(id).resolve("EMS-Backup.zip");return BackupBundle.create(backup,zip);}
 @jakarta.annotation.PreDestroy public void close(){worker.shutdown();}
}
