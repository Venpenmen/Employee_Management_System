package com.example.ems.config;

import com.example.ems.upgrade.Snapshot;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** A test transport exception is valid only inside a live, owned managed fixture.
 * This is an accidental-deployment guard, not protection against a privileged local owner.
 */
public final class IsolatedFixture {
 private IsolatedFixture(){}
 public static boolean insecureCompatibilityAllowed(){
  if(!"compatibility".equalsIgnoreCase(System.getenv("EMS_NETWORK_MODE")))return false;
  String root=System.getenv("EMS_UPGRADE_ROOT"),scope=System.getenv("EMS_DEVELOPMENT_TASK_ID");
  if(root==null||scope==null||!valid(Path.of(root),scope))throw new IllegalStateException("不安全兼容模式仅允许当前受管理的隔离测试副本；普通安装禁止启动此模式");return true;
 }
 static boolean plain(Path path)throws Exception{
  for(Path parent=path.toAbsolutePath().normalize();parent!=null;parent=parent.getParent())if(Files.exists(parent,LinkOption.NOFOLLOW_LINKS)){
   var attributes=Files.readAttributes(parent,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   if(attributes.isSymbolicLink()||attributes.isOther())return false;
  }return true;
 }
 static long ticks(java.time.Instant time){return Math.addExact(621355968000000000L,Math.addExact(Math.multiplyExact(time.getEpochSecond(),10000000L),time.getNano()/100));}
 public static boolean valid(Path supplied,String scope){
  try{
   if(!scope.matches("[a-f0-9-]{36}")||!plain(supplied))return false;
   Path fixture=supplied.toAbsolutePath().normalize();if(!fixture.equals(fixture.toRealPath()))return false;
   Path run=fixture.getParent();
   // Nested fixtures are accepted only underneath an actual managed run, never by name alone.
   for(int level=0;level<4&&run!=null&&!Files.isRegularFile(run.resolve("run.json"));level++)run=run.getParent();
   if(run==null||!Files.isRegularFile(run.resolve("run.json"))||Files.size(run.resolve("run.json"))>65536)return false;
   var manifest=Snapshot.JSON.readTree(run.resolve("run.json").toFile());Path project=Path.of(manifest.path("root").asText()).toAbsolutePath().normalize();
   if(!plain(project)||!run.getParent().equals(project.resolve(".release-work/runs"))||!run.equals(Path.of(manifest.path("path").asText()).toAbsolutePath().normalize())||!manifest.path("id").asText().equals(run.getFileName().toString())||!fixture.startsWith(run)||fixture.equals(run))return false;
   if(!manifest.path("managed").asBoolean()||manifest.path("schema").asInt()!=1||!"running".equals(manifest.path("status").asText())||!scope.equals(manifest.path("ownerTaskId").asText()))return false;
   var process=ProcessHandle.of(manifest.path("ownerId").asLong()).orElseThrow();var info=process.info();
   // ProcessHandle exposes milliseconds on Windows; compare that explicit precision.
   // Destructive cleanup continues to use the original exact .NET creation ticks.
   if(!process.isAlive()||ticks(info.startInstant().orElseThrow())/10000!=Long.parseLong(manifest.path("ownerTicks").asText())/10000)return false;
   String executable=info.command().orElseThrow(),expected=manifest.path("ownerExecutable").asText();
   return !expected.isBlank()&&Files.isSameFile(Path.of(executable),Path.of(expected));
  }catch(Exception invalid){return false;}
 }
}
