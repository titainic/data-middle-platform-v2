package tech.qiantong.qdata.acceptance;
import org.junit.jupiter.api.Test;
import com.alibaba.druid.DbType;
import tech.qiantong.qdata.common.database.core.*;
import static org.junit.jupiter.api.Assertions.*;
class ReadOnlyServiceSqlTest {
    @Test void hiveQueriesPreserveAllPhysicalReferences(){assertEquals(java.util.Set.of("dwd_iot_db.water_quality","dwt_iot_db.dim_device"),HiveReadQuery.tables("WITH x AS (SELECT * FROM dwd_iot_db.water_quality) SELECT * FROM x JOIN dwt_iot_db.dim_device d ON x.device_id=d.device_id","qdata_test"));}
    @Test void mysqlQueriesSupportNamedServiceParameters(){assertEquals(java.util.Set.of("serving_iot_db.report"),ReadOnlySql.tables("SELECT value FROM serving_iot_db.report WHERE release_id=:releaseId",DbType.mysql));}
    @Test void mysqlIntoAndUpdateLocksAreRejected(){for(String sql:java.util.List.of("SELECT 1 INTO OUTFILE '/tmp/forbidden'","SELECT * FROM t FOR UPDATE"))assertThrows(IllegalArgumentException.class,()->ReadOnlySql.tables(sql,DbType.mysql));}
    @Test void nestedPostgresDataModificationIsRejected(){assertThrows(IllegalArgumentException.class,()->ReadOnlySql.tables("WITH x AS (DELETE FROM private_table RETURNING *) SELECT * FROM x",DbType.postgresql));}
    @Test void scriptsReflectionAndWaitFunctionsAreRejected(){for(String sql:java.util.List.of("SELECT sleep(1)","SELECT load_file('/etc/passwd')","SELECT reflect('java.lang.Runtime','exec','anything')"))assertThrows(IllegalArgumentException.class,()->ReadOnlySql.tables(sql,DbType.mysql));}
    @Test void multipleStatementsAndSyntaxErrorsDoNotExposeTheSqlInTheError(){assertThrows(IllegalArgumentException.class,()->ReadOnlySql.tables("SELECT 1; DELETE FROM t",DbType.mysql));var error=assertThrows(IllegalArgumentException.class,()->ReadOnlySql.tables("SELECT FROM WHERE 'fixture-sensitive-value'",DbType.mysql));assertFalse(error.getMessage().contains("fixture-sensitive-value"));}
}
