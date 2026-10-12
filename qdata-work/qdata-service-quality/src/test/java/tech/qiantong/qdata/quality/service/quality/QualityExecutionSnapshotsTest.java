package tech.qiantong.qdata.quality.service.quality;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskObjDO;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskEvaluateDO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QualityExecutionSnapshotsTest {
    @Test void managedAssessmentUsesThePlannedDefinitionInsteadOfCurrentTaskEdits() {
        var tasks=org.mockito.Mockito.mock(tech.qiantong.qdata.quality.service.qa.IDppQualityTaskService.class);
        var objects=org.mockito.Mockito.mock(tech.qiantong.qdata.quality.service.qa.IDppQualityTaskObjService.class);
        var rules=org.mockito.Mockito.mock(tech.qiantong.qdata.quality.service.qa.IDppQualityTaskEvaluateService.class);
        var manager=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var object=object(1,"dwd_iot_db.water_quality");var rule=rule(2,1,object.getTableName());
        var plan=QualityExecutionSnapshots.freeze(7,"Frozen",List.of(object),List.of(rule));
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(7L))).thenReturn(List.of(Map.of("planned_json",plan.json(),"status","RUNNING")));
        var captured=new QualityExecutionSnapshots(tasks,objects,rules,manager,jdbc,org.mockito.Mockito.mock(QualityExecutionProjectGuard.class)).capture(7);
        assertEquals(plan.sha256(),captured.sha256());org.mockito.Mockito.verifyNoInteractions(tasks,objects,rules);
    }
    @Test void aCompletedAssessmentCannotClaimAnotherExecution() {
        var manager=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(7L))).thenReturn(List.of(Map.of("status","PASSED","log_id",12L)));
        var service=new QualityExecutionSnapshots(null,null,null,manager,jdbc,org.mockito.Mockito.mock(QualityExecutionProjectGuard.class));
        assertThrows(IllegalArgumentException.class,()->service.capture(7));
    }
    private DppQualityTaskObjDO object(long id,String table) {
        var object=new DppQualityTaskObjDO();object.setId(id);object.setTaskId(7L);object.setDatasourceId(9L);object.setTableName(table);return object;
    }
    private DppQualityTaskEvaluateDO rule(long id,long objectId,String table) {
        var rule=new DppQualityTaskEvaluateDO();rule.setId(id);rule.setTaskId(7L);rule.setObjId(objectId);rule.setTableName(table);
        rule.setStatus("1");rule.setRuleType("ENUM_VALIDATION");rule.setEvaColumn("data_quality");rule.setRule("{\"validValues\":[\"good\"]}");return rule;
    }
    @Test void changesToCurrentDefinitionsDoNotChangePreparedExecution() {
        var object=object(1,"dwd_iot_db.water_quality");var rule=rule(2,1,object.getTableName());
        rule.setParams(new HashMap<>(Map.of("password","unit-secret")));
        var plan=QualityExecutionSnapshots.freeze(7,"Unit plan",List.of(object),List.of(rule));
        rule.setRule("{}");object.setTableName("other.table");
        assertEquals("dwd_iot_db.water_quality",plan.objects().get(0).getTableName());
        assertTrue(plan.rules().get(0).getRule().contains("good"));assertFalse(plan.json().contains("unit-secret"));
        plan.rules().get(0).setRule("{}");assertTrue(plan.rules().get(0).getRule().contains("good"));
    }
    @Test void inputOrderDoesNotChangeTheRecordedDefinitionHash() {
        var a=object(1,"dwd_iot_db.water_quality");var b=object(3,"dwd_iot_db.air_quality");
        var ra=rule(2,1,a.getTableName());var rb=rule(4,3,b.getTableName());
        assertEquals(QualityExecutionSnapshots.freeze(7,"Unit plan",List.of(a,b),List.of(ra,rb)).sha256(),
                QualityExecutionSnapshots.freeze(7,"Unit plan",List.of(b,a),List.of(rb,ra)).sha256());
    }
    @Test void disabledRulesAreExcludedAndAnEmptyEnabledPlanIsRejected() {
        var a=object(1,"dwd_iot_db.water_quality");var enabled=rule(2,1,a.getTableName());var disabled=rule(3,1,a.getTableName());disabled.setStatus("0");
        assertEquals(1,QualityExecutionSnapshots.freeze(7,"Unit",List.of(a),List.of(enabled,disabled)).rules().size());
        assertThrows(IllegalArgumentException.class,()->QualityExecutionSnapshots.freeze(7,"Unit",List.of(a),List.of(disabled)));
    }
    @Test void foreignTaskRulesAndMismatchedObjectsAreRejected() {
        var a=object(1,"dwd_iot_db.water_quality");var rule=rule(2,1,"other.table");
        assertThrows(IllegalArgumentException.class,()->QualityExecutionSnapshots.freeze(7,"Unit",List.of(a),List.of(rule)));
        rule.setTableName(a.getTableName());rule.setTaskId(8L);
        assertThrows(IllegalArgumentException.class,()->QualityExecutionSnapshots.freeze(7,"Unit",List.of(a),List.of(rule)));
    }
}
