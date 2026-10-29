package com.example.ems.web;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
@RestController
public class HealthController {
 private final JdbcTemplate jdbc;
 public HealthController(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @GetMapping("/health") public ResponseEntity<?> health(){
  try{jdbc.queryForObject("SELECT 1",Integer.class);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status","UP"));}
  catch(Exception e){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("status","DOWN"));}
 }
}
