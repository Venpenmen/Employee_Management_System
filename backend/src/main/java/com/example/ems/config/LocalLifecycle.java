package com.example.ems.config;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
@Component

public class LocalLifecycle {
 private final ConfigurableApplicationContext context;
 private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ems-local-stop");t.setDaemon(true);return t;});
 public LocalLifecycle(ConfigurableApplicationContext context){this.context=context;}
 @EventListener(ApplicationReadyEvent.class) public void ready() throws Exception{
  String dir=System.getenv("EMS_CONTROL_DIR"),token=System.getenv("EMS_INSTANCE_TOKEN");
  if(dir==null&&token==null)return;
  if(dir==null||token==null||token.length()<32) throw new IllegalStateException("Local lifecycle identity is required");
  Path root=Path.of(dir); Files.writeString(root.resolve("ready"),Long.toString(ProcessHandle.current().pid()));
  executor.scheduleWithFixedDelay(()->{
   try{Path request=root.resolve("stop.request");
    if(Files.exists(request)&&MessageDigest.isEqual(Files.readString(request).getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))){
     Files.deleteIfExists(request);new Thread(context::close,"ems-shutdown").start();executor.shutdown();
    }
   }catch(Exception ignored){}
  },1,1,TimeUnit.SECONDS);
 }
 @PreDestroy public void close(){executor.shutdownNow();}
}
