package com.example.ems.upgrade;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Same lock order as protected business writes: employee, account, security, session. */
final class MaintenanceAuthorization {
 private MaintenanceAuthorization(){}
 static void require(Connection c,long id,String hash)throws SQLException{
  if(c.getAutoCommit())throw new IllegalStateException("Authorization requires transaction");
  if(hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Executive session expired");
  try(var s=c.prepareStatement("SELECT employment_status,user_type FROM employee WHERE id=? FOR UPDATE")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1||r.getInt(2)!=2)expired();}}
  try(var s=c.prepareStatement("SELECT account_status FROM user_account WHERE employee_id=? FOR UPDATE")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1)expired();}}
  try(var s=c.prepareStatement("SELECT frozen,temporary_state FROM account_security WHERE employee_id=? FOR UPDATE")){s.setLong(1,id);try(var r=s.executeQuery()){if(r.next()&&(r.getInt(1)!=0||!"NORMAL".equals(r.getString(2))))expired();}}
  try(var s=c.prepareStatement("SELECT token_hash FROM account_active_session WHERE employee_id=? FOR UPDATE")){s.setLong(1,id);try(var r=s.executeQuery()){if(!r.next()||!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),r.getString(1).getBytes(StandardCharsets.US_ASCII)))expired();}}
 }
 private static void expired(){throw new IllegalArgumentException("Executive session expired");}
}
