package com.example.ems.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseUploadTest {
 @TempDir Path root;
 @Test void targetVersionIsExplicitAllowList(){assertDoesNotThrow(()->DatabaseJobs.validateTarget("current"));assertDoesNotThrow(()->DatabaseJobs.validateTarget("1.2.0"));assertThrows(IllegalArgumentException.class,()->DatabaseJobs.validateTarget("1.1.3"));}
 @Test void fixedDestinationNeverUsesClientFilenameOrOverwrites()throws Exception{var file=new MockMultipartFile("file","../../secret.emsbk","application/octet-stream",new byte[]{1,2,3});Path target=root.resolve("input.emsbk");DatabaseJobs.copyUpload(file,target);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(target));assertThrows(FileAlreadyExistsException.class,()->DatabaseJobs.copyUpload(file,target));assertThrows(IllegalArgumentException.class,()->DatabaseJobs.copyUpload(new MockMultipartFile("empty",new byte[0]),root.resolve("empty")));}
}
