package com.example.ems.network;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;

/** Container boundary before MVC/session consumers. A rejected foreign SID is never invalidated. */
public final class EntryIsolation {
 private EntryIsolation(){}
 private static final String REQUEST=EntryIsolation.class.getName()+".nativeEntry",SESSION=EntryIsolation.class.getName()+".realm";
 private static final ThreadLocal<Rc2NetworkPolicy.Entry> CURRENT=new ThreadLocal<>();
 public static String realm(HttpServletRequest request){Object value=request.getAttribute(REQUEST);return value instanceof Rc2NetworkPolicy.Entry e?e.realm():"legacy";}
 public static boolean testHttp(HttpServletRequest request){Object value=request.getAttribute(REQUEST);return value instanceof Rc2NetworkPolicy.Entry e&&e.testHttp()&&!e.secure();}
 public static HttpSessionListener listener(){return new HttpSessionListener(){public void sessionCreated(HttpSessionEvent event){var entry=CURRENT.get();if(entry==null)throw new IllegalStateException("RC2 session creation requires native entry context");event.getSession().setAttribute(SESSION,entry.realm());}};}
 private static String escape(String value){
  if(value==null)return "";
  StringBuilder out=new StringBuilder(value.length()+8);
  for(char c:value.toCharArray()){switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<0x20)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}}
  return out.toString();
 }
 private static String hint(String reason,Rc2NetworkPolicy policy){
  String local="http://127.0.0.1:"+policy.httpPort;
  return switch(reason==null?"":reason){
   case "entry-port" -> "你访问的端口不是本实例的入口端口。本实例入口是 "+local+"。不要用 8481/8482 等其它端口，也不要打开别的解压目录。";
   case "remote-address" -> "请求不是从本机发出的；安全模式只允许本机回环访问。要让远程设备访问，请切到兼容模式并走代理隧道（如 Sakura Frp 的 TCP 隧道）。";
   case "host-count" -> "请求里出现了重复的 Host 头，通常由代理、抓包工具或安全软件注入。请关闭这类软件后重试。";
   case "host" -> "当前地址不在安全模式允许范围内：只接受 "+local+" 或 http://localhost:"+policy.httpPort+"。用域名、局域网 IP 或别的端口访问时，请切到兼容模式。";
   case "secure-origin" -> "HTTPS 入口要求 Host 与本实例配置的安全 Origin 完全一致。";
   case "origin" -> policy.edgeTls?"写操作被拒绝：提交地址与页面地址不同源（host 或端口不一致）。边缘TLS只忽略 http/https 协议差异，不会放宽 host 和端口。":policy.mode.equals("compat")?"写操作的 Origin 和当前地址不同源。若你是经代理隧道用 HTTPS 打开页面（隧道在代理侧终结 TLS），请在“网络模式”窗口勾选“边缘 TLS（代理终结 HTTPS）”后重启本实例；否则请让地址栏和提交地址保持一致。":"写操作的 Origin 和当前地址不一致（常见于用 localhost 打开页面、却提交到 127.0.0.1）。请让地址栏和提交地址保持一致。";
   case "referer" -> "请求没有 Origin，且 Referer 与当前地址不一致，已按跨站请求拒绝。";
   case "cross-site" -> "浏览器把该请求标记为跨站（Sec-Fetch-Site: cross-site），已拒绝。请在实例页面内操作。";
   case "header-format" -> "Host 或 Origin 头格式不合法（例如带路径、多个端口）。";
   case "host-format" -> "Host 头无法解析（缺失、含路径或端口非法）。若浏览器没改过地址，通常是代理或抓包软件改写了 Host。";
   case "origin-format" -> "Origin 头无法解析（常见于浏览器把它发成 null 或非 http/https）。请看 request.headers.origin 的原文。";
   case "referer-format" -> "Referer 头无法解析。请看 request.headers.referer 的原文。";
   case "cookie-duplicate" -> "同一入口出现了重复的会话 Cookie，可能是旧标签页和当前实例混用。请清除该站点 Cookie 后重新进入。";
   case "realm-mismatch" -> "这个会话属于上一次启动的实例（每次启动入口代次都会变）。请重新打开 "+local+"/local-entry 重新进入。";
   default -> "入口校验未通过。";
  };
 }
 static void reject(Response response,Rc2NetworkPolicy policy,Request request,Rc2NetworkPolicy.Entry entry,int status,String reason)throws IOException{
  response.setStatus(status);response.setHeader("Cache-Control","no-store");
  if(entry!=null&&status==401){Cookie clear=new Cookie(entry.cookieName(),"");clear.setPath("/");clear.setMaxAge(0);clear.setHttpOnly(true);clear.setSecure(entry.secure());clear.setAttribute("SameSite","Lax");response.addCookie(clear);}
  String host=request.getHeader("Host"),remote=request.getRemoteAddr();
  String expected=policy.edgeTls?"同一 host:port（代理终结 TLS，协议不参与判定）":"http://127.0.0.1:"+policy.httpPort;
  String headers="{\"origin\":\""+escape(request.getHeader("Origin"))+"\",\"referer\":\""+escape(request.getHeader("Referer"))+"\",\"fetchSite\":\""+escape(request.getHeader("Sec-Fetch-Site"))+"\"}";
  String method=escape(request.getMethod()),path=escape(request.getRequestURI());
  String body="{\"code\":"+status
   +",\"reason\":\""+escape(reason)+"\""
   +",\"msg\":\"入口或会话不符合当前网络配置\""
   +",\"hint\":\""+escape(hint(reason,policy))+"\""
   +",\"request\":{\"method\":\""+method+"\",\"path\":\""+path+"\",\"host\":\""+escape(host)+"\",\"remote\":\""+escape(remote)+"\",\"port\":"+request.getConnector().getPort()+",\"mode\":\""+escape(policy.mode)+"\",\"edgeTls\":"+policy.edgeTls+",\"expected\":\""+escape(expected)+"\",\"headers\":"+headers+"}"
   +",\"data\":null}";
  response.setContentType("application/json;charset=UTF-8");response.getWriter().write(body);
 }
 public static final class Valve extends ValveBase {
  private final Rc2NetworkPolicy policy;private final LocalEntryAdmission admission;
  public Valve(Rc2NetworkPolicy policy,LocalEntryAdmission admission){super(true);this.policy=policy;this.admission=Objects.requireNonNull(admission);}
  @Override public void invoke(Request request,Response response)throws IOException,ServletException{
   boolean tls=request.getConnector().getProtocolHandler() instanceof org.apache.coyote.http11.AbstractHttp11Protocol<?> protocol&&protocol.isSSLEnabled()&&request.getConnector().getSecure();
   var entry=policy.entry(request.getConnector().getPort(),tls,request.isSecure());
   String refusal=policy.refusal(entry,request.getRemoteAddr(),Collections.list(request.getHeaders("Host")),request.getMethod(),request.getHeader("Origin"),request.getHeader("Referer"),request.getHeader("Sec-Fetch-Site"));
   if(refusal!=null){reject(response,policy,request,entry,403,refusal);return;}
   request.setAttribute(REQUEST,entry);CURRENT.set(entry);
   try{
    String selected=null;int count=0;Cookie[] cookies=request.getCookies();if(cookies!=null)for(Cookie cookie:cookies)if(entry.cookieName().equals(cookie.getName())){selected=cookie.getValue();count++;}
    if(count>1){reject(response,policy,request,entry,401,"cookie-duplicate");return;}
    // Override the container's base-name or URL-derived SID before session lookup.
    request.setRequestedSessionId(selected);request.setRequestedSessionCookie(selected!=null);request.setRequestedSessionURL(false);request.setRequestedSessionSSL(false);
    if(selected!=null){
     var session=request.getContext().getManager().findSession(selected);
     if(session!=null&&session.isValid()&&!entry.realm().equals(session.getSession().getAttribute(SESSION))){request.setRequestedSessionId(null);request.setRequestedSessionCookie(false);reject(response,policy,request,entry,401,"realm-mismatch");return;}
    }
    // Defensive cached-session check: never rotate or invalidate a foreign object.
    HttpSession cached=request.getSession(false);
    if(cached!=null&&(selected==null||!selected.equals(cached.getId())||!entry.realm().equals(cached.getAttribute(SESSION)))){reject(response,policy,request,entry,401,"realm-mismatch");return;}
    if(!admission.handle(request,response,entry))getNext().invoke(request,response);
   }finally{CURRENT.remove();}
  }
 }
 public static final class Cookies extends Rfc6265CookieProcessor {
  private final String base;
  public Cookies(String base){this.base=base;setSameSiteCookies("lax");}
  @Override public String generateHeader(Cookie cookie,HttpServletRequest request){
   if(base.equals(cookie.getName())){
    Object nativeEntry=request.getAttribute(REQUEST);if(!(nativeEntry instanceof Rc2NetworkPolicy.Entry e))throw new IllegalStateException("Session cookie missing native entry");
    Cookie scoped=new Cookie(e.cookieName(),cookie.getValue());scoped.setPath("/");scoped.setHttpOnly(true);scoped.setSecure(e.secure());scoped.setMaxAge(cookie.getMaxAge());scoped.setAttribute("SameSite","Lax");return super.generateHeader(scoped,request);
   }return super.generateHeader(cookie,request);
  }
 }
}
