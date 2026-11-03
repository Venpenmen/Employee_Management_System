package com.example.ems.security;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Component @ConfigurationProperties(prefix="ems.security")
public class SecurityPolicy {
 private int windowMinutes=10,warningThreshold=3,freezeThreshold=6;
 public int getWindowMinutes(){return windowMinutes;} public void setWindowMinutes(int v){if(v<1||v>1440)throw new IllegalArgumentException("Invalid security window");windowMinutes=v;}
 public int getWarningThreshold(){return warningThreshold;} public void setWarningThreshold(int v){if(v<1)throw new IllegalArgumentException("Invalid warning threshold");warningThreshold=v;}
 public int getFreezeThreshold(){return freezeThreshold;} public void setFreezeThreshold(int v){if(v<1)throw new IllegalArgumentException("Invalid freeze threshold");freezeThreshold=v;}
 @jakarta.annotation.PostConstruct public void validate(){if(freezeThreshold<=warningThreshold)throw new IllegalArgumentException("Freeze threshold must exceed warning threshold");}
 public static void password(String p){if(p==null||p.length()<8||p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72||!p.matches("(?s).*[A-Za-z].*")||!p.matches("(?s).*[0-9].*"))throw new IllegalArgumentException("密码至少8位、最多72 UTF-8字节，且包含字母和数字");}
 public static String secret(){byte[] b=new byte[32];new java.security.SecureRandom().nextBytes(b);return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public static String address(String ip){if(ip==null)return "未知";if(ip.contains(":"))return "IPv6（已脱敏）";String[] p=ip.split("\\.");return p.length==4?p[0]+"."+p[1]+".*.*":"未知";}
}
