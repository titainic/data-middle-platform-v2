package tech.qiantong.qdata.module.mc.service.metadata.dialect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McDbDO;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class HiveMetadataRegressionTest {
    @Test void unavailableExternalReaderDoesNotDiscardSchema() throws Exception {
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        Statement statement = org.mockito.Mockito.mock(Statement.class);
        ResultSet rows = org.mockito.Mockito.mock(ResultSet.class);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        org.mockito.Mockito.when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            if (((String)call.getArgument(0)).startsWith("SELECT COUNT")) throw new SQLException("missing external reader");
            return rows;
        });
        McDbDO db = new McDbDO(); db.setIp("test");db.setPort(10000);db.setDbName("qdata_test");
        db.setDatasourceConfig("{\"dbname\":\"default\",\"username\":\"hadoop\",\"password\":\"\"}");
        try (org.mockito.MockedStatic<DriverManager> drivers = org.mockito.Mockito.mockStatic(DriverManager.class)) {
            drivers.when(() -> DriverManager.getConnection(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(connection);
            DatabaseDialect.TableMetadata metadata = new HiveDialect().getTableMetadata(db, "external_table");
            assertNull(metadata.getRowCount());
            assertEquals("", metadata.getPartitionFields());
            assertEquals("Hive", metadata.getStorageEngine());
        }
    }
    @Test void crossDatabaseMetadataAndEmptyPartitionSchema() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("qdata.hive.integration"));
        Class.forName("org.apache.hive.jdbc.HiveDriver");
        McDbDO db = new McDbDO();
        db.setIp("10.131.135.14"); db.setPort(10000); db.setDbName("qdata_test");
        // Connection default intentionally differs from the table database; Hive has no password.
        db.setDatasourceConfig("{\"dbname\":\"default\",\"username\":\"hadoop\",\"password\":\"\"}");
        String table = "codex_metadata_regression_" + System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection("jdbc:hive2://10.131.135.14:10000/default", "hadoop", ""); Statement s = c.createStatement()) {
            try {
                s.execute("CREATE TABLE qdata_test." + table + " (id INT) PARTITIONED BY (dt STRING) STORED AS PARQUET");
                HiveDialect dialect = new HiveDialect();
                DatabaseDialect.TableMetadata empty = dialect.getTableMetadata(db, table);
                assertEquals(0L, empty.getRowCount());
                assertEquals("dt", empty.getPartitionFields());
                assertEquals("PARQUET", empty.getStorageEngine());
                assertTrue(dialect.getColumnMetadata(db, table, "dt").isPartitionField());
                assertFalse(dialect.getColumnMetadata(db, table, "id").isPartitionField());
                s.execute("INSERT INTO qdata_test." + table + " PARTITION(dt='2026-09-07') VALUES (1),(2)");
                assertEquals(2L, dialect.getTableMetadata(db, table).getRowCount());
            } finally { s.execute("DROP TABLE IF EXISTS qdata_test." + table); }
        }
    }
}
