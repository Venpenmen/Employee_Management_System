package com.example.ems.session;
import com.example.ems.EmsApplication;
import com.example.ems.service.SessionService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
public class WebSessionIT {
 static int count;
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);count++;System.out.println("PASS: "+name);}
 static ServletWebServerApplicationContext start(int port){
  String url=System.getenv("EMS_IT_URL");
  if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(1650[6-9]|165[12][0-9]|16530)/ems_up_server_v120\\?.*"))throw new IllegalStateException("Unsafe test database");
  return (ServletWebServerApplicationContext)new SpringApplicationBuilder(EmsApplication.class).run(
   "--spring.profiles.active=server","--server.address=127.0.0.1","--server.port="+port,
   "--spring.datasource.url="+url,"--spring.datasource.username=ems_app","--ems.browser-auto-open=false",
   "--spring.main.banner-mode=off","--logging.level.root=WARN");
 }
 static class Browser {
  final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
  final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
  String base,csrf;
  Browser(int port){base="http://127.0.0.1:"+port;}
  HttpResponse<String> req(String route,String fields)throws Exception{
   var b=HttpRequest.newBuilder(URI.create(base+route));
   if(fields!=null)b.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(fields));
   return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
  }
  void login()throws Exception{
   String html=req("/login",null).body();var tags=Pattern.compile("<input\\b[^>]*>").matcher(html);
   while(tags.find()){String tag=tags.group();if(tag.contains("name=\"_csrf\"")){var m=Pattern.compile("value=\"([^\"]*)\"").matcher(tag);if(m.find())csrf=m.group(1);}}
   check(csrf!=null,"Real servlet login CSRF acquired");
   var res=req("/login","employeeNo=260001&password="+URLEncoder.encode(System.getenv("EMS_TEST_PASSWORD"),StandardCharsets.UTF_8)+"&_csrf="+csrf);
   check(res.statusCode()==302,"Real servlet login succeeds");
  }
  String id(){return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("EMS120U_SERVER")).findFirst().orElseThrow().getValue();}
 }
 public static void main(String[] args)throws Exception{
  Browser stale;int port;
  try(var app=start(0)){
   port=app.getWebServer().getPort();var jdbc=app.getBean(JdbcTemplate.class);
   Long id=jdbc.queryForObject("SELECT id FROM employee WHERE employee_no='260001'",Long.class);
   check(app.getEnvironment().getProperty("server.servlet.session.timeout").equals("30m"),"Default session timeout remains30minutes");
   Browser expired=new Browser(port);expired.login();
   var tomcat=((TomcatWebServer)app.getWebServer()).getTomcat();
   org.apache.catalina.Context context=(org.apache.catalina.Context)tomcat.getHost().findChildren()[0];
   var session=context.getManager().findSession(expired.id());
   check(session!=null&&session.getMaxInactiveInterval()==1800,"Real container session has1800second inactivity timeout");
   session.setMaxInactiveInterval(1);Thread.sleep(1200);
   check(!session.isValid(),"Actual Tomcat inactivity expires shortened test session");
   check(expired.req("/api/me",null).statusCode()==401,"Expired real session API returns401");
   check(jdbc.queryForObject("SELECT COUNT(*) FROM account_active_session WHERE employee_id=?",Integer.class,id)==0,"Real timeout listener removes matching digest");
   Browser old=new Browser(port);old.login();Browser winner=new Browser(port);winner.login();
   var losing=context.getManager().findSession(old.id());losing.setMaxInactiveInterval(1);Thread.sleep(1200);check(!losing.isValid(),"Old replaced real session expires");
   check(winner.req("/api/me",null).statusCode()==200,"Old timeout cannot revoke winning real session");
   stale=winner;
  }
  try(var restarted=start(port)){
   check(stale.req("/api/me",null).statusCode()==401,"Application restart never restores old authentication cookie");
  }
  System.out.println("Real servlet session checks passed: "+count);
 }
}
