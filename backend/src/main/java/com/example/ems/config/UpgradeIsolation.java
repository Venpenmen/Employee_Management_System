package com.example.ems.config;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
public final class UpgradeIsolation implements EnvironmentPostProcessor,Ordered{
 public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
 public void postProcessEnvironment(ConfigurableEnvironment e,SpringApplication a){
  String url=e.getProperty("spring.datasource.url","");
  if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(1660[6-9]|166[12][0-9]|16630)/ems_next_(dev|server|portable)_v121(_[a-z0-9]+)?(\\?.*)?"))throw new IllegalStateException("Upgrade build refuses nonisolated datasource");
  String mode=java.util.Arrays.asList(e.getActiveProfiles()).contains("server")?"server":java.util.Arrays.asList(e.getActiveProfiles()).contains("portable")?"portable":"dev";
  if(java.util.Arrays.asList(e.getActiveProfiles()).contains("server")&&java.util.Arrays.asList(e.getActiveProfiles()).contains("portable"))throw new IllegalStateException("Conflicting runtime modes");
  if(!url.contains("/ems_next_"+mode+"_v121"))throw new IllegalStateException("Datasource does not match active mode");
  int port=e.getProperty("server.port",Integer.class,8482);
  if(java.util.Set.of(8083,8183,8283,8381,8382,8383).contains(port))throw new IllegalStateException("Historical service ports are protected");
  boolean rc2=e.getProperty("ems.rc2.enabled",Boolean.class,false);
  String cookie=e.getProperty("server.servlet.session.cookie.name","");
  if(rc2?!cookie.equals("EMS121RC2_"+mode.toUpperCase(java.util.Locale.ROOT)+"_"+e.getProperty("EMS_RC2_MODE","safe").toUpperCase(java.util.Locale.ROOT)):!cookie.equals("EMS121N_COMPLETE_"+mode.toUpperCase(java.util.Locale.ROOT)))throw new IllegalStateException("Complete candidate requires its own mode cookie");
  if(rc2){new com.example.ems.network.Rc2NetworkPolicy(e);if(!"none".equals(e.getProperty("server.forward-headers-strategy","none")))throw new IllegalStateException("RC2 must use native connectors, never forwarded-header rewriting");}
  verifyInstallation(e,mode,url);
  ApplicationLease.hold(java.nio.file.Path.of(e.getRequiredProperty("EMS_UPGRADE_ROOT")).toAbsolutePath().normalize(),mode,rc2);
 }
 public static void verifyInstallation(ConfigurableEnvironment e,String mode,String url){
  try{
   boolean rc2=e.getProperty("ems.rc2.enabled",Boolean.class,false);String marker=rc2?"app/version-complete-rc2.json":"app/version-complete.json",data=rc2?"data/complete-rc2/":"data/complete/",build=rc2?"v1.2.2-local":"v1.2.1-complete-rc1";
   java.nio.file.Path root=java.nio.file.Path.of(e.getRequiredProperty("EMS_UPGRADE_ROOT")).toAbsolutePath().normalize();
   if(!IsolatedFixture.plain(root)||!root.equals(root.toRealPath())||!mode.equals(e.getRequiredProperty("EMS_UPGRADE_MODE")))throw new IllegalStateException();
   var version=com.example.ems.upgrade.Snapshot.JSON.readTree(root.resolve(marker).toFile());
   var state=com.example.ems.upgrade.Snapshot.JSON.readTree(root.resolve(data+mode+"/instance-upgrade.json").toFile());
   if(!build.equals(version.path("buildId").asText())||!build.equals(state.path("buildId").asText())||!root.equals(java.nio.file.Path.of(state.path("installRoot").asText()).toAbsolutePath().normalize())||!mode.equals(state.path("mode").asText()))throw new IllegalStateException();
   if(rc2&&!root.resolve(data+mode).equals(java.nio.file.Path.of(e.getRequiredProperty("EMS_CONTROL_DIR")).toAbsolutePath().normalize()))throw new IllegalStateException();
   var uri=java.net.URI.create(url.substring(5));if(uri.getPort()!=state.path("dbPort").asInt())throw new IllegalStateException();
   java.nio.file.Path pointer=root.resolve(data+mode+"/active-schema");String expected=java.nio.file.Files.exists(pointer)?java.nio.file.Files.readString(pointer).trim():"ems_next_"+mode+"_v121";
   if(!uri.getPath().equals("/"+expected))throw new IllegalStateException();
   var database=state.path("database");var process=ProcessHandle.of(database.path("id").asLong()).orElseThrow();var info=process.info();
   if(!process.isAlive()||IsolatedFixture.ticks(info.startInstant().orElseThrow())/10000!=Long.parseLong(database.path("ticks").asText())/10000)throw new IllegalStateException();
   java.nio.file.Path exe=root.resolve("runtime/mysql/bin/mysqld.exe");
   if(!java.nio.file.Files.isSameFile(exe,java.nio.file.Path.of(database.path("exe").asText()))||!java.nio.file.Files.isSameFile(exe,java.nio.file.Path.of(info.command().orElseThrow())))throw new IllegalStateException();
  }catch(Exception invalid){throw new IllegalStateException("Complete candidate requires its own initialized mode and live database identity; no connection attempted",invalid);}
 }
}
