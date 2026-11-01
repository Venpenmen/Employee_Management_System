package com.example.ems.network;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.embedded.tomcat.*;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import jakarta.servlet.http.*;
import java.nio.file.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
/** Actual production late-connector path. Synthetic loopback TLS, never system trust. */
class RemoteTlsConnectorTest {
 @TempDir static Path work;static Path store,anchor;static final String PASS="SyntheticTlsMaterial92";
 WebServer server;
 static int port()throws Exception{try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
 @BeforeAll static void certificate()throws Exception{
  store=work.resolve("fixture.p12");anchor=work.resolve("trust.cer");Path tool=Path.of(System.getProperty("java.home"),"bin/keytool.exe");assertTrue(Files.isRegularFile(tool));
  var p=new ProcessBuilder(tool.toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=ems-test.invalid","-ext","SAN=dns:ems-test.invalid","-storetype","PKCS12","-keystore",store.toString(),"-storepass",PASS,"-keypass",PASS,"-noprompt").redirectErrorStream(true).redirectOutput(work.resolve("keytool.log").toFile()).start();assertTrue(p.waitFor(45,TimeUnit.SECONDS));assertEquals(0,p.exitValue());
  var ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){ks.load(in,PASS.toCharArray());}Files.write(anchor,ks.getCertificate("test").getEncoded());
 }
 @AfterEach void stop(){if(server!=null)server.stop();}
 MockEnvironment environment(int http,int tls,String host){return new MockEnvironment().withProperty("server.port",""+http).withProperty("EMS_RC2_HTTPS_PORT",""+tls).withProperty("EMS_RC2_HTTPS_ENABLED","true").withProperty("EMS_RC2_SECURE_ORIGIN","https://"+host).withProperty("EMS_UPGRADE_ROOT",work.toString()).withProperty("EMS_UPGRADE_MODE","server").withProperty("EMS_INSTANCE_TOKEN","SyntheticControl01234567890123456789").withProperty("EMS_CONTROL_DIR",work.toString()).withProperty("EMS_RC2_KEYSTORE",store.toString()).withProperty("EMS_RC2_TRUST_CERTIFICATE",anchor.toString()).withProperty("EMS_TLS_STORE_PASSWORD",PASS);}
 JsonNode run(MockEnvironment env)throws Exception{
  var policy=new Rc2NetworkPolicy(env);var configuration=new Rc2EntryConfiguration();var factory=new TomcatServletWebServerFactory();configuration.rc2Factory(policy,new LocalEntryAdmission(work,"SyntheticControl01234567890123456789",policy.bootId,policy.generation,ProcessHandle.current().pid(),Clock.systemUTC())).customize(factory);
  server=factory.getWebServer(context->{context.addListener(EntryIsolation.listener());context.addServlet("health",new HttpServlet(){protected void doGet(HttpServletRequest req,HttpServletResponse res)throws java.io.IOException{res.getWriter().write("UP");}}).addMapping("/health");});server.start();
  try(var context=new ServletWebServerApplicationContext(){@Override public WebServer getWebServer(){return server;}}){new Rc2EntryConfiguration.RemoteTlsLifecycle(env,policy).ready(new ApplicationReadyEvent(new SpringApplication(RemoteTlsConnectorTest.class),new String[0],context,Duration.ZERO));}
  try(var socket=new Socket("127.0.0.1",policy.httpPort)){socket.setSoTimeout(5000);socket.getOutputStream().write(("GET /health HTTP/1.0\r\nHost: localhost:"+policy.httpPort+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));assertTrue(new String(socket.getInputStream().readAllBytes()).endsWith("UP"));}
  return new ObjectMapper().readTree(work.resolve("network-runtime.json").toFile());
 }
 @Test void validPinnedCertificateAddsIndependentLateConnector()throws Exception{int h=port(),t;do{t=port();}while(h==t);assertEquals("READY",run(environment(h,t,"ems-test.invalid")).path("remoteState").asText());Rc2EntryConfiguration.RemoteTlsLifecycle.verifyPeer(anchor,"ems-test.invalid",t);}
 @Test void wrongCertificateHostnameDisablesOnlyRemoteEntry()throws Exception{int h=port(),t;do{t=port();}while(h==t);String envPort=""+t;assertEquals("FAILED",run(environment(h,t,"wrong-test.invalid")).path("remoteState").asText());assertThrows(Exception.class,()->Rc2EntryConfiguration.RemoteTlsLifecycle.verifyPeer(anchor,"wrong-test.invalid",Integer.parseInt(envPort)));}
 @Test void occupiedTlsPortNeverKillsLocalOrTouchesExistingListener()throws Exception{try(var occupied=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){assertEquals("FAILED",run(environment(port(),occupied.getLocalPort(),"ems-test.invalid")).path("remoteState").asText());assertFalse(occupied.isClosed());}}
}
