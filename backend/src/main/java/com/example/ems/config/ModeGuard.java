package com.example.ems.config;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
@Component
public class ModeGuard {
 public ModeGuard(Environment environment){
  var active=java.util.Set.of(environment.getActiveProfiles());
  if(active.contains("server")&&(active.contains("portable")||active.contains("demo")))throw new IllegalStateException("Incompatible runtime profiles");
 }
}
