package tech.qiantong.qdata.module.mc.service.task.impl;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.core.DbTable;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class McTableSelectionTest {
    private DbTable table(String name) { var table = new DbTable(); table.setTableName(name); return table; }

    @Test void canonicalizesAndDeduplicatesFullyQualifiedNames() {
        assertEquals(Set.of("ods_iot_db.water_quality", "dwd_iot_db.water_quality"),
                McTableSelection.parse("ODS_IOT_DB.Water_Quality\n dwd_iot_db.water_quality,ods_iot_db.water_quality"));
    }
    @Test void rejectsPatternsAndAmbiguousShortNames() {
        for (String value : List.of("water_quality", "ods_iot_db.*", "a.b.c", "a.b; SELECT 1", "a.b -- x"))
            assertThrows(IllegalArgumentException.class, () -> McTableSelection.parse(value));
    }
    @Test void newVersionDoesNotJoinAnExistingFrozenCollection() {
        var original = table("water_quality");
        var version = table("water_quality__new_version");
        assertEquals(List.of(original), McTableSelection.select("ods_iot_db.water_quality", "ods_iot_db", List.of(original, version)));
        assertFalse(McTableSelection.includes("ods_iot_db.water_quality", "dwd_iot_db", "water_quality"));
    }
    @Test void aMissingRequestedVersionFailsBeforeMetadataComparison() {
        assertThrows(IllegalStateException.class, () -> McTableSelection.select("ods_iot_db.water_quality__missing", "ods_iot_db", List.of(table("water_quality"))));
        assertThrows(IllegalStateException.class, () -> McTableSelection.select("ods_iot_db.water_quality", "ods_iot_db", List.of()));
    }
    @Test void excludedHistoricalMetadataIsOutsideRemovalScope() {
        assertFalse(McTableSelection.includes("ods_iot_db.water_quality__new", "ods_iot_db", "water_quality__old"));
        assertTrue(McTableSelection.includes(null, "ods_iot_db", "water_quality__old"));
    }
    @Test void validatesDatabaseScopeAndConflictingExclusions() {
        assertThrows(IllegalArgumentException.class, () -> McTableSelection.validate("dwd_iot_db.water_quality", "Hive", "1", List.of("ods_iot_db"), null));
        assertThrows(IllegalArgumentException.class, () -> McTableSelection.validate("ods_iot_db.water_quality", "Hive", "2", List.of("ods_iot_db"), null));
        assertThrows(IllegalArgumentException.class, () -> McTableSelection.validate("ods_iot_db.water_quality", "MySQL", "1", List.of("ods_iot_db"), null));
        assertThrows(IllegalArgumentException.class, () -> McTableSelection.validate("ods_iot_db.water_quality", "Hive", "1", List.of("ods_iot_db"), "ods_iot_db.water_quality"));
        assertEquals("ods_iot_db.water_quality", McTableSelection.validate("ODS_IOT_DB.WATER_QUALITY", "Hive", "1", List.of("ods_iot_db"), null));
    }
    @Test void noSelectionPreservesLegacyCollection() {
        var tables = List.of(table("legacy"));
        assertSame(tables, McTableSelection.select(null, "existing", tables));
        assertEquals("", McTableSelection.validate(null, "MySQL", "2", List.of(), null));
    }
}
