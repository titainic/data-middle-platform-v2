package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class HiveExecutionRegressionTest {
    private static void set(Object target, String name, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private List<String> command(String host, String bind) throws Exception {
        DppEtlTaskSparkRunner runner = new DppEtlTaskSparkRunner();
        set(runner, "sparkSubmitPath", "/opt/spark/bin/spark-submit");
        set(runner, "sparkMaster", "yarn");
        set(runner, "mainClass", "example.EtlApplication");
        set(runner, "mainJar", "file:/opt/spark/etl.jar");
        set(runner, "driverHost", host);
        set(runner, "driverBindAddress", bind);
        DppEtlTaskDO task = new DppEtlTaskDO();
        task.setId(26L);
        task.setDraftJson("{\"executorCores\":2,\"numExecutors\":1,\"executorMemory\":\"1g\"}");
        Method method = runner.getClass().getDeclaredMethod("buildCommand", DppEtlTaskDO.class, Path.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(runner, task, Path.of("/tmp/etl payload.b64"));
    }

    @Test
    public void usesExplicitRoutableDriverAndPreservesArgumentBoundaries() throws Exception {
        List<String> args = command("10.201.0.131", "0.0.0.0");
        assertTrue(args.contains("spark.driver.host=10.201.0.131"));
        assertTrue(args.contains("spark.driver.bindAddress=0.0.0.0"));
        assertEquals("2", args.get(args.indexOf("--executor-cores") + 1));
        assertEquals("client", args.get(args.indexOf("--deploy-mode") + 1));
        assertEquals("file:/tmp/etl payload.b64", args.get(args.size() - 1));
        assertEquals("file:/opt/spark/etl.jar", args.get(args.size() - 2));
        assertEquals("/opt/spark/etl.jar", args.get(args.indexOf("--driver-class-path") + 1));
    }

    @Test
    public void absentDriverOverridesLeaveSparkDiscoveryIntact() throws Exception {
        assertFalse(command("", " ").stream().anyMatch(s -> s.startsWith("spark.driver.")));
    }

    @Test
    public void hiveSqlDevelopmentLoadsHiveDriver() throws Exception {
        Method method = DataDevelopmentJdbcTaskRunner.class.getDeclaredMethod("getDriverClassName", String.class);
        method.setAccessible(true);
        Object runner = new DataDevelopmentJdbcTaskRunner();
        assertEquals("org.apache.hive.jdbc.HiveDriver", method.invoke(runner, "Hive"));
        assertEquals("org.apache.hive.jdbc.HiveDriver", method.invoke(runner, "HIVE"));
        assertEquals("com.mysql.cj.jdbc.Driver", method.invoke(runner, "MySql"));
    }
    @Test
    @SuppressWarnings("unchecked")
    public void hiveScriptKeepsSemicolonsInsideStrings() throws Exception {
        Method method = DataDevelopmentJdbcTaskRunner.class.getDeclaredMethod("splitJdbcSqlScript", String.class, String.class, String.class);
        method.setAccessible(true);
        List<String> statements = (List<String>) method.invoke(new DataDevelopmentJdbcTaskRunner(),
                "INSERT INTO t VALUES ('a;b'); SELECT * FROM t;", "Hive", null);
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("a;b"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "qdata.hive.integration", matches = "true")
    public void passwordlessHiveRunsDdlDmlAndQueryWithoutJdbcTransactions() throws Exception {
        String table = "qdata_test.codex_sql_dev_" + System.currentTimeMillis();
        com.alibaba.fastjson2.JSONObject params = new com.alibaba.fastjson2.JSONObject();
        params.put("sql", "CREATE TABLE " + table + " (id INT,note STRING) STORED AS PARQUET; INSERT INTO "
                + table + " VALUES (1,'semicolon;value'); SELECT * FROM " + table);
        params.put("sqlType", "1");
        params.put("datasources", com.alibaba.fastjson2.JSON.parseObject(
                "{\"datasourceType\":\"Hive\",\"ip\":\"10.131.135.14\",\"port\":10000,\"datasourceConfig\":{\"username\":\"hadoop\",\"dbname\":\"qdata_test\",\"password\":\"\"}}"));
        var node = new tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO();
        node.setName("Codex Hive DDL/DML regression");
        node.setParameters(params.toJSONString());
        Method method = DataDevelopmentJdbcTaskRunner.class.getDeclaredMethod("executeDataDevelopmentNodeSql", node.getClass(), StringBuilder.class);
        method.setAccessible(true);
        StringBuilder logs = new StringBuilder();
        assertNotNull(method.invoke(new DataDevelopmentJdbcTaskRunner(), node, logs));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:hive2://10.131.135.14:10000/qdata_test", "hadoop", "");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT note FROM " + table + " WHERE id=1")) {
            assertTrue(rows.next());
            assertEquals("semicolon;value", rows.getString(1));
            assertFalse(rows.next());
        }
        System.out.println("HIVE_SQL_TEST_TABLE=" + table);
    }

}
