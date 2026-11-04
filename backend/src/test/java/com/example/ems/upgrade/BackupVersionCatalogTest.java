package com.example.ems.upgrade;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackupVersionCatalogTest {
 ObjectNode manifest(String id){var profile=BackupVersionCatalog.PROFILES.get(id);var m=Snapshot.JSON.createObjectNode();m.put("format",1);m.put("programVersion",profile.programVersion());m.put("schemaVersion",profile.schemaVersion());var tables=m.putObject("tables");profile.columns().forEach((name,columns)->tables.putObject(name).set("columns",Snapshot.JSON.valueToTree(columns)));return m;}
 @Test void actualSparseDemoHasItsOwnTextProjection(){var profile=BackupVersionCatalog.identify(manifest("official-demo-120-sparse"));assertTrue(profile.serializedTextProjection());assertEquals(17,profile.columns().size());assertFalse(profile.columns().get("salary_record").containsKey("id"));}
 @Test void sameDisplayVersionDoesNotMakeAnUnknownStructureSupported(){var m=manifest("official-demo-120-sparse");((ObjectNode)m.path("tables").path("employee").path("columns")).put("unregistered","VARCHAR");assertThrows(IllegalArgumentException.class,()->BackupVersionCatalog.identify(m));}
 @Test void unknownVersionCannotBorrowAKnownProjection(){var m=manifest("canonical-121");m.put("programVersion","9.9.9");assertThrows(IllegalArgumentException.class,()->BackupVersionCatalog.identify(m));}
 @Test void canonicalCurrentAndLegacyTargetAreDistinct(){assertEquals("canonical-121",BackupVersionCatalog.identify(manifest("canonical-121")).id());assertEquals("canonical-120",BackupVersionCatalog.identify(manifest("canonical-120")).id());}
 @Test void sourceTypeDeclarationsMustMatchRegisteredProjection(){var m=manifest("canonical-120");((ObjectNode)m.path("tables").path("salary_record").path("columns")).put("actual_salary","VARCHAR");assertThrows(IllegalArgumentException.class,()->BackupVersionCatalog.identify(m));}
 @Test void futureSchemaAndUnknownTablesAreRejected(){var future=manifest("canonical-121");future.put("schemaVersion",4);assertThrows(IllegalArgumentException.class,()->BackupVersionCatalog.identify(future));var altered=manifest("canonical-121");((ObjectNode)altered.path("tables")).putObject("unknown").putObject("columns").put("id","BIGINT");assertThrows(IllegalArgumentException.class,()->BackupVersionCatalog.identify(altered));}
}
