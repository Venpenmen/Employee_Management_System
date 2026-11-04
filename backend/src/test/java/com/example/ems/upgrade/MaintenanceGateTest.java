package com.example.ems.upgrade;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MaintenanceGateTest {
 @TempDir Path work;
 @Test void requestWaitingForDrainRechecksMaintenanceBeforeBusinessWrite()throws Exception{
  var jobs=mock(DatabaseJobs.class);var gate=new MaintenanceGate(jobs){@Override Path dir(){return work;}};
  var field=MaintenanceGate.class.getDeclaredField("gate");field.setAccessible(true);var lock=(ReentrantReadWriteLock)field.get(gate);
  var request=new MockHttpServletRequest("POST","/api/protected");var response=new MockHttpServletResponse();var wrote=new AtomicBoolean();var executor=Executors.newSingleThreadExecutor();
  lock.writeLock().lock();try{
   var entered=new CountDownLatch(1);var future=executor.submit(()->{entered.countDown();gate.doFilterInternal(request,response,(r,s)->wrote.set(true));return null;});assertTrue(entered.await(5,TimeUnit.SECONDS));
   Files.writeString(work.resolve("maintenance.flag"),"owned-task");lock.writeLock().unlock();future.get(5,TimeUnit.SECONDS);assertEquals(503,response.getStatus());assertFalse(wrote.get());
  }finally{if(lock.isWriteLockedByCurrentThread())lock.writeLock().unlock();executor.shutdownNow();gate.stop();}
 }
}
