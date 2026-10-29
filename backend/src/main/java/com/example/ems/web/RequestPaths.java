package com.example.ems.web;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;
/** Use the same decoded, semicolon-free application path for access and response policies. */
public final class RequestPaths {
 private static final UrlPathHelper PATHS=new UrlPathHelper();
 private RequestPaths(){}
 public static String path(HttpServletRequest request){return PATHS.getLookupPathForRequest(request);}
}