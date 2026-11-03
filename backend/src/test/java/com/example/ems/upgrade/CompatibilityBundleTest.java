package com.example.ems.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class CompatibilityBundleTest {
 @TempDir Path root;
 @Test void compatibilityBundleHasOnlyCiphertextsAndPublicMetadata()throws Exception{
  Path source=Files.createDirectory(root.resolve("export"));Files.writeString(source.resolve("base-v120.emsbk"),"encrypted-base");Files.writeString(source.resolve("extensions.emsbk"),"encrypted-extension");Files.writeString(source.resolve("conversion-report.json"),"{\"targetVersion\":\"1.2.0\"}");Files.writeString(source.resolve("private-extra.txt"),"must-never-appear");
  Path result=BackupBundle.compatibility(source,root.resolve("result.zip"));
  try(var zip=new ZipFile(result.toFile())){var names=zip.stream().map(e->e.getName()).collect(java.util.stream.Collectors.toSet());assertEquals(Set.of("base-v120.emsbk","extensions.emsbk","conversion-report.json","manifest.json","SHA256SUMS.txt","README.txt"),names);String sums=new String(zip.getInputStream(zip.getEntry("SHA256SUMS.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(sums.contains(BackupBundle.sha256(source.resolve("base-v120.emsbk"))));assertTrue(sums.contains(BackupBundle.sha256(source.resolve("extensions.emsbk"))));assertFalse(new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes()).contains("proofKey"));}
 }
 @Test void incompleteExportCannotProduceDownload()throws Exception{assertThrows(java.io.IOException.class,()->BackupBundle.compatibility(root,root.resolve("result.zip")));assertFalse(Files.exists(root.resolve("result.zip")));}
}
