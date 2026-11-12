package com.example.ems.upgrade;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.*;

final class BackupBundle {
 private BackupBundle() {}
 static Path compatibility(Path directory,Path output)throws Exception{
  List<String> names=List.of("base-v120.emsbk","extensions.emsbk","conversion-report.json");
  for(String name:names)if(!Files.isRegularFile(directory.resolve(name),LinkOption.NOFOLLOW_LINKS))throw new IOException("Compatibility export incomplete");
  Path temporary=Files.createTempFile(output.getParent(),"compatibility-",".partial");
  try{
   StringBuilder checksums=new StringBuilder();
   try(var zip=new ZipOutputStream(Files.newOutputStream(temporary),StandardCharsets.UTF_8)){
    for(String name:names){Path file=directory.resolve(name);checksums.append(sha256(file)).append("  ").append(name).append('\n');zip.putNextEntry(new ZipEntry(name));Files.copy(file,zip);zip.closeEntry();}
    byte[] manifest=Snapshot.JSON.writeValueAsBytes(Map.of("format","EMS-COMPATIBILITY-BUNDLE-1","targetVersion","1.2.0","encryptedInputs",true,"returnProofRequiredAfterOldVersionChanges",true));add(zip,"manifest.json",manifest);checksums.append(sha256(manifest)).append("  manifest.json\n");add(zip,"SHA256SUMS.txt",checksums.toString().getBytes(StandardCharsets.UTF_8));
    add(zip,"README.txt",("配对兼容备份：base-v120.emsbk 与 extensions.emsbk 必须共同保管。\r\n"+"仅在受控旧版副本中使用，不覆盖原安装。旧版修改后通过工具导出回程基础和证明，再在新版隔离预览。\r\n"+"扩展与证明包含敏感信息，禁止公开分享；口令应另行保存。缺少扩展或证明时不能宣称无损恢复。\r\n").getBytes(StandardCharsets.UTF_8));
   }
   try{Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING);}return output;
  }finally{Files.deleteIfExists(temporary);}
 }
 static Path create(Path backup, Path output) throws Exception {
  if (!Files.isRegularFile(backup)) throw new FileNotFoundException("Encrypted backup missing");
  Files.createDirectories(output.getParent());
  Path temporary=Files.createTempFile(output.getParent(),"bundle-", ".partial");
  try {
   String hash=sha256(backup);
   long size=Files.size(backup);
   String manifest="{\n  \"format\": \"EMS-BACKUP-BUNDLE-1\",\n  \"emsVersion\": \"1.2.2\",\n  \"createdAt\": \""+Instant.now()+"\",\n  \"backupFile\": \"backup.emsbk\",\n  \"backupBytes\": "+size+"\n}\n";
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
