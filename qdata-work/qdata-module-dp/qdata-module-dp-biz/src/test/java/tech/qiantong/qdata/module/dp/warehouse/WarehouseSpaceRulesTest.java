package tech.qiantong.qdata.module.dp.warehouse;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import java.util.*;
import java.sql.ResultSet;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseSpaceRulesTest {
    @Test void existingDatasetCannotBeReusedByAnUnrelatedPhysicalTable() {
        assertTrue(WarehouseSpaceService.sameLogicalDataset("dim_device", "dim_device"));
        assertTrue(WarehouseSpaceService.sameLogicalDataset("dim_device", "dim_device__8f47e480a3d54ceeb5c138895016ef3f"));
        assertFalse(WarehouseSpaceService.sameLogicalDataset("dim_device", "dim_device_location_history_p6_20260923"));
        assertFalse(WarehouseSpaceService.sameLogicalDataset("environment_quality_report", "device_location_history_summary_p6_20260923"));
        assertFalse(WarehouseSpaceService.sameLogicalDataset("dim_device", "dim_device__"));
    }
    @Test void requiresAllFiveDistinctDatabases() {
        var bindings = new LinkedHashMap<String,String>();
        WarehouseSql.LAYERS.forEach(layer -> bindings.put(layer, layer.toLowerCase() + "_iot_db"));
        assertEquals(5, WarehouseSpaceService.validateDatabases(bindings).size());
        bindings.put("DWD", "ODS_IOT_DB");
        assertThrows(IllegalArgumentException.class, () -> WarehouseSpaceService.validateDatabases(bindings));
        bindings.remove("DWD");
        assertThrows(IllegalArgumentException.class, () -> WarehouseSpaceService.validateDatabases(bindings));
    }
    @Test void credentialRotationAndDefaultDatabaseDoNotChangeCatalogIdentity() {
        var first = new DbQueryProperty("Hive", "hive.example", "hadoop", "", 10000, "qdata_test", null);
        var second = new DbQueryProperty("Hive", "HIVE.EXAMPLE", "hadoop", "rotated", 10000, "ods_iot_db", null);
        assertEquals(WarehouseSpaceService.connectionFingerprint(first), WarehouseSpaceService.connectionFingerprint(second));
        second.setHost("other-hive.example");
        assertNotEquals(WarehouseSpaceService.connectionFingerprint(first), WarehouseSpaceService.connectionFingerprint(second));
    }
    @Test void differentDiscoveryNamespacesAreNotAssumedToBeSameEngine() {
        var first = new DbQueryProperty("Hive", "discovery.example", "hadoop", "", 2181, "default", null);
        var second = first.copy();
        first.setConfig(Map.of("serviceDiscoveryMode", "zooKeeper", "zooKeeperNamespace", "cluster_one"));
        second.setConfig(Map.of("serviceDiscoveryMode", "zooKeeper", "zooKeeperNamespace", "cluster_two"));
        assertNotEquals(WarehouseSpaceService.connectionFingerprint(first), WarehouseSpaceService.connectionFingerprint(second));
    }
    @Test void readsThePhysicalMetastoreIdentityFromHiveServer2() throws Exception {
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(statement.executeQuery("SET hive.metastore.uris")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("hive.metastore.uris=thrift://metastore.example:9083");
        assertEquals("thrift://metastore.example:9083", WarehouseSpaceService.metastoreSetting(statement));
        verify(rows).close();
    }
    @Test void readsKyuubiTwoColumnSettingAndStillRejectsMissingIdentity() throws Exception {
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(statement.executeQuery("SET hive.metastore.uris")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("hive.metastore.uris");
        when(rows.getString(2)).thenReturn("thrift://metastore.example:9083");
        assertEquals("thrift://metastore.example:9083", WarehouseSpaceService.metastoreSetting(statement));
        for (String unavailable : Arrays.asList(null, "", "<undefined>")) {
            when(rows.getString(2)).thenReturn(unavailable);
            assertThrows(IllegalArgumentException.class, () -> WarehouseSpaceService.metastoreSetting(statement));
        }
        when(rows.getString(1)).thenReturn("unrelated.setting");
        assertThrows(IllegalArgumentException.class, () -> WarehouseSpaceService.metastoreSetting(statement));
    }
}
