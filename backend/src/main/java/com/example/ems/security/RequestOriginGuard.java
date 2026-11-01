package com.example.ems.security;

import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Native connector identity only. Forwarded headers cannot establish TLS or origin. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+10)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="ems.rc2.enabled",havingValue="false",matchIfMissing=true)
public final class RequestOriginGuard extends OncePerRequestFilter {
 private final Set<String> allowed;
 public RequestOriginGuard(Environment env){
  String scheme=env.getProperty("server.ssl.enabled",Boolean.class,false)?"https":"http";int port=env.getProperty("server.port",Integer.class,8482);
  var origins=new HashSet<String>();String address=env.getProperty("server.address","127.0.0.1");
  origins.add(origin(scheme+"://"+address+":"+port,false));
  if("127.0.0.1".equals(address))origins.add(origin(scheme+"://localhost:"+port,false));
  String publicOrigin=env.getProperty("EMS_PUBLIC_ORIGIN","");
  if(!publicOrigin.isBlank()){
   if(!"https".equals(scheme))throw new IllegalStateException("Public origin requires a genuine TLS connector");
   String value=origin(publicOrigin,false);if(!value.startsWith("https://"))throw new IllegalStateException("Public origin must be HTTPS");origins.add(value);
  }
  if("proxy".equals(env.getProperty("EMS_NETWORK_MODE","local"))&&publicOrigin.isBlank())throw new IllegalStateException("Proxy mode requires one explicitly configured HTTPS public origin");
  allowed=Set.copyOf(origins);
 }
 static String origin(String text,boolean allowPath){
  try{URI uri=URI.create(text);String scheme=uri.getScheme(),host=uri.getHost();if(!Set.of("http","https").contains(scheme)||host==null||uri.getRawUserInfo()!=null||(!allowPath&&(uri.getRawQuery()!=null||uri.getRawFragment()!=null||!Set.of("","/").contains(uri.getRawPath()))))throw new IllegalArgumentException();
   int port=uri.getPort()<0?(scheme.equals("https")?443:80):uri.getPort();if(port<1||port>65535)throw new IllegalArgumentException();return scheme+"://"+host.toLowerCase(Locale.ROOT)+":"+port;
  }catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid origin configuration or request");}
 }
 boolean permits(HttpServletRequest request){
  try{
   var hosts=Collections.list(request.getHeaders("Host"));if(hosts.size()!=1)return false;
   String actual=origin((request.isSecure()?"https":"http")+"://"+hosts.get(0),false);if(!allowed.contains(actual))return false;
   if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
    if("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site")))return false;
    String supplied=request.getHeader("Origin");if(supplied!=null){if(!origin(supplied,false).equals(actual))return false;}
    else{String referer=request.getHeader("Referer");if(referer!=null&&!origin(referer,true).equals(actual))return false;}
   }return true;
  }catch(IllegalArgumentException invalid){return false;}
 }
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  if(!permits(request)){response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"msg\":\"访问地址或请求来源不符合当前网络模式\",\"data\":null}");return;}chain.doFilter(request,response);
 }
}
