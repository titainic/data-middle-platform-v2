package tech.qiantong.qdata.module.mc.service.metadata.dialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McDbDO;
import tech.qiantong.qdata.common.database.core.DbColumn;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
@EnabledIfSystemProperty(named="qdata.hive.readonly.integration",matches="true")
class HiveExistingMetadataIntegrationTest {
    private McDbDO database() throws Exception {
        Class.forName("org.apache.hive.jdbc.HiveDriver");
        var db=new McDbDO();db.setIp("10.131.135.14");db.setPort(10000);db.setDbName("qdata_test");
        db.setDatasourceConfig("{\"dbname\":\"qdata_test\",\"username\":\"hadoop\",\"password\":\"\"}");return db;
    }
    private List<String> columns(String table) throws Exception {
        assertTrue(table.matches("[A-Za-z0-9_]+"));
        List<DbColumn> raw=new ArrayList<>();
        var dialect=new tech.qiantong.qdata.common.database.dialect.HiveDialect();
        try(Connection c=DriverManager.getConnection("jdbc:hive2://10.131.135.14:10000/qdata_test","hadoop","");Statement s=c.createStatement();ResultSet rows=s.executeQuery("DESCRIBE qdata_test.`"+table+"`")) {
            while(rows.next())raw.add(dialect.columnMapper().mapRow(rows,raw.size()));
        }
        return tech.qiantong.qdata.common.database.dialect.HiveDialect.normalizeColumns(raw).stream().map(DbColumn::getColName).toList();
    }
    @Test void ownedTableMetadataUsesHiveServer2() throws Exception {
        String table=System.getProperty("qdata.hive.readonly.table");assumeTrue(table!=null);
        var metadata=new HiveDialect().getTableMetadata(database(),table);
        assertEquals(2L,metadata.getRowCount());assertEquals("PARQUET",metadata.getStorageEngine());
        assertEquals(List.of("id","name","amount","dt"),columns(table));
    }
    @Test void emptyPartitionedTableStillExposesPartitionColumn() throws Exception {
        String table=System.getProperty("qdata.hive.readonly.partitionTable");assumeTrue(table!=null);
        var metadata=new HiveDialect().getTableMetadata(database(),table);
        assertEquals(0L,metadata.getRowCount());assertEquals("dt",metadata.getPartitionFields());
        assertEquals(List.of("id","note","dt"),columns(table));
    }
}
