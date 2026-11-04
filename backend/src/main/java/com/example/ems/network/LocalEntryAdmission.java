package com.example.ems.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Local console signs only a digest. Clear codes never reach files, URLs or logs. */
public final class LocalEntryAdmission {
 private static final String ADMITTED=LocalEntryAdmission.class.getName()+".admitted",CSRF=LocalEntryAdmission.class.getName()+".csrf";
 private final Path root;private final String control,boot,generation;private final long pid;private final Clock clock;
 private final Set<String> consumed=new HashSet<>();private static final ObjectMapper JSON=new ObjectMapper();
 public LocalEntryAdmission(Path root,String control,String boot,String generation,long pid,Clock clock){if(control==null||control.length()<32)throw new IllegalArgumentException("Local control identity required");this.root=root.toAbsolutePath().normalize();this.control=control;this.boot=boot;this.generation=generation;this.pid=pid;this.clock=clock;}
 public static String digest(String code){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
 public static String sign(String key,String canonical){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
 /** A form post carries an Origin only when the browser considers it cross-site or opaque.
  *  Chrome sends the literal null for opaque initiators, so treat it like "absent" when the
  *  request itself says it is same-origin; a cross-site post is still refused, and the
  *  session-bound CSRF token stays mandatory for every accepted form. */
 static boolean originAccepted(String supplied,String expected,String fetchSite,String referer){
  String value=supplied==null?"":supplied.trim();
  if(!value.isEmpty()&&!value.equalsIgnoreCase("null")){try{return Rc2NetworkPolicy.origin(value,false).equals(Rc2NetworkPolicy.origin(expected,false));}catch(IllegalArgumentException invalid){return false;}}
  if("same-origin".equalsIgnoreCase(fetchSite)||"none".equalsIgnoreCase(fetchSite))return true;
  String from=referer==null?"":referer.trim();
  if(!from.isEmpty()){try{return Rc2NetworkPolicy.origin(from,true).equals(Rc2NetworkPolicy.origin(expected,false));}catch(IllegalArgumentException invalid){return false;}}
  return false;
 }
 private String refuse(String reason){org.slf4j.LoggerFactory.getLogger(LocalEntryAdmission.class).info("Local entry proof refused: {}",reason);return reason;}
 synchronized String redeemReason(String code){
  if(code==null||!code.matches("[A-Za-z0-9_-]{32}"))return refuse("code-format");
  try{
   Path file=root.resolve("local-entry-request.json");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file)||Files.size(file)>4096)return refuse("proof-missing");
   var value=JSON.readTree(Files.readAllBytes(file));String nonce=value.path("nonce").asText(),hash=value.path("digest").asText();long expires=value.path("expires").asLong(),now=clock.millis();
   if(value.path("pid").asLong()!=pid||!boot.equals(value.path("bootId").asText())||!generation.equals(value.path("generation").asText()))return refuse("proof-instance");
   if(expires<=now)return refuse("proof-expired");
   if(expires>now+120000)return refuse("proof-expiry-window");
   if(!nonce.matches("[a-f0-9-]{36}")||!hash.matches("[a-f0-9]{64}"))return refuse("proof-format");
   if(consumed.contains(nonce))return refuse("proof-replay");
   String canonical=pid+"|"+boot+"|"+generation+"|"+nonce+"|"+expires+"|"+hash;
   if(!MessageDigest.isEqual(HexFormat.of().parseHex(sign(control,canonical)),HexFormat.of().parseHex(value.path("mac").asText()))||!MessageDigest.isEqual(HexFormat.of().parseHex(hash),HexFormat.of().parseHex(digest(code))))return refuse("proof-auth");
   // Bound memory even if an authorized console repeatedly issues unused codes.
   if(consumed.size()>=4096)return refuse("proof-capacity");
   consumed.add(nonce);return null;
  }catch(Exception invalid){return refuse("proof-malformed");}
 }
 synchronized boolean redeem(String code){return redeemReason(code)==null;}
 public boolean handle(HttpServletRequest request,HttpServletResponse response,Rc2NetworkPolicy.Entry entry)throws java.io.IOException{
  if(entry.secure()||entry.testHttp())return false;
  String path=request.getRequestURI();
  if(request.getMethod().equals("GET")&&(path.equals("/health")||path.startsWith("/css/")||path.startsWith("/js/")||path.equals("/favicon.ico")))return false;
  HttpSession session=request.getSession(false);if(session!=null&&entry.realm().equals(session.getAttribute(ADMITTED)))return false;
  response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
  String host=request.getHeader("Host"),origin=request.getHeader("Origin"),fetchSite=request.getHeader("Sec-Fetch-Site");
  if(path.equals("/local-entry")&&request.getMethod().equals("GET")){
   session=request.getSession(true);String csrf=UUID.randomUUID().toString()+UUID.randomUUID();session.setAttribute(CSRF,csrf);
   response.setContentType("text/html;charset=UTF-8");response.getWriter().write("<!doctype html><html lang=zh-CN><meta charset=utf-8><title>本机入口授权 · EMS</title><h1>本机入口授权</h1><p>请从本安装的本机控制台取得短期单次授权代码。此步骤不代替账号登录。</p><form method=post action=/local-entry><input type=hidden name=_csrf value=\""+csrf+"\"><label>本机授权代码<input type=password name=code autocomplete=off maxlength=32 required></label><button>验证并进入登录</button></form></html>");return true;
  }
  if(path.equals("/local-entry")&&request.getMethod().equals("POST")){
   String expected="http://"+host;
   if(!originAccepted(origin,expected,fetchSite,request.getHeader("Referer"))){denied(response,"origin",request);return true;}
   if(request.getContentLengthLong()<0||request.getContentLengthLong()>4096||request.getContentType()==null||!request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")){denied(response,"body",request);return true;}
   String[] csrf=request.getParameterValues("_csrf"),codes=request.getParameterValues("code");Object token=session==null?null:session.getAttribute(CSRF);
   if(csrf==null||csrf.length!=1||codes==null||codes.length!=1||!(token instanceof String saved)||!MessageDigest.isEqual(saved.getBytes(StandardCharsets.UTF_8),csrf[0].getBytes(StandardCharsets.UTF_8))){denied(response,"csrf",request);return true;}
   String failure=redeemReason(codes[0]);if(failure!=null){denied(response,failure,request);return true;}
   synchronized(session){request.changeSessionId();session.removeAttribute(CSRF);session.setAttribute(ADMITTED,entry.realm());}response.sendRedirect("/login");return true;
  }
  if(path.startsWith("/api/")||!request.getMethod().equals("GET")){denied(response,"admission-required",request);return true;}
  response.sendRedirect("/local-entry");return true;
 }
 private static String hint(String reason){
  return switch(reason){
   case "code-format" -> "授权码应为 32 位字母数字下划线或连字符，请从本机控制台重新取码。";
   case "proof-missing" -> "没有找到当前实例的授权请求文件，请在本机控制台重新取码（有效期 90 秒）。";
   case "proof-instance" -> "这个授权码不是当前运行实例签发的（实例重启过或换了安装目录），请重新取码。";
   case "proof-expired" -> "授权码已过期（有效期 90 秒），请重新取码后立刻提交。";
   case "proof-expiry-window" -> "授权请求的时间戳超出允许范围（本机与系统时间可能不同步），请校准时间后重新取码。";
   case "proof-format" -> "授权请求文件格式异常，请重新取码。";
   case "proof-replay" -> "该授权码已经使用过一次，请重新取码。";
   case "proof-auth" -> "授权码与本机请求不匹配（取码后实例已重启或配置变化），请重新取码。";
   case "proof-capacity" -> "待用授权码过多，请重启实例后重新取码。";
   case "proof-malformed" -> "授权请求文件无法解析，请重新取码。";
   case "origin" -> "浏览器的 Origin/Referer 与本机地址不一致（常见于代理、扩展或改用局域网 IP 打开），请用 http://127.0.0.1:<端口> 或 http://localhost:<端口> 打开后再提交。";
   case "body" -> "提交内容类型或长度异常，请从授权页面上的表单提交。";
   case "csrf" -> "表单已失效（可能刷新过多标签或会话已更换），请重新打开 /local-entry 再提交。";
   default -> "请重新打开 /local-entry 获取新的授权码后再提交。";
  };
 }
 private static String escape(String text){return text==null?"":text.replace("\\","\\\\").replace("\"","\\\"").replaceAll("[\\r\\n]"," ");}
 private static void denied(HttpServletResponse response,String reason,HttpServletRequest request)throws java.io.IOException{
  response.setStatus(403);response.setContentType("application/json;charset=UTF-8");
  response.getWriter().write("{\"code\":403,\"reason\":\""+reason+"\",\"msg\":\"需要有效的本机入口授权，或代码已过期、已使用\",\"hint\":\""+hint(reason)+"\",\"request\":{\"method\":\""+escape(request.getMethod())+"\",\"path\":\""+escape(request.getRequestURI())+"\",\"host\":\""+escape(request.getHeader("Host"))+"\",\"headers\":{\"origin\":\""+escape(request.getHeader("Origin"))+"\",\"referer\":\""+escape(request.getHeader("Referer"))+"\",\"fetchSite\":\""+escape(request.getHeader("Sec-Fetch-Site"))+"\"}},\"data\":null}");
 }
}
