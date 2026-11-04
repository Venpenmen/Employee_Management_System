package com.example.ems.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.*;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.time.Clock;
import java.util.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="ems.rc2.enabled",havingValue="true")
public class Rc2EntryConfiguration {
 @Bean Rc2NetworkPolicy rc2Policy(Environment env){return new Rc2NetworkPolicy(env);}
 @Bean LocalEntryAdmission rc2Admission(Environment e,Rc2NetworkPolicy p){return new LocalEntryAdmission(Path.of(e.getRequiredProperty("EMS_CONTROL_DIR")),e.getRequiredProperty("EMS_INSTANCE_TOKEN"),p.bootId,p.generation,ProcessHandle.current().pid(),Clock.systemUTC());}
 @Bean ServletListenerRegistrationBean<jakarta.servlet.http.HttpSessionListener> rc2EntryListener(){return new ServletListenerRegistrationBean<>(EntryIsolation.listener());}
 @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> rc2Factory(Rc2NetworkPolicy p,LocalEntryAdmission a){return factory->{
  try{factory.setAddress(InetAddress.getByName("127.0.0.1"));}catch(UnknownHostException impossible){throw new IllegalStateException(impossible);}
  factory.setSsl(null);factory.setPort(p.httpPort);factory.addEngineValves(new EntryIsolation.Valve(p,a));factory.addContextCustomizers(context->{context.setCookieProcessor(new EntryIsolation.Cookies(p.cookieBase));context.setSessionCookieName(p.cookieBase);});
 };}
 @Bean RemoteTlsLifecycle rc2RemoteLifecycle(Environment e,Rc2NetworkPolicy p){return new RemoteTlsLifecycle(e,p);}

 /** Late connector attachment: an unusable remote TLS listener must not kill local management. */
 public static final class RemoteTlsLifecycle {
  private final Environment env;private final Rc2NetworkPolicy policy;
  RemoteTlsLifecycle(Environment env,Rc2NetworkPolicy policy){this.env=env;this.policy=policy;}
  Path file(String property)throws Exception{
   Path root=Path.of(env.getRequiredProperty("EMS_CONTROL_DIR")).toAbsolutePath().normalize(),p=Path.of(env.getRequiredProperty(property)).toAbsolutePath().normalize();
   if(!p.startsWith(root)||!p.toRealPath().equals(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Invalid package-local TLS material");
   for(Path parent=p;parent!=null&&parent.startsWith(root);parent=parent.getParent())if(Files.isSymbolicLink(parent)||!parent.toRealPath().equals(parent))throw new IllegalArgumentException("TLS reparse path refused");return p;
  }
  @EventListener(ApplicationReadyEvent.class) @Order(-100)
  public void ready(ApplicationReadyEvent event){
   String status="DISABLED",error="";Connector own=null;org.apache.catalina.Service service=null;
   try{
    if(policy.remoteEnabled){
     if(policy.remoteError!=null)throw new IllegalArgumentException("Invalid secure remote origin");
     Path store=file("EMS_RC2_KEYSTORE"),anchor=file("EMS_RC2_TRUST_CERTIFICATE");String password=env.getRequiredProperty("EMS_TLS_STORE_PASSWORD");
     var keys=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(store)){keys.load(in,password.toCharArray());}
     boolean privateKey=false;for(var names=keys.aliases();names.hasMoreElements();){String alias=names.nextElement();if(keys.isKeyEntry(alias)){privateKey=true;var chain=keys.getCertificateChain(alias);if(chain==null||chain.length==0)throw new CertificateException();for(var cert:chain)((X509Certificate)cert).checkValidity();}}
     if(!privateKey)throw new CertificateException();
     try(var probe=new java.net.ServerSocket()){probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),policy.httpsPort));}
     var context=(ServletWebServerApplicationContext)event.getApplicationContext();var server=(TomcatWebServer)context.getWebServer();service=server.getTomcat().getService();
     own=new Connector(Http11NioProtocol.class.getName());own.setPort(policy.httpsPort);own.setScheme("https");own.setSecure(true);var protocol=(Http11NioProtocol)own.getProtocolHandler();protocol.setAddress(InetAddress.getByName("127.0.0.1"));protocol.setSSLEnabled(true);
     var ssl=new SSLHostConfig();ssl.setProtocols("TLSv1.2,TLSv1.3");var cert=new SSLHostConfigCertificate(ssl,SSLHostConfigCertificate.Type.UNDEFINED);cert.setCertificateKeystoreFile(store.toString());cert.setCertificateKeystoreType("PKCS12");cert.setCertificateKeystorePassword(password);cert.setCertificateKeyPassword(password);ssl.addCertificate(cert);own.addSslHostConfig(ssl);
     service.addConnector(own);if(!own.getState().isAvailable())throw new IllegalStateException("Owned TLS connector not available");
     // Authenticate the configured hostname over a numeric loopback socket: no DNS,
     // no global trust changes and no hostname-verification bypass.
     verifyPeer(anchor,URI.create(policy.secureOrigin).getHost(),policy.httpsPort);status="READY";
    }
   }catch(Exception failed){
    status="FAILED";error=UUID.randomUUID().toString();
    if(own!=null&&service!=null){try{service.removeConnector(own);own.destroy();}catch(Exception cleanup){org.slf4j.LoggerFactory.getLogger(RemoteTlsLifecycle.class).warn("Owned TLS connector cleanup incomplete [{}]",error);}}
    org.slf4j.LoggerFactory.getLogger(RemoteTlsLifecycle.class).warn("Remote TLS unavailable; local entry remains gated [{}]",error);
   }
   try{
    Path root=Path.of(env.getRequiredProperty("EMS_CONTROL_DIR")).toAbsolutePath().normalize();Path target=root.resolve("network-runtime.json"),part=root.resolve("network-runtime-"+UUID.randomUUID()+".partial");
    var value=new LinkedHashMap<String,Object>();value.put("buildId","v1.2.1-complete-rc2");value.put("pid",ProcessHandle.current().pid());value.put("bootId",policy.bootId);value.put("generation",policy.generation);value.put("mode",policy.mode);value.put("edgeTls",policy.edgeTls);value.put("remoteState",status);value.put("errorId",error);value.put("localHttpPort",policy.httpPort);value.put("httpsPort",policy.httpsPort);
    var info=ProcessHandle.current().info();var started=info.startInstant().orElseThrow();String ticks=Long.toString(Math.addExact(621355968000000000L,Math.addExact(Math.multiplyExact(started.getEpochSecond(),10000000L),started.getNano()/100)));
    String exe=info.command().orElseThrow(),install=Path.of(env.getRequiredProperty("EMS_UPGRADE_ROOT")).toAbsolutePath().normalize().toString(),runtimeMode=env.getRequiredProperty("EMS_UPGRADE_MODE");
    value.put("startTicks",ticks);value.put("exe",exe);value.put("installRoot",install);value.put("runtimeMode",runtimeMode);
    value.put("identityMac",LocalEntryAdmission.sign(env.getRequiredProperty("EMS_INSTANCE_TOKEN"),ProcessHandle.current().pid()+"|"+ticks+"|"+exe+"|"+install+"|"+runtimeMode+"|"+policy.bootId+"|"+policy.generation));
    new ObjectMapper().writeValue(part.toFile(),value);try{Files.move(part,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(part,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(part);}
   }catch(Exception failed){throw new IllegalStateException("Local network status could not be safely recorded",failed);}
  }
  static void verifyPeer(Path anchor,String host,int port)throws Exception{
   var roots=KeyStore.getInstance(KeyStore.getDefaultType());roots.load(null,null);try(var in=Files.newInputStream(anchor)){int i=0;for(var c:CertificateFactory.getInstance("X.509").generateCertificates(in))roots.setCertificateEntry("root"+(i++),c);if(i==0)throw new CertificateException();}
   var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(roots);var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);
   try(var raw=new Socket()){raw.connect(new InetSocketAddress("127.0.0.1",port),3000);raw.setSoTimeout(5000);try(var secure=(SSLSocket)ssl.getSocketFactory().createSocket(raw,host,port,true)){var p=secure.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(p);secure.setSoTimeout(5000);secure.startHandshake();}}
  }
 }
}
