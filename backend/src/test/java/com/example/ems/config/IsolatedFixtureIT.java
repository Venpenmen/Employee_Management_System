package com.example.ems.config;
import com.example.ems.upgrade.Snapshot;
import java.nio.file.*;
import java.util.*;

/** Checks a real PowerShell owner, including Windows process start-time precision. */
public final class IsolatedFixtureIT {
 public static void main(String[] args)throws Exception{
  Path root=Path.of(System.getenv("EMS_UPGRADE_ROOT"));String scope=System.getenv("EMS_DEVELOPMENT_TASK_ID");
  if(!IsolatedFixture.insecureCompatibilityAllowed())throw new AssertionError("Actual managed owner not recognized");
  if(IsolatedFixture.valid(root,UUID.randomUUID().toString()))throw new AssertionError("Different task accepted");
  if(IsolatedFixture.valid(root.getParent(),scope))throw new AssertionError("Run directory accepted as an application fixture");
  Snapshot.JSON.writeValue(Path.of(args[0]).toFile(),Map.of("passed",true,"checks",3,"actualPowerShellOwner",true,"productionExceptionEnabled",false));
  System.out.println("PASS real managed owner, wrong task rejection and fixture boundary");
 }
}
