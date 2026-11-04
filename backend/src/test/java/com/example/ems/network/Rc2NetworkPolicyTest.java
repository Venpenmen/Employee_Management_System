package com.example.ems.network;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Rc2NetworkPolicyTest {
 MockEnvironment env(){return new MockEnvironment().withProperty("server.port","54831").withProperty("EMS_RC2_HTTPS_PORT","54832");}
 @Test void defaultLocalSurvivesNoCertificateAndNoRemote(){var p=new Rc2NetworkPolicy(env());var e=p.entry(54831,false,false);assertNotNull(e);assertNull(p.entry(54832,true,true));assertTrue(p.permits(e,"127.0.0.1",List.of("localhost:54831"),"GET",null,null,null));assertTrue(p.permits(e,"127.0.0.1",List.of("127.0.0.1:54831"),"POST","http://127.0.0.1:54831",null,null));assertFalse(p.permits(e,"127.0.0.1",List.of("ems-test.invalid"),"GET",null,null,null));}
 @Test void compatibilityNeedsConsentAndAcceptsAnyHostThatMatchesItsOwnOrigin(){assertThrows(IllegalArgumentException.class,()->new Rc2NetworkPolicy(env().withProperty("EMS_RC2_MODE","compat")));var p=new Rc2NetworkPolicy(env().withProperty("EMS_RC2_MODE","compat").withProperty("EMS_RC2_TEST_ACK","true"));var e=p.entry(54831,false,false);assertTrue(e.testHttp());assertTrue(p.permits(e,"127.0.0.1",List.of("ems-peer-one.invalid:19001"),"POST","http://ems-peer-one.invalid:19001",null,null));assertTrue(p.permits(e,"127.0.0.1",List.of("ems-peer-two.invalid"),"GET",null,null,null));assertTrue(p.permits(e,"127.0.0.1",List.of("127.0.0.1:54831"),"POST","http://127.0.0.1:54831",null,null));assertFalse(p.permits(e,"127.0.0.1",List.of("ems-peer-one.invalid:19001"),"POST","http://evil.invalid",null,null));assertFalse(p.permits(e,"127.0.0.1",List.of("ems-peer-one.invalid:19001"),"POST",null,null,"cross-site"));assertFalse(p.permits(e,"192.0.2.1",List.of("ems-peer-two.invalid"),"GET",null,null,null));assertFalse(p.permits(e,"127.0.0.1",List.of("ems-peer-two.invalid","ems-peer-two.invalid"),"GET",null,null,null));}
 @Test void nativeTlsAndHttpHaveDistinctRealmsAndCookies(){var p=new Rc2NetworkPolicy(env().withProperty("EMS_RC2_HTTPS_ENABLED","true").withProperty("EMS_RC2_SECURE_ORIGIN","https://ems-test.invalid"));var h=p.entry(54831,false,false);var t=p.entry(54832,true,true);assertNotEquals(h.realm(),t.realm());assertNotEquals(h.cookieName(),t.cookieName());assertTrue(t.secure());assertFalse(h.secure());assertNull(p.entry(54831,false,true));assertNull(p.entry(54833,true,true));assertFalse(p.permits(h,"127.0.0.1",List.of("ems-test.invalid"),"GET",null,null,null));assertTrue(p.permits(t,"127.0.0.1",List.of("ems-test.invalid"),"POST","https://ems-test.invalid",null,null));}
 @Test void originDuplicatesCrossSiteAndExternalPeerCannotBypass(){var p=new Rc2NetworkPolicy(env());var h=p.entry(54831,false,false);assertFalse(p.permits(h,"127.0.0.1",List.of("localhost:54831","localhost:54831"),"GET",null,null,null));assertFalse(p.permits(h,"192.0.2.1",List.of("localhost:54831"),"GET",null,null,null));assertFalse(p.permits(h,"127.0.0.1",List.of("localhost:54831"),"POST","http://evil.invalid",null,null));assertFalse(p.permits(h,"127.0.0.1",List.of("localhost:54831"),"POST",null,null,"cross-site"));}
 @Test void unknownOrCorruptPoliciesFailClosed(){assertThrows(IllegalArgumentException.class,()->new Rc2NetworkPolicy(env().withProperty("EMS_RC2_MODE","lan")));var missing=new Rc2NetworkPolicy(env().withProperty("EMS_RC2_HTTPS_ENABLED","true"));assertNotNull(missing.remoteError);assertNotNull(missing.entry(54831,false,false));assertNull(missing.entry(54832,true,true));assertThrows(IllegalArgumentException.class,()->new Rc2NetworkPolicy(env().withProperty("EMS_RC2_HTTPS_PORT","54831")));}
 @Test void restartedInstanceCannotReuseEarlierRealm(){assertNotEquals(new Rc2NetworkPolicy(env()).entry(54831,false,false).realm(),new Rc2NetworkPolicy(env()).entry(54831,false,false).realm());}
 @Test void refusalsCarryDiagnosableReasons(){var p=new Rc2NetworkPolicy(env());var h=p.entry(54831,false,false);
  assertEquals("entry-port",p.refusal(null,"127.0.0.1",List.of(),"GET",null,null,null));
  assertEquals("remote-address",p.refusal(h,"192.0.2.1",List.of("localhost:54831"),"GET",null,null,null));
  assertEquals("host-count",p.refusal(h,"127.0.0.1",List.of("localhost:54831","localhost:54831"),"GET",null,null,null));
  assertEquals("host",p.refusal(h,"127.0.0.1",List.of("ems-test.invalid"),"GET",null,null,null));
  assertEquals("origin",p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST","http://evil.invalid",null,null));
  assertEquals("cross-site",p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST",null,null,"cross-site"));
  assertEquals("host-format",p.refusal(h,"127.0.0.1",List.of("localhost:54831/path"),"GET",null,null,null));
  assertEquals("origin-format",p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST","null",null,null));
  assertEquals("referer-format",p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST",null,"not a url",null));
  assertNull(p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST","HTTP://LocalHost:54831",null,null));
  assertNull(p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST",null,"http://localhost:54831/local-entry?x=1",null));
  assertNull(p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST","http://localhost:54831",null,null));
  assertEquals("origin-format",p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST","null",null,"same-site"));
  assertNull(p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST","null",null,"same-origin"));
  assertNull(p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST",null,"","same-origin"));
  assertEquals("origin-format",p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST","null",null,null));
  assertEquals("cross-site",p.refusal(h,"127.0.0.1",List.of("localhost:54831"),"POST","null",null,"cross-site"));}
 @Test void edgeTlsTunnelOnlyIgnoresTheSchemeNotTheHostOrPort(){
  var p=new Rc2NetworkPolicy(env().withProperty("EMS_RC2_MODE","compat").withProperty("EMS_RC2_TEST_ACK","true").withProperty("EMS_RC2_EDGE_TLS","true"));
  var e=p.entry(54831,false,false);assertTrue(p.edgeTls);assertTrue(e.testHttp());
  assertTrue(p.permits(e,"127.0.0.1",List.of("www.ems-public.invalid:14938"),"POST","https://www.ems-public.invalid:14938",null,"same-origin"));
  assertTrue(p.permits(e,"127.0.0.1",List.of("www.ems-public.invalid:14938"),"POST",null,"https://www.ems-public.invalid:14938/login","same-origin"));
  assertTrue(p.permits(e,"127.0.0.1",List.of("www.ems-public.invalid:14938"),"POST","http://www.ems-public.invalid:14938",null,"same-origin"));
  assertEquals("origin",p.refusal(e,"127.0.0.1",List.of("www.ems-public.invalid:14938"),"POST","https://evil.invalid:14938",null,"same-origin"));
  assertEquals("origin",p.refusal(e,"127.0.0.1",List.of("www.ems-public.invalid:14938"),"POST","https://www.ems-public.invalid:14939",null,"same-origin"));
  assertEquals("referer",p.refusal(e,"127.0.0.1",List.of("www.ems-public.invalid:14938"),"POST",null,"https://evil.invalid:14938/login","same-origin"));
  assertEquals("cross-site",p.refusal(e,"127.0.0.1",List.of("www.ems-public.invalid:14938"),"POST","https://www.ems-public.invalid:14938",null,"cross-site"));
  assertEquals("remote-address",p.refusal(e,"192.0.2.1",List.of("www.ems-public.invalid:14938"),"POST","https://www.ems-public.invalid:14938",null,"same-origin"));}
 @Test void edgeTlsStaysOffByDefaultAndOutsideCompatibilityMode(){
  var strict=new Rc2NetworkPolicy(env().withProperty("EMS_RC2_MODE","compat").withProperty("EMS_RC2_TEST_ACK","true"));var e=strict.entry(54831,false,false);
  assertFalse(strict.edgeTls);assertEquals("origin",strict.refusal(e,"127.0.0.1",List.of("ems-peer-one.invalid:19001"),"POST","https://ems-peer-one.invalid:19001",null,null));
  assertThrows(IllegalArgumentException.class,()->new Rc2NetworkPolicy(env().withProperty("EMS_RC2_EDGE_TLS","true")));
  assertThrows(RuntimeException.class,()->new Rc2NetworkPolicy(env().withProperty("EMS_RC2_MODE","compat").withProperty("EMS_RC2_TEST_ACK","true").withProperty("EMS_RC2_EDGE_TLS","not-a-boolean")));}}
