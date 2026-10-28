package com.example.ems.upgrade;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.zip.*;

final class BackupBundle {
 private BackupBundle() {}
 static Path create(Path backup, Path output) throws Exception {
  if (!Files.isRegularFile(backup)) throw new FileNotFoundException("Encrypted backup missing");
  Files.createDirectories(output.getParent());
  Path temporary=Files.createTempFile(output.getParent(),"bundle-", ".partial");
  try {
   String hash=sha256(backup);
   long size=Files.size(backup);
   String manifest="{\n  \"format\": \"EMS-BACKUP-BUNDLE-1\",\n  \"emsVersion\": \"1.2.0\",\n  \"createdAt\": \""+Instant.now()+"\",\n  \"backupFile\": \"backup.emsbk\",\n  \"backupBytes\": "+size+"\n}\n";
   byte[] manifestBytes=manifest.getBytes(StandardCharsets.UTF_8);
   String checksums=hash+"  backup.emsbk\n"+sha256(manifestBytes)+"  manifest.json\n";
   String readme="EMS encrypted database backup bundle\r\n\r\n"+
     "backup.emsbk: encrypted database backup; keep the backup passphrase separately.\r\n"+
     "manifest.json: non-sensitive bundle metadata.\r\n"+
     "checksums.sha256: SHA-256 digests of backup.emsbk and manifest.json.\r\n"+
     "To verify: sha256sum -c checksums.sha256 (from the extracted folder).\r\n"+
     "To restore: use the EMS database management page and upload backup.emsbk, not this ZIP.\r\n"+
     "Checksums detect accidental changes; they do not authenticate the source.\r\n"+
     "Never share the archive or passphrase with unauthorized people.\r\n";
   try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temporary),StandardCharsets.UTF_8)) {
    zip.putNextEntry(new ZipEntry("backup.emsbk"));Files.copy(backup,zip);zip.closeEntry();
    add(zip,"manifest.json",manifestBytes);
    add(zip,"checksums.sha256",checksums.getBytes(StandardCharsets.UTF_8));
    add(zip,"README.txt",readme.getBytes(StandardCharsets.UTF_8));
   }
   try {Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
   catch (AtomicMoveNotSupportedException ex){Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING);}
   return output;
  } finally {Files.deleteIfExists(temporary);}
 }
 private static void add(ZipOutputStream zip,String name,byte[] bytes)throws IOException{
  zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();
 }
 static String sha256(Path path)throws Exception{
  MessageDigest md=MessageDigest.getInstance("SHA-256");
  try(InputStream in=Files.newInputStream(path)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)md.update(buf,0,n);}
  return HexFormat.of().formatHex(md.digest());
 }
 static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
