package com.example.ems.upgrade;
import javax.crypto.*;import javax.crypto.spec.*;import java.nio.file.*;import java.io.*;import java.security.*;import java.util.*;
public final class BackupCrypto {
 private static final byte[] MAGIC="EMSBK001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
 public static String sha256(Path path)throws Exception{var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);}return HexFormat.of().formatHex(hash.digest());}
 private static SecretKey key(char[] password,byte[] salt,int rounds)throws Exception{
  if(password==null||password.length<12||password.length>256)throw new IllegalArgumentException("备份口令应为12至256字符");
  var spec=new PBEKeySpec(password,salt,rounds,256);
  try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}finally{spec.clearPassword();}
 }
 public static void encrypt(Path plain,Path dest,char[] password)throws Exception{
  byte[] salt=new byte[16],iv=new byte[12];var rand=new SecureRandom();rand.nextBytes(salt);rand.nextBytes(iv);
  var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(password,salt,600000),new GCMParameterSpec(128,iv));cipher.updateAAD(MAGIC);
  Path part=Path.of(dest+".partial");
  var file=Files.newOutputStream(part,StandardOpenOption.CREATE_NEW);
  try(var out=new DataOutputStream(file)){out.write(MAGIC);out.writeInt(600000);out.write(salt);out.write(iv);try(var encrypted=new CipherOutputStream(out,cipher)){Files.copy(plain,encrypted);}}
  catch(Exception e){Files.deleteIfExists(part);throw e;}
  Files.move(part,dest);
 }
 public static void decrypt(Path source,Path plain,char[] password)throws Exception{
  if(Files.exists(plain))throw new java.nio.file.FileAlreadyExistsException(plain.toString());
  if(Files.size(source)>512L*1024*1024)throw new IllegalArgumentException("备份文件超过512MiB");
  try(var in=new DataInputStream(Files.newInputStream(source))){
   if(!Arrays.equals(in.readNBytes(8),MAGIC))throw new IllegalArgumentException("不支持的备份格式");
   int rounds=in.readInt();if(rounds!=600000)throw new IllegalArgumentException("不支持的密钥参数");
   byte[] salt=in.readNBytes(16),iv=in.readNBytes(12);if(salt.length!=16||iv.length!=12)throw new IllegalArgumentException("备份已截断");
   var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(password,salt,rounds),new GCMParameterSpec(128,iv));cipher.updateAAD(MAGIC);
   try(var encrypted=new CipherInputStream(in,cipher);var out=Files.newOutputStream(plain,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[65536];long n=0;int read;while((read=encrypted.read(buffer))!=-1){n+=read;if(n>4L*1024*1024*1024)throw new IllegalArgumentException("恢复数据超过4GiB");out.write(buffer,0,read);}}
  }catch(Exception e){Files.deleteIfExists(plain);throw new IllegalArgumentException("备份口令不正确、文件被篡改或格式无效",e);}
 }
}
