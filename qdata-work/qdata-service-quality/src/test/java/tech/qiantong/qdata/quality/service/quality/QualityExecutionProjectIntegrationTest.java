package tech.qiantong.qdata.quality.service.quality;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskObjDO;
import tech.qiantong.qdata.quality.dal.dataobject.qa.DppQualityTaskEvaluateDO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same retained MySQL ownership fixture as the API tests; no Hive client exists in this test. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class QualityExecutionProjectIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;QualityExecutionProjectGuard guard;
    @BeforeEach void setup(){String url=System.getenv("QDATA_QUALITY_PROJECT_URL");assertNotNull(url);assertTrue(url.contains("/qdata_quality_project_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());guard=new QualityExecutionProjectGuard(jdbc);
        var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM dpp_quality_task WHERE id=30"));row.put("id",900144L);row.put("project_id",144L);row.put("asset_id",null);row.put("status","0");row.put("system_job_id",null);jdbc.update("INSERT INTO dpp_quality_task ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
        assign(9,144);
    }
    @AfterEach void close(){manager.rollback(transaction);fixture.destroy();}
    void assign(long source,long project){jdbc.update("INSERT INTO da_datasource_project_rel(datasource_id,project_id,project_code,valid_flag) SELECT ?,id,code,'1' FROM att_project WHERE id=?",source,project);}
    QualityExecutionSnapshots.Plan plan(long source,String table){var object=new DppQualityTaskObjDO();object.setId(1L);object.setTaskId(900144L);object.setDatasourceId(source);object.setTableName(table);var rule=new DppQualityTaskEvaluateDO();rule.setId(2L);rule.setTaskId(900144L);rule.setObjId(1L);rule.setTableName(table);rule.setStatus("1");rule.setRuleType("ENUM_VALIDATION");rule.setRule("{}");return QualityExecutionSnapshots.freeze(900144,"worker project guard",List.of(object),List.of(rule));}
    @Test void approvedProjectAndSourceCanBeAdmittedWithoutBrowserSession(){assertEquals(144L,guard.validate(plan(9,"qdata_test.worker_scope")));}
    @Test void pausedTaskCannotBeTriggeredDirectly(){jdbc.update("UPDATE dpp_quality_task SET status='1' WHERE id=900144");assertThrows(IllegalArgumentException.class,()->guard.validate(plan(9,"qdata_test.worker_scope")));}
    @Test void disabledProjectStopsQueuedWork(){var plan=plan(9,"qdata_test.worker_scope");guard.validate(plan);jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=144");assertThrows(IllegalArgumentException.class,()->guard.validate(plan));}
    @Test void revokedConnectionStopsQueuedWork(){var plan=plan(9,"qdata_test.worker_scope");guard.validate(plan);jdbc.update("UPDATE da_datasource_project_rel SET valid_flag='0' WHERE datasource_id=9 AND project_id=144");assertThrows(IllegalArgumentException.class,()->guard.validate(plan));}
    @Test void disabledDatasourceStopsQueuedWork(){jdbc.update("UPDATE da_datasource SET valid_flag='0' WHERE id=9");assertThrows(IllegalArgumentException.class,()->guard.validate(plan(9,"qdata_test.worker_scope")));}
    @Test void warehouseNamespaceCannotBeBorrowedBySharedConnection(){assertThrows(IllegalArgumentException.class,()->guard.validate(plan(9,"dwd_iot_db.water_quality")));}
    @Test void secondConnectionDefaultCannotGrantAnotherProjectsWarehouse(){assign(13,144);assertThrows(IllegalArgumentException.class,()->guard.validate(plan(13,"water_quality")));}
    @Test void changedEndpointDoesNotGrantManagedDatabase(){jdbc.update("UPDATE da_datasource SET ip='unregistered.example' WHERE id=9");assertThrows(IllegalArgumentException.class,()->guard.validate(plan(9,"dwd_iot_db.water_quality")));}
    @Test void snapshotProjectCannotChangeAfterCapture(){var original=plan(9,"qdata_test.worker_scope");var content=JSON.parseObject(original.json());content.put("projectId",143);var different=new QualityExecutionSnapshots.Plan(original.taskId(),original.taskName(),content.toJSONString(),original.sha256());assertThrows(IllegalArgumentException.class,()->guard.validate(different));}
    @Test void unassignedLegacyTaskIsNotRunnable(){jdbc.update("UPDATE dpp_quality_task SET project_id=NULL WHERE id=900144");assertThrows(IllegalArgumentException.class,()->guard.validate(plan(9,"qdata_test.worker_scope")));}
}
