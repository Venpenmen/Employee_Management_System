package com.example.ems.config;

import java.nio.channels.*;
import java.nio.file.*;

/** Process-lifetime, per-installation/per-mode lease, including direct IDEA main(). */
public final class ApplicationLease {
 private static FileChannel channel;private static FileLock lock;private static Path owned;
 static {Runtime.getRuntime().addShutdownHook(new Thread(ApplicationLease::release,"ems-application-lease"));}
 private ApplicationLease(){}
 public static synchronized void hold(Path root,String mode){hold(root,mode,false);}
 public static synchronized void hold(Path root,String mode,boolean rc2){
  try{
   if(!java.util.Set.of("server","portable","dev").contains(mode))throw new IllegalArgumentException();
   Path dir=root.resolve((rc2?"data/complete-rc2/":"data/complete/")+mode);if(!Files.isDirectory(dir)||!IsolatedFixture.plain(dir))throw new IllegalArgumentException();Path file=dir.resolve("application.lease");if(!IsolatedFixture.plain(file))throw new IllegalArgumentException();
   if(channel!=null){if(owned.equals(file)&&lock.isValid())return;throw new IllegalStateException("A different application lease is already held");}
   FileChannel opened=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
   try{FileLock acquired=opened.tryLock();if(acquired==null)throw new IllegalStateException("Installation/mode is already running or under local maintenance");channel=opened;lock=acquired;owned=file;}catch(Exception failed){opened.close();throw failed;}
  }catch(Exception unavailable){throw new IllegalStateException("Cannot acquire complete-candidate application lease; no database connection attempted",unavailable);}
 }
 static synchronized void release(){try{if(lock!=null)lock.release();}catch(Exception ignored){}finally{try{if(channel!=null)channel.close();}catch(Exception ignored){}lock=null;channel=null;owned=null;}}
}
