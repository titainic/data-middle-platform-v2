package tech.qiantong.qdata.common.database.core;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class HiveReadQueryTest {
    @Test void preservesDistinctCrossDatabaseSourcesAndIgnoresCteNames() {
        assertEquals(Set.of("ods_iot_db.water_quality","dwd_iot_db.water_quality"),HiveReadQuery.tables(
                "WITH raw AS (SELECT * FROM ods_iot_db.water_quality) SELECT a.device_id FROM raw a JOIN dwd_iot_db.water_quality b ON a.device_id=b.device_id", "qdata_test"));
    }
    @Test void allowsScalarQueriesAndWordsInsideStringLiterals() {
        assertEquals(Set.of(),HiveReadQuery.tables("SELECT 'drop; reflect(x)' AS value", "qdata_test"));
    }
    @Test void rejectsWritesAndMultipleStatementsWithoutCallingAnExecutor() {
        for(String sql:new String[]{"DROP TABLE any_table","INSERT OVERWRITE TABLE any_table SELECT 1","SELECT 1; SELECT 2"})
            assertThrows(IllegalArgumentException.class,()->HiveReadQuery.tables(sql,"qdata_test"));
    }
    @Test void reflectionCannotHideBehindComments() {
        assertThrows(IllegalArgumentException.class,()->HiveReadQuery.tables("SELECT reflect /*comment*/ ('unit.class','method')", "qdata_test"));
        assertThrows(IllegalArgumentException.class,()->HiveReadQuery.tables("SELECT java_method('unit.class','method')", "qdata_test"));
    }
    @Test void quotedExternalFunctionNamesAreRejected() {
        for(String name:Set.of("reflect","reflect2","java_method","transform"))
            assertThrows(IllegalArgumentException.class,()->HiveReadQuery.tables("SELECT `"+name+"`/**/('unit.class','method') FROM ods_iot_db.source", "qdata_test"));
    }
}
