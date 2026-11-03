package com.example.ems.network;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual shared Tomcat manager + genuine TLS, synthetic servlet principal only.
 * Does not claim full JDBC login/session-summary or production FRP validation. */
class EntryIsolationContainerTest {
 @TempDir static Path work;static Path store;static SSLContext trusted;static final ObjectMapper JSON=new ObjectMapper();
 static final String KEY="SyntheticLocalControlKey0123456789",CODE="A".repeat(32),BOOT="fixture-boot";
 WebServer server;Rc2NetworkPolicy policy;int hp,tp;AtomicInteger destroyed;
 static int port()throws Exception{try(var s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return s.getLocalPort();}}
 @BeforeAll static void certificate()throws Exception{
  store=work.resolve("fixture.p12");String password="SyntheticTLSStore91";
  Path tool=Path.of(System.getProperty("java.home"),"bin","keytool.exe");assertTrue(Files.isRegularFile(tool),"Existing local JDK keytool required; never download");
  var process=new ProcessBuilder(tool.toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=ems-test.invalid","-ext","SAN=dns:ems-test.invalid","-storetype","PKCS12","-keystore",store.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(work.resolve("keytool.log").toFile()).start();
  assertTrue(process.waitFor(60,TimeUnit.SECONDS));assertEquals(0,process.exitValue());var keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,password.toCharArray());}
  var anchor=KeyStore.getInstance(KeyStore.getDefaultType());anchor.load(null,null);anchor.setCertificateEntry("test",keys.getCertificate("test"));var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(anchor);trusted=SSLContext.getInstance("TLS");trusted.init(null,tm.getTrustManagers(),null);
 }
 void start(String mode)throws Exception{
  hp=port();do{tp=port();}while(tp==hp);destroyed=new AtomicInteger();
  var env=new MockEnvironment().withProperty("server.port",String.valueOf(hp)).withProperty("EMS_RC2_HTTPS_PORT",String.valueOf(tp)).withProperty("EMS_RC2_MODE",mode).withProperty("EMS_RC2_HTTPS_ENABLED","true").withProperty("EMS_RC2_SECURE_ORIGIN","https://ems-test.invalid").withProperty("EMS_RC2_TEST_ACK","true").withProperty("EMS_RC2_TEST_ORIGIN","http://ems-test.invalid");
  policy=new Rc2NetworkPolicy(env);var factory=new TomcatServletWebServerFactory(hp);factory.setAddress(InetAddress.getByName("127.0.0.1"));
  var admission=new LocalEntryAdmission(work,KEY,BOOT,policy.generation,42,Clock.systemUTC());factory.addEngineValves(new EntryIsolation.Valve(policy,admission));factory.addContextCustomizers(c->{c.setCookieProcessor(new EntryIsolation.Cookies(policy.cookieBase));c.setSessionCookieName(policy.cookieBase);});
  var connector=new Connector(Http11NioProtocol.class.getName());connector.setPort(tp);connector.setScheme("https");connector.setSecure(true);var protocol=(Http11NioProtocol)connector.getProtocolHandler();protocol.setAddress(InetAddress.getByName("127.0.0.1"));protocol.setSSLEnabled(true);
  var ssl=new SSLHostConfig();ssl.setProtocols("TLSv1.2,TLSv1.3");var cert=new SSLHostConfigCertificate(ssl,SSLHostConfigCertificate.Type.RSA);cert.setCertificateKeystoreFile(store.toString());cert.setCertificateKeystoreType("PKCS12");cert.setCertificateKeystorePassword("SyntheticTLSStore91");cert.setCertificateKeyPassword("SyntheticTLSStore91");ssl.addCertificate(cert);connector.addSslHostConfig(ssl);factory.addAdditionalTomcatConnectors(connector);
  server=factory.getWebServer(context->{context.getSessionCookieConfig().setName(policy.cookieBase);context.setSessionTrackingModes(Set.of(jakarta.servlet.SessionTrackingMode.COOKIE));context.addListener(EntryIsolation.listener());context.addListener(new HttpSessionListener(){public void sessionDestroyed(HttpSessionEvent event){destroyed.incrementAndGet();}});
   context.addServlet("synthetic-principal",new HttpServlet(){
    protected void service(HttpServletRequest req,HttpServletResponse res)throws java.io.IOException{
     if(req.getRequestURI().equals("/health")){write(res,Map.of("status","UP"));return;}
     HttpSession s=req.getSession(false);if(req.getRequestURI().equals("/make")){s=req.getSession(true);s.setAttribute("csrf",UUID.randomUUID().toString());}
     if(req.getRequestURI().equals("/login")&&req.getMethod().equals("POST")){
      if(s==null||!Objects.equals(s.getAttribute("csrf"),req.getParameter("csrf"))){res.setStatus(403);return;}req.changeSessionId();s.setAttribute("principal","synthetic-user");
     }
     if(req.getRequestURI().equals("/logout")&&req.getMethod().equals("POST")){if(s==null||!Objects.equals(s.getAttribute("csrf"),req.getParameter("csrf"))){res.setStatus(403);return;}s.invalidate();res.setStatus(204);return;}
     if(s==null){res.setStatus(401);return;}write(res,Map.of("id",s.getId(),"csrf",Objects.toString(s.getAttribute("csrf"),""),"authenticated",s.getAttribute("principal")!=null,"realm",EntryIsolation.realm(req)));
    }
   }).addMapping("/*");});server.start();
 }
 static void write(HttpServletResponse response,Object data)throws java.io.IOException{byte[] b=JSON.writeValueAsBytes(data);response.setContentType("application/json");response.setContentLength(b.length);response.getOutputStream().write(b);}
 @AfterEach void stop(){if(server!=null)server.stop();}
 record Wire(int status,Map<String,String> cookies,String headers,String body){JsonNode json()throws Exception{return JSON.readTree(body);}String cookie(){return cookies.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).reduce((a,b)->a+"; "+b).orElse("");}}
 Wire request(boolean tls,String host,String path,String cookie,String body,String extra)throws Exception{
  Socket raw=new Socket();raw.connect(new InetSocketAddress("127.0.0.1",tls?tp:hp),5000);raw.setSoTimeout(10000);Socket connection=raw;
  if(tls){var secure=(SSLSocket)trusted.getSocketFactory().createSocket(raw,"ems-test.invalid",tp,true);var parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);secure.startHandshake();connection=secure;}
  try(Socket s=connection){String wire=(body==null?"GET":"POST")+" "+path+" HTTP/1.0\r\nHost: "+host+"\r\nConnection: close\r\n"+(cookie.isEmpty()?"":"Cookie: "+cookie+"\r\n")+extra+(body==null?"":"Content-Type: application/x-www-form-urlencoded\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\n")+"\r\n"+Objects.toString(body,"");s.getOutputStream().write(wire.getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();String answer=new String(s.getInputStream().readAllBytes(),StandardCharsets.UTF_8);String[] halves=answer.split("\r\n\r\n",2);Map<String,String> cookies=new LinkedHashMap<>();for(String line:halves[0].split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("set-cookie:")){String pair=line.substring(11).trim().split(";",2)[0];int at=pair.indexOf('=');cookies.put(pair.substring(0,at),pair.substring(at+1));}return new Wire(Integer.parseInt(halves[0].split(" ")[1]),cookies,halves[0],halves.length==2?halves[1]:"");}
 }
 Wire login(boolean tls)throws Exception{var a=request(tls,"ems-test.invalid","/make","",null,"");assertEquals(200,a.status);var b=request(tls,"ems-test.invalid","/login",a.cookie(),"csrf="+a.json().path("csrf").asText(),"Origin: "+(tls?"https":"http")+"://ems-test.invalid\r\n");assertEquals(200,b.status);assertNotEquals(a.json().path("id").asText(),b.json().path("id").asText());assertTrue(b.json().path("authenticated").asBoolean());return b;}
 @Test void realTlsAndHttpShareManagerButCannotReplaySessionsOrDestroyVictim()throws Exception{
  start("compat");var h=login(false);var t=login(true);assertFalse(h.headers.toLowerCase(Locale.ROOT).contains("; secure"));assertTrue(t.headers.toLowerCase(Locale.ROOT).contains("; secure"));int before=destroyed.get();
  String httpName=policy.entry(hp,false,false).cookieName(),tlsName=policy.entry(tp,true,true).cookieName();
  assertEquals(401,request(true,"ems-test.invalid","/who",tlsName+"="+h.json().path("id").asText(),null,"").status);
  assertEquals(401,request(false,"ems-test.invalid","/logout",httpName+"="+t.json().path("id").asText(),"csrf="+t.json().path("csrf").asText(),"Origin: http://ems-test.invalid\r\n").status);
  assertEquals(before,destroyed.get());assertTrue(request(false,"ems-test.invalid","/who",h.cookie(),null,"").json().path("authenticated").asBoolean());assertTrue(request(true,"ems-test.invalid","/who",t.cookie(),null,"").json().path("authenticated").asBoolean());
  assertEquals(403,request(true,"ems-test.invalid","/logout",t.cookie(),"csrf="+h.json().path("csrf").asText(),"Origin: https://ems-test.invalid\r\n").status);
  assertEquals(401,request(false,"ems-test.invalid","/who;jsessionid="+t.json().path("id").asText(),"",null,"").status);
  assertEquals(401,request(false,"ems-test.invalid","/who",policy.cookieBase+"="+t.json().path("id").asText(),null,"").status);
  assertEquals(before,destroyed.get());
 }
 @Test void safeLoopbackNeedsLocalCodeDespiteSpoofedProxyHeaders()throws Exception{
  start("safe");String host="127.0.0.1:"+hp;assertEquals(302,request(false,host,"/login","",null,"Forwarded: proto=https;host=ems-test.invalid\r\n").status);assertEquals(200,request(false,"localhost:"+hp,"/health","",null,"").status);
  var gate=request(false,host,"/local-entry","",null,"");assertEquals(200,gate.status);var match=java.util.regex.Pattern.compile("name=_csrf value=\"([^\"]+)\"").matcher(gate.body);assertTrue(match.find());String csrf=match.group(1);
  long expires=System.currentTimeMillis()+60000;String nonce=UUID.randomUUID().toString(),digest=LocalEntryAdmission.digest(CODE),canonical="42|"+BOOT+"|"+policy.generation+"|"+nonce+"|"+expires+"|"+digest;
  JSON.writeValue(work.resolve("local-entry-request.json").toFile(),Map.of("pid",42,"bootId",BOOT,"generation",policy.generation,"nonce",nonce,"expires",expires,"digest",digest,"mac",LocalEntryAdmission.sign(KEY,canonical)));
  assertEquals(403,request(false,host,"/local-entry",gate.cookie(),"_csrf="+csrf+"&code="+CODE,"Origin: http://evil.invalid\r\n").status);
  var admitted=request(false,host,"/local-entry",gate.cookie(),"_csrf="+csrf+"&code="+CODE,"Origin: http://"+host+"\r\n");assertEquals(302,admitted.status);assertNotEquals(gate.cookie(),admitted.cookie());assertEquals(200,request(false,host,"/make",admitted.cookie(),null,"").status);
 }
 @Test void wrongHostOriginAndRenamedCookiesStayDeniedOnRealConnectors()throws Exception{
  start("compat");var h=login(false);var t=login(true);
  assertEquals(403,request(true,"localhost:"+tp,"/make","",null,"").status);
  assertEquals(403,request(true,"ems-test.invalid","/login",t.cookie(),"csrf="+t.json().path("csrf").asText(),"Origin: http://ems-test.invalid\r\n").status);
  String name=policy.entry(hp,false,false).cookieName();assertEquals(401,request(false,"ems-test.invalid","/who",name+"="+t.json().path("id").asText(),null,"X-Forwarded-Proto: https\r\nX-Forwarded-For: 127.0.0.1\r\n").status);
  assertEquals(401,request(false,"ems-test.invalid","/who",h.cookie()+"; "+h.cookie(),null,"").status);
  assertTrue(request(true,"ems-test.invalid","/who",t.cookie(),null,"").json().path("authenticated").asBoolean());
 }
}
