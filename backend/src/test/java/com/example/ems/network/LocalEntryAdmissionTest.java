package com.example.ems.network;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class LocalEntryAdmissionTest {
 @TempDir Path root;final String key="SyntheticLocalControlKey0123456789",code="A".repeat(32),boot="test-boot",generation="test-generation";final long now=2000000;
 LocalEntryAdmission admission(){return new LocalEntryAdmission(root,key,boot,generation,42,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));}
 Map<String,Object> record(){var value=new LinkedHashMap<String,Object>();value.put("pid",42L);value.put("bootId",boot);value.put("generation",generation);value.put("nonce",UUID.randomUUID().toString());value.put("expires",now+60000);value.put("digest",LocalEntryAdmission.digest(code));return value;}
 void write(Map<String,Object> v)throws Exception{String canonical=v.get("pid")+"|"+v.get("bootId")+"|"+v.get("generation")+"|"+v.get("nonce")+"|"+v.get("expires")+"|"+v.get("digest");v.put("mac",LocalEntryAdmission.sign(key,canonical));new ObjectMapper().writeValue(root.resolve("local-entry-request.json").toFile(),v);}
 @Test void correctCodeIsSingleUseAndNeverWrittenClear()throws Exception{write(record());var a=admission();assertFalse(Files.readString(root.resolve("local-entry-request.json")).contains(code));assertTrue(a.redeem(code));assertFalse(a.redeem(code));}
 @Test void wrongCodeDoesNotConsumeLegitimateCode()throws Exception{write(record());var a=admission();assertFalse(a.redeem("B".repeat(32)));assertTrue(a.redeem(code));}
 @Test void expiryAndTooLongTtlRefused()throws Exception{var a=admission();for(long expiry:new long[]{now,now-1,now+120001}){var v=record();v.put("expires",expiry);write(v);assertFalse(a.redeem(code));}}
 @Test void signedWrongBootGenerationOrPidRefused()throws Exception{var a=admission();for(String field:List.of("bootId","generation","pid")){var v=record();v.put(field,field.equals("pid")?43L:"wrong");write(v);assertFalse(a.redeem(code));}}
 @Test void missingMalformedAndOversizedRequestsRefused()throws Exception{var a=admission();assertFalse(a.redeem(code));Files.writeString(root.resolve("local-entry-request.json"),"{broken");assertFalse(a.redeem(code));Files.writeString(root.resolve("local-entry-request.json"),"X".repeat(5000));assertFalse(a.redeem(code));}
 @Test void tamperedMacRefused()throws Exception{var v=record();write(v);v.put("mac","0".repeat(64));new ObjectMapper().writeValue(root.resolve("local-entry-request.json").toFile(),v);assertFalse(admission().redeem(code));}
 @Test void exactlyOneConcurrentRedemption()throws Exception{write(record());var a=admission();var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);Callable<Boolean> call=()->{start.await();return a.redeem(code);};var x=pool.submit(call);var y=pool.submit(call);start.countDown();assertNotEquals(x.get(5,TimeUnit.SECONDS),y.get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
 @Test void reasonsNameTheFailedCheck()throws Exception{var a=admission();assertEquals("proof-missing",a.redeemReason(code));var v=record();v.put("expires",now-1);write(v);assertEquals("proof-expired",a.redeemReason(code));write(record());assertNull(a.redeemReason(code));assertEquals("proof-replay",a.redeemReason(code));assertEquals("code-format",a.redeemReason("short"));}
 @Test void opaqueOriginAcceptedOnlyWhenRequestSaysSameOrigin(){
  String expected="http://127.0.0.1:8483";
  assertTrue(LocalEntryAdmission.originAccepted("http://127.0.0.1:8483",expected,"same-origin",null));
  assertTrue(LocalEntryAdmission.originAccepted("null",expected,"same-origin",null));
  assertTrue(LocalEntryAdmission.originAccepted(null,expected,"same-origin",null));
  assertTrue(LocalEntryAdmission.originAccepted("",expected,"none",null));
  assertFalse(LocalEntryAdmission.originAccepted("null",expected,"cross-site",null));
  assertFalse(LocalEntryAdmission.originAccepted("null",expected,null,null));
  assertFalse(LocalEntryAdmission.originAccepted("http://evil.invalid",expected,"same-origin",null));
  assertTrue(LocalEntryAdmission.originAccepted(null,expected,null,"http://127.0.0.1:8483/local-entry"));
  assertFalse(LocalEntryAdmission.originAccepted(null,expected,null,"http://evil.invalid/x"));
 }
}
