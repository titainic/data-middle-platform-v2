package tech.qiantong.qdata.quality.service.quality.impl;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.quality.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.quality.dal.dataobject.quality.QualityRuleEntity;
import tech.qiantong.qdata.quality.utils.quality.enums.CommonGenerator;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class HiveQualityNamespaceTest {
    private QualityRuleEntity rule(String table) {
        var source = new DaDatasourceDO(); source.setDatasourceType("Hive"); source.setIp("example"); source.setPort(10000L);
        source.setDatasourceConfig("{\"dbname\":\"qdata_test\",\"username\":\"hadoop\",\"password\":\"\"}");
        var rule = new QualityRuleEntity(); rule.setDaDatasourceById(source); rule.setTableName(table); rule.setConfig(new HashMap<>());
        return rule;
    }
    @Test void declaredNamespaceWinsOverConnectionDefault() {
        var rule = rule("dwd_iot_db.water_quality");
        assertTrue(CommonGenerator.generateSql(rule, "ph_value < 0").contains("FROM `dwd_iot_db`.`water_quality`"));
        assertEquals("dwd_iot_db.water_quality", rule.getTableName());
        var property = new DbQueryProperty("Hive", "example", "hadoop", "", 10000, "qdata_test", null);
        assertEquals("`dwd_iot_db`.`water_quality`", QualityTaskExecutorServiceImpl.buildFullTableName(property, rule.getTableName()));
    }
    @Test void legacyIssueNamesRemainUnchangedWhileSqlIsFullyQualified() {
        var rule = rule("dwd_env_water_quality");
        assertEquals("`qdata_test`.`dwd_env_water_quality`", rule.sqlTableName());
        assertEquals("dwd_env_water_quality", rule.getTableName());
    }
    @Test void queryTargetsCannotContainAdditionalSql() {
        var rule = rule("dwd_iot_db.water_quality;DROP TABLE x");
        assertThrows(IllegalArgumentException.class, rule::sqlTableName);
    }
}
