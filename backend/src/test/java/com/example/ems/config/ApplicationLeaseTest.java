package com.example.ems.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.channels.*;
import static org.junit.jupiter.api.Assertions.*;
class ApplicationLeaseTest {
 @TempDir Path root;
 @Test void applicationLeaseExcludesMaintenanceUntilReleased()throws Exception{
  Files.createDirectories(root.resolve("data/complete/dev"));Path file=root.resolve("data/complete/dev/application.lease");
  try{ApplicationLease.hold(root,"dev");try(var other=FileChannel.open(file,StandardOpenOption.WRITE)){assertThrows(OverlappingFileLockException.class,other::tryLock);}assertDoesNotThrow(()->ApplicationLease.hold(root,"dev"));}
  finally{ApplicationLease.release();}
  try(var other=FileChannel.open(file,StandardOpenOption.WRITE);var lock=other.tryLock()){assertNotNull(lock);}
 }
 @Test void maintenanceLeasePreventsDirectMainBeforeAnyConnection()throws Exception{
  Path dir=Files.createDirectories(root.resolve("data/complete/server"));try(var maintenance=FileChannel.open(dir.resolve("application.lease"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=maintenance.tryLock()){assertNotNull(lock);assertThrows(IllegalStateException.class,()->ApplicationLease.hold(root,"server"));}finally{ApplicationLease.release();}
 }
 @Test void rc2LeaseNeverTouchesHistoricalLease()throws Exception{
  Path legacy=Files.createDirectories(root.resolve("data/complete/server")).resolve("application.lease"),next=Files.createDirectories(root.resolve("data/complete-rc2/server")).resolve("application.lease");
  try(var old=FileChannel.open(legacy,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var oldLock=old.tryLock()){
   assertNotNull(oldLock);ApplicationLease.hold(root,"server",true);
   try(var own=FileChannel.open(next,StandardOpenOption.WRITE)){assertThrows(OverlappingFileLockException.class,own::tryLock);}
  }finally{ApplicationLease.release();}
 }
}
