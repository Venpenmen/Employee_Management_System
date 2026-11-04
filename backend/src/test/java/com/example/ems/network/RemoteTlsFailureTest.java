package com.example.ems.network;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class RemoteTlsFailureTest {
 @TempDir Path work;
 void verify(MockEnvironment env,String state)throws Exception{
  env.withProperty("EMS_UPGRADE_ROOT",work.toString()).withProperty("EMS_UPGRADE_MODE","server").withProperty("EMS_INSTANCE_TOKEN","SyntheticControl01234567890123456789").withProperty("EMS_CONTROL_DIR",work.toString());var p=new Rc2NetworkPolicy(env);
  try(var context=new GenericApplicationContext()){var event=new ApplicationReadyEvent(new SpringApplication(RemoteTlsFailureTest.class),new String[0],context,Duration.ZERO);new Rc2EntryConfiguration.RemoteTlsLifecycle(env,p).ready(event);}
  var saved=new ObjectMapper().readTree(work.resolve("network-runtime.json").toFile());assertEquals(state,saved.path("remoteState").asText());assertEquals(p.bootId,saved.path("bootId").asText());assertNotNull(p.entry(p.httpPort,false,false));assertFalse(saved.toString().contains("password"));
  String canonical=saved.path("pid").asText()+"|"+saved.path("startTicks").asText()+"|"+saved.path("exe").asText()+"|"+saved.path("installRoot").asText()+"|"+saved.path("runtimeMode").asText()+"|"+p.bootId+"|"+p.generation;assertEquals(LocalEntryAdmission.sign("SyntheticControl01234567890123456789",canonical),saved.path("identityMac").asText());assertTrue(saved.path("startTicks").asText().matches("[0-9]+"));
 }
 @Test void missingOriginKeepsOnlyGatedLocalEntry()throws Exception{verify(new MockEnvironment().withProperty("EMS_RC2_HTTPS_ENABLED","true"),"FAILED");}
 @Test void missingKeyMaterialDoesNotEnableRemoteHttpFallback()throws Exception{verify(new MockEnvironment().withProperty("EMS_RC2_HTTPS_ENABLED","true").withProperty("EMS_RC2_SECURE_ORIGIN","https://ems-test.invalid"),"FAILED");}
 @Test void noRemoteConfigurationNeedsNoCertificate()throws Exception{verify(new MockEnvironment(),"DISABLED");}
}
