package com.example.ems.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupBundleTest {
 @TempDir Path dir;
 @Test void archiveContainsEncryptedBackupAndSafeVerificationFiles()throws Exception{
  Path backup=dir.resolve("backup.emsbk"),archive=dir.resolve("bundle.zip");
  Files.writeString(backup,"encrypted-fixture-only");
  BackupBundle.create(backup,archive);
  try(ZipFile zip=new ZipFile(archive.toFile())){
   assertEquals(Set.of("backup.emsbk","manifest.json","checksums.sha256","README.txt"),
      Collections.list(zip.entries()).stream().map(ZipEntry::getName).collect(java.util.stream.Collectors.toSet()));
   assertArrayEquals(Files.readAllBytes(backup),zip.getInputStream(zip.getEntry("backup.emsbk")).readAllBytes());
   String manifest=new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   assertTrue(manifest.contains("EMS-BACKUP-BUNDLE-1"));
   assertFalse(manifest.contains(dir.toString()));
   String sums=new String(zip.getInputStream(zip.getEntry("checksums.sha256")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   assertTrue(sums.contains(BackupBundle.sha256(backup)+"  backup.emsbk"));
  }
 }
 @Test void missingBackupDoesNotCreateArchive(){
  assertThrows(java.io.FileNotFoundException.class,()->BackupBundle.create(dir.resolve("missing.emsbk"),dir.resolve("bundle.zip")));
  assertFalse(Files.exists(dir.resolve("bundle.zip")));
 }
}
