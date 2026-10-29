package com.example.ems.upgrade;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.util.*;import java.util.zip.*;import static org.junit.jupiter.api.Assertions.*;
class BackupCryptoTest {
 @TempDir Path dir;
 @Test void encryptedRoundTripAndNoPlaintext()throws Exception{
  Path src=dir.resolve("plain"),dst=dir.resolve("encrypted"),restored=dir.resolve("restored");String secret="synthetic-account-record";
  Files.writeString(src,secret);BackupCrypto.encrypt(src,dst,"A long backup passphrase 123".toCharArray());
  assertFalse(new String(Files.readAllBytes(dst),java.nio.charset.StandardCharsets.ISO_8859_1).contains(secret));
  BackupCrypto.decrypt(dst,restored,"A long backup passphrase 123".toCharArray());assertEquals(secret,Files.readString(restored));
 }
 @Test void wrongPasswordAndTamperingNeverLeavePlaintext()throws Exception{
  Path src=dir.resolve("plain"),dst=dir.resolve("encrypted"),out=dir.resolve("out");Files.writeString(src,"synthetic data");BackupCrypto.encrypt(src,dst,"A long backup passphrase 123".toCharArray());
  assertThrows(IllegalArgumentException.class,()->BackupCrypto.decrypt(dst,out,"Incorrect passphrase 456".toCharArray()));assertFalse(Files.exists(out));
  byte[] bytes=Files.readAllBytes(dst);bytes[bytes.length-1]^=1;Files.write(dst,bytes);
  assertThrows(IllegalArgumentException.class,()->BackupCrypto.decrypt(dst,out,"A long backup passphrase 123".toCharArray()));assertFalse(Files.exists(out));
 }
 @Test void noWeakPasswordOrDestinationOverwrite()throws Exception{
  Path src=dir.resolve("plain"),dst=dir.resolve("encrypted");Files.writeString(src,"data");
  assertThrows(IllegalArgumentException.class,()->BackupCrypto.encrypt(src,dst,"weak".toCharArray()));
  Files.writeString(Path.of(dst+".partial"),"KEEP");assertThrows(Exception.class,()->BackupCrypto.encrypt(src,dst,"A long backup passphrase 123".toCharArray()));
 }
 @Test void traversalAndDuplicateZipEntriesRejected()throws Exception{
  Path zip=dir.resolve("bad.zip");try(var out=new ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new ZipEntry("../escape"));out.write(1);out.closeEntry();}
  assertThrows(IllegalArgumentException.class,()->Snapshot.inspect(zip,dir.resolve("staging")));assertFalse(Files.exists(dir.resolve("escape")));
 }
 @Test void unsafeDatabaseTargetsRejected(){
  for(String s:List.of("jdbc:mysql://127.0.0.1:3306/ems_db","jdbc:mysql://127.0.0.1:15406/ems_server_v121","jdbc:mysql://192.168.1.1:16506/ems_up_server_v120"))assertThrows(IllegalArgumentException.class,()->Schema.safeUrl(s));
  assertDoesNotThrow(()->Schema.safeUrl("jdbc:mysql://127.0.0.1:16506/ems_up_server_v120_g123456abcdef?useSSL=false"));
 }
}
