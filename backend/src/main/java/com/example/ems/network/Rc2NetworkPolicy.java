package com.example.ems.network;

import org.springframework.core.env.Environment;
import java.net.URI;
import java.util.*;

/**
 * Immutable startup policy. Native connector identity, never forwarded headers.
 *
 * <p>{@code edgeTls} is an explicit compatibility-mode switch for deployments where a tunnel or
 * reverse proxy terminates TLS in front of this plain-HTTP connector (Sakura Frp automatic HTTPS,
 * Cloudflare Tunnel, ...). The connector identity then cannot see the browser scheme, so the
 * same-origin check compares {@code host:port} only. It never relaxes the loopback, single-Host,
 * cross-site, CSRF or session-namespace checks, and it is refused outside compatibility mode.
 */
public final class Rc2NetworkPolicy {
 public final String mode,generation,cookieBase,secureOrigin,testOrigin,remoteError;
 public final int httpPort,httpsPort;
 public final boolean remoteEnabled,edgeTls;
 public final String bootId=UUID.randomUUID().toString();
 public Rc2NetworkPolicy(Environment e){
  mode=e.getProperty("EMS_RC2_MODE","safe");if(!Set.of("safe","compat").contains(mode))throw new IllegalArgumentException("未知RC2网络模式");
  generation=e.getProperty("EMS_RC2_GENERATION","default-safe");if(!generation.matches("[A-Za-z0-9-]{1,80}"))throw new IllegalArgumentException("网络配置代际无效");
  httpPort=e.getProperty("server.port",Integer.class,8483);httpsPort=e.getProperty("EMS_RC2_HTTPS_PORT",Integer.class,8484);
  if(httpPort<1024||httpPort>65535||httpsPort<1024||httpsPort>65535||httpsPort==httpPort)throw new IllegalArgumentException("入口端口无效或冲突");
  cookieBase=e.getProperty("server.servlet.session.cookie.name","EMS121RC2_SERVER_SAFE");if(!cookieBase.matches("EMS121RC2_[A-Z_]+"))throw new IllegalArgumentException("RC2需要独立Cookie命名空间");
  remoteEnabled=e.getProperty("EMS_RC2_HTTPS_ENABLED",Boolean.class,false);
  edgeTls=e.getProperty("EMS_RC2_EDGE_TLS",Boolean.class,false);
  if(edgeTls&&!mode.equals("compat"))throw new IllegalArgumentException("边缘TLS兼容只在兼容模式下可用");
  if(!"server".equals(e.getProperty("EMS_UPGRADE_MODE","server"))&&(!mode.equals("safe")||remoteEnabled))throw new IllegalArgumentException("开发和便携模式只允许安全本机入口");
  secureOrigin=e.getProperty("EMS_RC2_SECURE_ORIGIN","");testOrigin=e.getProperty("EMS_RC2_TEST_ORIGIN","");
  String failure=null;if(remoteEnabled)try{requireRemote(secureOrigin,"https");}catch(IllegalArgumentException invalid){failure="远程HTTPS Origin缺失或无效，本机入口保持受控可用";}remoteError=failure;
  if(mode.equals("compat")){
   if(!e.getProperty("EMS_RC2_TEST_ACK",Boolean.class,false))throw new IllegalArgumentException("兼容模式必须明确确认仅使用虚构或脱敏数据");
   // 兼容模式不再预置精确外部 Origin：代理域名和端口随时可能变化（FRP 节点、
   // 免费隧道端口），要求写死一个地址会让远程访问无法自举。准入改由 permits()
   // 的 Host↔Origin 同源校验完成，跨站请求、伪造转发头仍被拒绝。
   // 历史配置里的 EMS_RC2_TEST_ORIGIN 仅作诊断信息保留，不参与准入判定。
  }
 }
 private static void requireRemote(String value,String scheme){String normalized=origin(value,false);URI u=URI.create(normalized);if(!u.getScheme().equals(scheme)||Set.of("localhost","127.0.0.1","::1","[::1]","0.0.0.0").contains(u.getHost()))throw new IllegalArgumentException("远程入口必须配置准确的非回环Origin");}
 public static String origin(String text,boolean pathAllowed){
  try{URI u=URI.create(text);String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);if(!Set.of("http","https").contains(scheme)||u.getHost()==null||u.getUserInfo()!=null||(!pathAllowed&&(u.getQuery()!=null||u.getFragment()!=null||!Set.of("","/").contains(u.getRawPath()))))throw new IllegalArgumentException();int p=u.getPort()<0?(scheme.equals("https")?443:80):u.getPort();if(p<1||p>65535)throw new IllegalArgumentException();return scheme+"://"+u.getHost().toLowerCase(Locale.ROOT)+":"+p;}catch(RuntimeException bad){throw new IllegalArgumentException("入口Origin格式无效");}
 }
 public Entry entry(int nativePort,boolean nativeSecure,boolean requestSecure){
  if(nativePort==httpPort&&!nativeSecure&&!requestSecure)return new Entry(bootId+":"+generation+":HTTP",cookieBase+"_HTTP",false,mode.equals("compat"));
  if(remoteEnabled&&remoteError==null&&nativePort==httpsPort&&nativeSecure&&requestSecure)return new Entry(bootId+":"+generation+":TLS",cookieBase+"_TLS",true,false);
  return null;
 }
 public boolean permits(Entry entry,String remote,List<String> hosts,String method,String suppliedOrigin,String referer,String fetchSite){return refusal(entry,remote,hosts,method,suppliedOrigin,referer,fetchSite)==null;}
 /** Diagnosable refusal code, null when the request is admitted. Diagnosis only; never a bypass. */
 public String refusal(Entry entry,String remote,List<String> hosts,String method,String suppliedOrigin,String referer,String fetchSite){
  if(entry==null)return "entry-port";
  if(!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(remote))return "remote-address";
  if(hosts.size()!=1)return "host-count";
  String actual;try{actual=origin((entry.secure?"https":"http")+"://"+hosts.get(0),false);}catch(IllegalArgumentException bad){return "host-format";}
  if(entry.secure){String secured;try{secured=origin(secureOrigin,false);}catch(IllegalArgumentException bad){return "secure-origin";}if(!actual.equals(secured))return "secure-origin";}
  else if(!entry.testHttp&&!Set.of("http://127.0.0.1:"+httpPort,"http://localhost:"+httpPort).contains(actual))return "host";
  // 兼容模式（entry.testHttp）接受任意外部 Host，由下面同一段的同源校验兜底：
  // 非只读请求必须满足 Origin（或 Referer）等于该 Host，且不得是 cross-site。
  // 启用边缘TLS兼容（edgeTls）时，代理已在外部终结 TLS，应用看不到浏览器协议，
  // 因此只忽略 http/https 协议差异；host:port 仍必须完全一致，转发头依然不被信任。
  if(!Set.of("GET","HEAD","OPTIONS").contains(method)){
   if("cross-site".equalsIgnoreCase(fetchSite))return "cross-site";
   // 浏览器把 Origin 发成字面量 null 时，该文档是不透明源（沙箱文档、某些隐私插件、
   // 部分旧内核）。仅当浏览器同时断言 Sec-Fetch-Site 为 same-origin/none 时才退化为
   // "没有 Origin"处理：跨站请求在这一步之前已按 cross-site 拒绝，而写操作仍必须通过
   // 会话绑定的 CSRF 令牌校验，因此不构成绕过；其余来源仍按 origin-format 拒绝。
   String suppliedOriginHeader=suppliedOrigin==null?null:suppliedOrigin.trim();
   String refererHeader=referer==null?null:referer.trim();
   if(suppliedOriginHeader!=null&&suppliedOriginHeader.isEmpty())suppliedOriginHeader=null;
   if(suppliedOriginHeader!=null&&suppliedOriginHeader.equalsIgnoreCase("null")&&("same-origin".equalsIgnoreCase(fetchSite)||"none".equalsIgnoreCase(fetchSite)))suppliedOriginHeader=null;
   if(refererHeader!=null&&refererHeader.isEmpty())refererHeader=null;
   boolean edge=entry.testHttp()&&edgeTls;
   if(suppliedOriginHeader!=null){String supplied;try{supplied=origin(suppliedOriginHeader,false);}catch(IllegalArgumentException bad){return "origin-format";}if(!sameHost(actual,supplied,edge))return "origin";}
   if(suppliedOriginHeader==null&&refererHeader!=null){String referred;try{referred=origin(refererHeader,true);}catch(IllegalArgumentException bad){return "referer-format";}if(!sameHost(actual,referred,edge))return "referer";}
  }return null;
 }
 /**
  * Same origin, or (only through {@code edgeTls} on the plain entry) the same {@code host:port}
  * reached over an edge-terminated scheme. The scheme is dropped, never invented: a client supplied
  * {@code X-Forwarded-Proto} is still ignored and a different host or port is still refused.
  */
 private static boolean sameHost(String actual,String supplied,boolean edge){
  if(actual.equals(supplied))return true;
  if(!edge)return false;
  int a=actual.indexOf("://"),b=supplied.indexOf("://");
  return a>0&&b>0&&actual.substring(a+3).equals(supplied.substring(b+3));
 }
 public record Entry(String realm,String cookieName,boolean secure,boolean testHttp){}
}
