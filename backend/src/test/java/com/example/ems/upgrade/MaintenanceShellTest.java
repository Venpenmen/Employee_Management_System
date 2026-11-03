package com.example.ems.upgrade;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MaintenanceShellTest {
 @Test void childPowerShellReconstructsSystemModulesWithoutChangingParent(){var parent=new HashMap<>(Map.of("PSModulePath","isolated-ps7-only-modules","PSModuleAnalysisCachePath","ps7-only-cache","KEEP","unchanged"));var child=new HashMap<>(parent);DatabaseJobs.windowsShellEnvironment(child);assertEquals(Map.of("KEEP","unchanged"),child);assertEquals(3,parent.size());}
 @Test void environmentNamesAreCaseInsensitiveForWindows(){var child=new HashMap<>(Map.of("psmodulepath","ps7-only"));DatabaseJobs.windowsShellEnvironment(child);assertTrue(child.isEmpty());}
 @Test void inheritedTaskSecretsAndChoicesCannotChangeAnotherJob(){var child=new HashMap<>(Map.of("EMS_EXPORT_TARGET_VERSION","1.2.0","EMS_BACKUP_PASSWORD","old-password","EMS_CONFIRM_SKIP","true","EMS_MAINT_ACTOR_HASH","old-session","EMS_COMPATIBILITY_PREVIEW","true","EMS_TLS_STORE_PASSWORD","local-tls-secret"));DatabaseJobs.windowsShellEnvironment(child);assertEquals(Map.of("EMS_TLS_STORE_PASSWORD","local-tls-secret"),child);}
}
