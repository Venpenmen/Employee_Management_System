package com.example.ems.upgrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ConfirmationBindingTest {
 @TempDir Path job;final Instant now=Instant.parse("2026-10-29T00:00:00Z");
 void inputs()throws Exception{Files.writeString(job.resolve("input.emsbk"),"Synthetic encrypted fixture placeholder");Files.writeString(job.resolve("result.json"),"{\"synthetic\":true}");}
 @Test void bindsReportInputsAndExpiry()throws Exception{inputs();ConfirmationBinding.create(job,false,0,false,now);ConfirmationBinding.validate(job,false,false,now);Files.writeString(job.resolve("input.emsbk"),"changed");assertThrows(IllegalArgumentException.class,()->ConfirmationBinding.validate(job,false,false,now));}
 @Test void baseOnlyAndSkippedExtensionsRequireExplicitDecisions()throws Exception{inputs();ConfirmationBinding.create(job,true,2,false,now);assertThrows(IllegalArgumentException.class,()->ConfirmationBinding.validate(job,false,true,now));assertThrows(IllegalArgumentException.class,()->ConfirmationBinding.validate(job,true,false,now));ConfirmationBinding.validate(job,true,true,now);}
 @Test void changedSelectionChangesSingleUseAuthorizationObject()throws Exception{inputs();var receipt=ConfirmationBinding.create(job,true,1,false,now);String id=UUID.randomUUID().toString();assertNotEquals(ConfirmationBinding.object(id,receipt,true,true),ConfirmationBinding.object(id,receipt,true,false));}
 @Test void expiryAndBlockingCannotBeAcknowledgedAway()throws Exception{inputs();ConfirmationBinding.create(job,false,0,true,now);assertThrows(IllegalArgumentException.class,()->ConfirmationBinding.validate(job,true,true,now));}
 @Test void expiredPreviewAndChangedReportAreRejected()throws Exception{inputs();ConfirmationBinding.create(job,false,0,false,now);assertThrows(IllegalArgumentException.class,()->ConfirmationBinding.validate(job,false,false,now.plusSeconds(901)));Files.writeString(job.resolve("result.json"),"changed report");assertThrows(IllegalArgumentException.class,()->ConfirmationBinding.validate(job,false,false,now));}
 @Test void replacementAndExtraProofRequireRepreview()throws Exception{inputs();ConfirmationBinding.create(job,false,0,false,now);Files.writeString(job.resolve("return-proof.emsbk"),"New synthetic proof");assertThrows(IllegalArgumentException.class,()->ConfirmationBinding.validate(job,false,false,now));assertThrows(FileAlreadyExistsException.class,()->ConfirmationBinding.create(job,false,0,false,now));}
}
