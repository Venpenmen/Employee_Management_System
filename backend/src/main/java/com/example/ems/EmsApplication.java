package com.example.ems;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/** A-only runnable scaffold; C handoff restores production entry point. No database access. */
@SpringBootApplication(scanBasePackages="ems.scaffold",exclude={DataSourceAutoConfiguration.class,FlywayAutoConfiguration.class})
public class EmsApplication {
 public static void main(String[] args){SpringApplication.run(EmsApplication.class,args);}

 @RestController static class FrameworkController {
  @GetMapping("/health") public Map<String,String> health(){return Map.of("status","UP","mode","A-framework","database","DISABLED");}
  @GetMapping("/") public String home(){return "EMS A framework: frontend and backend handoff pending";}
 }
}
