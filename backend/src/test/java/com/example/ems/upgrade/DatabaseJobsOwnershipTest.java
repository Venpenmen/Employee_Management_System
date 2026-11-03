package com.example.ems.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseJobsOwnershipTest {
 @Test void onlyImportOwnerMayCommit(){
  assertDoesNotThrow(()->DatabaseJobs.requireImportOwner(Map.of("action","import","actor",1L),1L));
  assertEquals(403,assertThrows(ResponseStatusException.class,()->DatabaseJobs.requireImportOwner(Map.of("action","import","actor",1L),2L)).getStatusCode().value());
  assertEquals(403,assertThrows(ResponseStatusException.class,()->DatabaseJobs.requireImportOwner(Map.of("action","export","actor",1L),1L)).getStatusCode().value());
  assertEquals(403,assertThrows(ResponseStatusException.class,()->DatabaseJobs.requireImportOwner(Map.of("action","import"),1L)).getStatusCode().value());
 }
}
