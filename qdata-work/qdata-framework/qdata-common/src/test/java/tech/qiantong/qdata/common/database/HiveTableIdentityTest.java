package tech.qiantong.qdata.common.database;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.dialect.HiveDialect;
import static org.junit.jupiter.api.Assertions.*;

class HiveTableIdentityTest {
    @Test void sameShortNameInDifferentDatabasesIsDistinct() {
        assertNotEquals(HiveTableIdentity.resolve("ods_iot_db.water_quality", "default"),
                HiveTableIdentity.resolve("dwd_iot_db.water_quality", "default"));
    }
    @Test void quotedIdentifierIsNormalizedWithoutUsingConnectionDefault() {
        assertEquals("`dwd_iot_db`.`water_quality`",
                HiveTableIdentity.resolve("`DWD_IOT_DB`.`Water_Quality`", "qdata_test").qualified());
    }
    @Test void legacyShortNamesKeepTheirDeclaredDefaultDatabase() {
        assertEquals("qdata_test.ods_env_water_quality",
                HiveTableIdentity.resolve("ods_env_water_quality", "qdata_test").canonical());
    }
    @Test void malformedOrExpressionNamesFailClosed() {
        for (String name : new String[]{"db.table.extra", "db.", ".table", "db.table;DROP", "db.`table", "db.`table`x", "db.a-b"})
            assertThrows(IllegalArgumentException.class, () -> HiveTableIdentity.resolve(name, "default"), name);
    }
    @Test void resolvingAResourceDoesNotChangeTheSharedConnection() {
        var connection = new DbQueryProperty("Hive", "example", "hadoop", "", 10000, "qdata_test", null);
        var resource = HiveTableIdentity.resolve("dwd_iot_db.water_quality", connection.getDbName());
        assertEquals("dwd_iot_db", resource.property(connection).getDbName());
        assertEquals("qdata_test", connection.getDbName());
    }
    @Test void dialectUsesActualNamespaceForExistenceAndColumns() {
        var dialect = new HiveDialect();
        var connection = new DbQueryProperty("Hive", "example", "hadoop", "", 10000, "qdata_test", null);
        assertEquals("DESCRIBE `dwd_iot_db`.`water_quality`", dialect.columns(connection, "dwd_iot_db.water_quality"));
        assertEquals("SHOW TABLES IN `dwd_iot_db` LIKE 'water_quality'",
                dialect.generateCheckTableExistsSQL(connection, "dwd_iot_db.water_quality"));
        assertEquals("`dwd_iot_db`.`water_quality`", dialect.buildTableNameByDbType(connection, "dwd_iot_db.water_quality"));
    }
}
