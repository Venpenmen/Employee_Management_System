package com.example.ems.security;
import java.util.*;import java.util.concurrent.*;import org.springframework.stereotype.Component;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;import org.springframework.scheduling.annotation.*;
@Component @EnableScheduling
public class SecurityEvents {
 private final JdbcTemplate jdbc;private final ConcurrentMap<String,Set<SseEmitter>> streams=new ConcurrentHashMap<>();
 public SecurityEvents(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private String key(Long id,String hash){return id+":"+hash;}
 public SseEmitter subscribe(Long id,String hash){
  if(!valid(id,hash))throw new com.example.ems.service.SessionService.SessionExpired();
  String key=key(id,hash);var set=streams.computeIfAbsent(key,k->ConcurrentHashMap.newKeySet());
  synchronized(set){if(set.size()>=3)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"事件连接过多");
   var e=new SseEmitter(120000L);set.add(e);Runnable remove=()->{set.remove(e);if(set.isEmpty())streams.remove(key,set);};e.onCompletion(remove);e.onTimeout(()->{remove.run();complete(e);});e.onError(x->remove.run());try{e.send(SseEmitter.event().name("ready").data(Map.of("status","connected")));}catch(Exception ex){remove.run();complete(e);}return e;}
 }
 private boolean valid(Long id,String hash){return jdbc.queryForObject("SELECT COUNT(*) FROM account_active_session x JOIN user_account a ON a.employee_id=x.employee_id JOIN employee e ON e.id=a.employee_id LEFT JOIN account_security s ON s.employee_id=e.id WHERE x.employee_id=? AND x.token_hash=? AND a.account_status=1 AND e.employment_status=1 AND COALESCE(s.frozen,0)=0 AND COALESCE(s.temporary_state,'NORMAL')='NORMAL'",Integer.class,id,hash)==1;}
 public void replaced(Long id,String oldHash,Map<String,Object> info){send(key(id,oldHash),"session-replaced",info);}
 public void refreshed(Long id,String hash){send(key(id,hash),"session-refresh",Map.of("message","当前浏览器已重新认证"));}
 public void ended(Long id,String hash){send(key(id,hash),"session-ended",Map.of("message","登录已失效，请重新登录"));}
 private static void complete(SseEmitter e){try{e.complete();}catch(RuntimeException closed){/* A disconnected AsyncContext cannot be completed again. */}}
 private void send(String key,String type,Object data){var set=streams.remove(key);if(set!=null)for(var e:set){try{e.send(SseEmitter.event().name(type).data(data));}catch(Exception ignored){}finally{complete(e);}}}
 @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class) public void closeStreams(){for(String key:List.copyOf(streams.keySet()))send(key,"session-ended",Map.of("message","服务正在停止，请稍后重新登录"));}
 @Scheduled(fixedDelay=15000) public void heartbeat(){for(var entry:streams.entrySet()){try{String[] key=entry.getKey().split(":",2);if(!valid(Long.valueOf(key[0]),key[1])){send(entry.getKey(),"session-ended",Map.of("message","登录已失效"));continue;}for(var e:entry.getValue())try{e.send(SseEmitter.event().comment("heartbeat"));}catch(Exception ex){complete(e);}}catch(Exception ex){send(entry.getKey(),"session-ended",Map.of("message","连接暂不可用，请重新验证登录"));}}}
}
