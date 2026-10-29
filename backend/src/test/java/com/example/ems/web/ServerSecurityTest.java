package com.example.ems.web;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import static org.junit.jupiter.api.Assertions.*;
class ServerSecurityTest {
 @Test void everyBusinessWriteRequiresSessionBoundToken() throws Exception{
  var interceptor=new ProfileCsrfInterceptor();
  for(String path:new String[]{"/login","/register","/logout","/admin/departments","/attendance/check-in","/leaves","/api/test","/profile"}){
   var get=new MockHttpServletRequest("GET",path);var session=new MockHttpSession();get.setSession(session);
   assertTrue(interceptor.preHandle(get,new MockHttpServletResponse(),new Object()));
   String token=(String)get.getAttribute("profileCsrf");
   var post=new MockHttpServletRequest("POST",path);post.setSession(session);var denied=new MockHttpServletResponse();
   assertFalse(interceptor.preHandle(post,denied,new Object()));assertEquals(403,denied.getStatus());
   post.addHeader("X-CSRF-Token",token);
   assertTrue(interceptor.preHandle(post,new MockHttpServletResponse(),new Object()));
   var other=new MockHttpServletRequest("POST",path);other.addParameter("_csrf",token);
   assertFalse(interceptor.preHandle(other,new MockHttpServletResponse(),new Object()));
  }
 }
 @Test void allUnsafeMethodsProtectedAndSafeReadWorks() throws Exception{
  var interceptor=new ProfileCsrfInterceptor();
  for(String method:new String[]{"POST","PUT","PATCH","DELETE"})assertFalse(interceptor.preHandle(new MockHttpServletRequest(method,"/employees"),new MockHttpServletResponse(),new Object()));
  for(String method:new String[]{"GET","HEAD","OPTIONS"})assertTrue(interceptor.preHandle(new MockHttpServletRequest(method,"/employees"),new MockHttpServletResponse(),new Object()));
 }
 @Test void serverRejectsPortableAndDemoProfiles(){
  var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("server","portable");
  assertThrows(IllegalStateException.class,()->new com.example.ems.config.ModeGuard(env));
  env.setActiveProfiles("server");assertDoesNotThrow(()->new com.example.ems.config.ModeGuard(env));
 }
}
