package com.example.ems.upgrade;
public class Rc2LauncherGuardIT {public static void main(String[] args){String url=args[0];Schema.safeUrl(url);org.flywaydb.core.Flyway.configure().dataSource(url,"root",System.getenv("EMS_IT_PASSWORD")).locations("classpath:db/upgrade").target("2").cleanDisabled(true).load().migrate();}}
