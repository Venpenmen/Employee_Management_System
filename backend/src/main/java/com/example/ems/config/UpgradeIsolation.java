package com.example.ems.config;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
public final class UpgradeIsolation implements EnvironmentPostProcessor,Ordered{
 public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
 public void postProcessEnvironment(ConfigurableEnvironment e,SpringApplication a){
  String url=e.getProperty("spring.datasource.url","");
  if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:(1650[6-9]|165[12][0-9]|16530)/ems_up_(dev|server|portable)_v120(_[a-z0-9]+)?(\\?.*)?"))throw new IllegalStateException("Upgrade build refuses nonisolated datasource");
  int port=e.getProperty("server.port",Integer.class,8282);
  if(port==8083||port==8183)throw new IllegalStateException("Historical service ports are protected");
 }
}
