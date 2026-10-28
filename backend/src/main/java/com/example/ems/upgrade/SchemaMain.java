package com.example.ems.upgrade;
/** Local Windows launcher only. Never handles web jobs or restores data. */
public final class SchemaMain {
 public static void main(String[] args){
  try{Schema.migrate(MaintenanceMain.env("EMS_MAINT_URL"),MaintenanceMain.env("EMS_MAINT_USER"),MaintenanceMain.env("EMS_MAINT_PASSWORD"));System.out.println("Flyway validation completed");}
  catch(Exception e){System.err.println("Schema validation failed ["+java.util.UUID.randomUUID()+"]: "+e.getClass().getSimpleName());System.exit(1);}
 }
}
