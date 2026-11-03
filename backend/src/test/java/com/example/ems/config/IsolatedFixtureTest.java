package com.example.ems.config;
import com.example.ems.upgrade.Snapshot;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedFixtureTest {
 @TempDir Path root;
 String scope=UUID.randomUUID().toString();
 Path fixture;com.fasterxml.jackson.databind.node.ObjectNode manifest;
 void fixture()throws Exception{
  root=root.toRealPath();Path run=root.resolve(".release-work/runs/synthetic-owned-run");fixture=run.resolve("instance");Files.createDirectories(fixture);
  var self=ProcessHandle.current();manifest=Snapshot.JSON.createObjectNode();manifest.put("schema",1);manifest.put("managed",true);manifest.put("id",run.getFileName().toString());manifest.put("root",root.toString());manifest.put("path",run.toString());manifest.put("status","running");manifest.put("ownerTaskId",scope);manifest.put("ownerId",self.pid());manifest.put("ownerTicks",Long.toString(IsolatedFixture.ticks(self.info().startInstant().orElseThrow())));manifest.put("ownerExecutable",self.info().command().orElseThrow());save();
 }
 void save()throws Exception{Snapshot.JSON.writeValue(fixture.getParent().resolve("run.json").toFile(),manifest);}
 @Test void ordinaryFolderOrMarkerAloneCannotEnableException()throws Exception{Files.writeString(root.resolve("test-fixture.json"),"{}");assertFalse(IsolatedFixture.valid(root,scope));}
 @Test void liveOwnedFixtureIsRecognized()throws Exception{fixture();assertTrue(IsolatedFixture.valid(fixture,scope));}
 @Test void mismatchedTaskScopeIsDenied()throws Exception{fixture();assertFalse(IsolatedFixture.valid(fixture,UUID.randomUUID().toString()));}
 @Test void pidOrStartTimeMismatchIsDenied()throws Exception{fixture();manifest.put("ownerId",0);save();assertFalse(IsolatedFixture.valid(fixture,scope));manifest.put("ownerId",ProcessHandle.current().pid());manifest.put("ownerTicks",Long.toString(IsolatedFixture.ticks(ProcessHandle.current().info().startInstant().orElseThrow())+10000));save();assertFalse(IsolatedFixture.valid(fixture,scope));}
 @Test void forgedExecutableOrFinishedTaskIsDenied()throws Exception{fixture();manifest.put("ownerExecutable",root.resolve("unrelated.exe").toString());save();assertFalse(IsolatedFixture.valid(fixture,scope));manifest.put("ownerExecutable",ProcessHandle.current().info().command().orElseThrow());manifest.put("status","succeeded");save();assertFalse(IsolatedFixture.valid(fixture,scope));}
 @Test void projectOrRunPathCannotBeForged()throws Exception{fixture();manifest.put("root",root.resolve("other").toString());save();assertFalse(IsolatedFixture.valid(fixture,scope));}
}
