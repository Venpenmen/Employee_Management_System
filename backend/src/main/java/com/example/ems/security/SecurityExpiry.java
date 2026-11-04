package com.example.ems.security;
import org.springframework.stereotype.Component;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.transaction.annotation.Transactional;import com.example.ems.service.LogService;
@Component public class SecurityExpiry {
 @org.springframework.beans.factory.annotation.Autowired private com.example.ems.config.ApplicationReadiness readiness;
 private final JdbcTemplate jdbc;private final LogService logs;public SecurityExpiry(JdbcTemplate jdbc,LogService logs){this.jdbc=jdbc;this.logs=logs;}
 @Scheduled(fixedDelay=60000) @Transactional public void expire(){
  if(!readiness.isReady())return;
  var rows=jdbc.queryForList("SELECT id,employee_id FROM password_change_request WHERE status IN('PENDING','APPROVED') AND expires_at<=NOW(6) ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED");
  for(var row:rows){jdbc.update("UPDATE password_change_request SET status='EXPIRED',active_employee_id=NULL WHERE id=?",row.get("id"));logs.log(((Number)row.get("employee_id")).longValue(),"PASSWORD_EXPIRED","PASSWORD_REQUEST",((Number)row.get("id")).longValue(),"密码申请或重置资格到期");}
  jdbc.update("DELETE FROM security_grant WHERE expires_at<DATE_SUB(NOW(6),INTERVAL 1 DAY)");
 }
}
