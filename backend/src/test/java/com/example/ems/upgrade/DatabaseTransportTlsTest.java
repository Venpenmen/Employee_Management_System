package com.example.ems.upgrade;

import com.example.ems.model.SessionUser;
import com.example.ems.service.AuthService;
import com.example.ems.service.SessionService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real loopback sockets and TLS, with a synthetic principal: transport boundary only,
 * not an end-to-end production login, FRP tunnel or certificate-provisioning test. */
class DatabaseTransportTlsTest {
 @TempDir Path work;

 private WebServer start(boolean tls, Path store, String password) {
  var sessions=mock(SessionService.class);
  var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  var access=new DatabaseAccess(sessions,mock(AuthService.class),mock(JdbcTemplate.class),manager);
  var factory=new TomcatServletWebServerFactory(0);
  factory.setAddress(java.net.InetAddress.getLoopbackAddress());
  if(tls){
   var ssl=new Ssl();ssl.setEnabled(true);ssl.setKeyStore(store.toUri().toString());
   ssl.setKeyStoreType("PKCS12");ssl.setKeyStorePassword(password);ssl.setKeyPassword(password);
   ssl.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});factory.setSsl(ssl);
  }
  WebServer server=factory.getWebServer(context->{
   var servlet=context.addServlet("transport-check",new HttpServlet(){
    @Override protected void doGet(HttpServletRequest req,HttpServletResponse res) throws java.io.IOException {
     // Test fixture identity is never used by the application or an external listener.
     if(!req.getRequestURI().equals("/anonymous")){
      req.getSession().setAttribute("user",new SessionUser(1L,"260001","虚构高层",2,null,""));
      req.getSession().setAttribute(SessionService.TOKEN,"a".repeat(43));
     }
     try{access.user(req);res.getWriter().write(req.getRequestURI().equals("/health")?"{\"status\":\"UP\"}":"allowed secure="+req.isSecure());}
     catch(ResponseStatusException denied){res.setStatus(denied.getStatusCode().value());}
    }
   });servlet.addMapping("/*");
  });server.start();return server;
 }

 @Test void realHttpRejectsSpoofedProxyMetadata() throws Exception {
  WebServer server=start(false,null,null);
  try{
   var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
   var request=HttpRequest.newBuilder(URI.create("http://localhost:"+server.getPort()+"/check"))
     .header("Forwarded","for=203.0.113.9;proto=https")
     .header("X-Forwarded-Proto","https").header("X-Forwarded-For","203.0.113.9").build();
   assertEquals(403,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
  }finally{server.stop();}
 }

 @Test void realTlsIsSecureAndStillRequiresExecutiveIdentity() throws Exception {
  String password=UUID.randomUUID().toString();Path store=work.resolve("isolated-test.p12");
  Path keytool=Path.of(System.getProperty("java.home"),"bin","keytool.exe");
  if(!Files.exists(keytool))keytool=Path.of(System.getProperty("java.home"),"bin","keytool");
  assertTrue(Files.isExecutable(keytool),"A complete test JDK with keytool is required");
  // Password environment indirection avoids command-line disclosure. Key never leaves @TempDir.
  var builder=new ProcessBuilder(keytool.toString(),"-genkeypair","-alias","isolated-test",
    "-keyalg","RSA","-keysize","2048","-storetype","PKCS12","-keystore",store.toString(),
    "-storepass:env","EMS_TLS_TEST_STORE_PASS","-keypass:env","EMS_TLS_TEST_STORE_PASS",
    "-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1,ip:::1","-validity","1","-noprompt");
  builder.environment().put("EMS_TLS_TEST_STORE_PASS",password);
  builder.redirectErrorStream(true).redirectOutput(work.resolve("keytool.log").toFile());
  var process=builder.start();boolean completed=process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS);
  if(!completed){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}
  assertTrue(completed,"Test certificate generation timed out");
  assertEquals(0,process.exitValue(),"Test certificate generation failed");
  KeyStore keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,password.toCharArray());}
  KeyStore trusted=KeyStore.getInstance(KeyStore.getDefaultType());trusted.load(null,null);
  trusted.setCertificateEntry("isolated-test",keys.getCertificate("isolated-test"));
  var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trusted);
  var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);
  var client=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
  WebServer server=start(true,store,password);
  try{
   var uri="https://localhost:"+server.getPort();
   var response=client.send(HttpRequest.newBuilder(URI.create(uri+"/check")).build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode());assertEquals("allowed secure=true",response.body());
   assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(uri+"/anonymous")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
   // Default JVM trust must reject this test-only certificate; no global trust-all callback is installed.
   assertThrows(javax.net.ssl.SSLHandshakeException.class,()->HttpClient.newHttpClient().send(
     HttpRequest.newBuilder(URI.create(uri+"/check")).build(),HttpResponse.BodyHandlers.ofString()));
   Path anchor=work.resolve("health-anchor.der");Files.write(anchor,keys.getCertificate("isolated-test").getEncoded());
   assertTrue(com.example.ems.tools.TlsHealth.check("127.0.0.1",server.getPort(),anchor),"Launcher health uses an explicit local trust anchor and genuine TLS");
   assertTrue(com.example.ems.tools.TlsHealth.check("127.0.0.1",server.getPort(),anchor,"https://localhost:"+server.getPort()),"Public hostname is authenticated over the recorded numeric loopback endpoint without DNS");
   assertThrows(javax.net.ssl.SSLHandshakeException.class,()->com.example.ems.tools.TlsHealth.check("127.0.0.1",server.getPort(),anchor,"https://wrong-host.invalid"),"A trusted certificate must still match the expected public hostname");
   var keyManagers=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());keyManagers.init(keys,password.toCharArray());var frontTls=SSLContext.getInstance("TLS");frontTls.init(keyManagers.getKeyManagers(),tm.getTrustManagers(),null);
   var proxy=com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0),0);proxy.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(frontTls));
   proxy.createContext("/",exchange->{try{
    // Fixed local upstream; client forwarding metadata is deliberately not copied.
    String route=exchange.getRequestURI().getPath();if(!java.util.Set.of("/check","/anonymous").contains(route)){exchange.sendResponseHeaders(404,-1);return;}
    var upstream=client.send(HttpRequest.newBuilder(URI.create(uri+route)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());exchange.sendResponseHeaders(upstream.statusCode(),upstream.body().length);exchange.getResponseBody().write(upstream.body());
   }catch(Exception failure){exchange.sendResponseHeaders(502,-1);}finally{exchange.close();}});proxy.start();
   try{String front="https://localhost:"+proxy.getAddress().getPort();var through=client.send(HttpRequest.newBuilder(URI.create(front+"/check")).header("X-Forwarded-Proto","http").header("Forwarded","for=203.0.113.20;proto=https").build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,through.statusCode());assertEquals("allowed secure=true",through.body());assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(front+"/anonymous")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());}
   finally{proxy.stop(0);}
  }finally{server.stop();}
 }
}
