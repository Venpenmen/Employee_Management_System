package com.example.ems.security;
import java.util.concurrent.*;import org.springframework.stereotype.Component;import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;
@Component public class AttemptLimiter {
 private record Window(long until,int count){} private final ConcurrentHashMap<String,Window> entries=new ConcurrentHashMap<>();
 public synchronized void check(String key){long now=System.nanoTime();entries.entrySet().removeIf(e->e.getValue().until<now);if(entries.size()>=10000&&!entries.containsKey(key))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"请求繁忙，请稍后重试");var old=entries.get(key);int count=old==null?1:old.count+1;entries.put(key,new Window(old==null?now+60000000000L:old.until,count));if(count>5)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"请求过于频繁，请一分钟后重试");}
}
