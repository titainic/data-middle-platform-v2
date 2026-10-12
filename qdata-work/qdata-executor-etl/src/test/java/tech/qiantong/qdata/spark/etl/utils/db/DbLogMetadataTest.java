package tech.qiantong.qdata.spark.etl.utils.db;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DbLogMetadataTest {

    @Test
    void databaseLogSummaryNeverSerializesConnectionSecrets() {
        String canary = "SYNTHETIC_CANARY_PASSWORD_20260928";
        JSONObject parameter = JSONObject.parseObject("""
                {
                  "datasourceId": "42",
                  "dbType": "MySql",
                  "password": "%s",
                  "writerProperty": {
                    "password": "%s",
                    "datasourceConfig": {"keySecret": "%s"}
                  },
                  "connection": {"table": "dwd_iot_db.water_reading", "jdbcUrl": "jdbc:mysql://host/db?password=%s"}
                }
                """.formatted(canary, canary, canary, canary));

        String logged = "Database input: " + DbLogMetadata.describe(parameter, parameter.getString("dbType"));
        assertEquals("Database input: datasourceId=42, dbType=MySql, table=dwd_iot_db.water_reading", logged);
        assertFalse(logged.contains(canary));
        assertFalse(logged.contains("jdbc:mysql"));
    }

    @Test
    void invalidMetadataCannotInjectConnectionDetailsIntoLogs() {
        JSONObject parameter = JSONObject.parseObject("""
                {"datasourceId":"42 password=synthetic", "connection":{"table":"water;password=synthetic"}}
                """);

        assertEquals("datasourceId=<redacted>, dbType=<redacted>, table=<redacted>",
                DbLogMetadata.describe(parameter, "MySql password=synthetic"));
    }
}
