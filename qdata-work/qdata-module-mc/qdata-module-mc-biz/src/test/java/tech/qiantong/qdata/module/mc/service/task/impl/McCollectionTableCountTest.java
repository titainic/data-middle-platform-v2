package tech.qiantong.qdata.module.mc.service.task.impl;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.core.DbTable;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class McCollectionTableCountTest {
    private DbTable table(String name) { DbTable t = new DbTable(); t.setTableName(name); return t; }
    @Test void excludesBlacklistedHistoricalTablesFromFailureDenominator() {
        assertEquals(2, McTaskServiceImpl.countCollectableTables(
            List.of(table("ods_env_water_quality"), table("ads_env_report_v1"), table("ads_env_report_v2")),
            "qdata_test", "qdata_test.ads_env_report_v1"));
    }
    @Test void noBlacklistCountsAllTablesAndEmptyInputCountsZero() {
        assertEquals(1, McTaskServiceImpl.countCollectableTables(List.of(table("current")), "qdata_test", null));
        assertEquals(0, McTaskServiceImpl.countCollectableTables(List.of(), "qdata_test", "*"));
    }
    @Test void excludedTableNameInAnotherDatabaseDoesNotHideCurrentTable() {
        assertEquals(1, McTaskServiceImpl.countCollectableTables(List.of(table("current")), "qdata_test", "other.current"));
    }
}
