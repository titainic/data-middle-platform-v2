package tech.qiantong.qdata.quality.repository;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tech.qiantong.qdata.quality.controller.quality.vo.CheckErrorDataReqDTO;
import tech.qiantong.qdata.quality.dal.dataobject.quality.CheckErrorData;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUALITY_MYSQL_TEST_URL",matches=".*qdata_mysql_quality_verify.*")
class MysqlQualityDataIntegrationTest {
    static AnnotationConfigApplicationContext context;
    static CheckErrorDataRepository repository;
    static JdbcTemplate jdbc;
    final String report = "mysql-verify-"+UUID.randomUUID();
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource datasource(){return new DriverManagerDataSource(System.getenv("QUALITY_MYSQL_TEST_URL"),System.getenv("QUALITY_MYSQL_TEST_USER"),System.getenv("QUALITY_MYSQL_TEST_PASSWORD"));}
        @Bean JdbcTemplate jdbc(DataSource d){return new JdbcTemplate(d);}
        @Bean DataSourceTransactionManager transactionManager(DataSource d){return new DataSourceTransactionManager(d);}
        @Bean CheckErrorDataRepository repository(JdbcTemplate j){return new CheckErrorDataRepository(j);}
    }
    @BeforeAll static void start(){context=new AnnotationConfigApplicationContext(Config.class);repository=context.getBean(CheckErrorDataRepository.class);jdbc=context.getBean(JdbcTemplate.class);}
    @AfterAll static void close(){context.close();}
    @AfterEach void clean(){jdbc.update("DELETE FROM quality_error_data WHERE report_id LIKE 'mysql-verify-%'");}
    CheckErrorData doc(String id,JSONObject data){return CheckErrorData.builder().id(id).reportId(report).count(3).errorCount(2).time(new Date(1788817067045L)).dataJsonStr(data.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue)).dataJsonStrOLd(data.toJSONString(com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue)).repair(0).remark("").build();}
    CheckErrorDataReqDTO request(){var dto=new CheckErrorDataReqDTO();dto.setReportId(report);return dto;}
    @Test void roundTripPreservesChineseDecimalsNullAndNestedJson(){
        var json=new JSONObject();json.put("测站", "南京一号🌧");json.put("amount",new BigDecimal("123456789012345.678901"));json.put("missing",null);json.put("nested",Map.of("codes",List.of("A","B")));
        var d=doc("a",json);repository.saveAll(List.of(d));var read=repository.getById(report,"a");
        assertEquals(json,read.getJsonData());assertEquals(json,read.getJsonDataOld());assertEquals(new BigDecimal("123456789012345.678901"),read.getJsonData().getBigDecimal("amount"));assertEquals(d.getTime(),read.getTime());
        var dto=request();dto.setKeyWordData(Map.of("amount","123456789012345.678901"));assertEquals(1,repository.page(PageRequest.of(0,10),dto).getTotalElements());
    }
    @Test void paginatesStablyAndIsolatesReports(){
        repository.saveAll(List.of(doc("a",new JSONObject()),doc("b",new JSONObject()),doc("c",new JSONObject())));
        var other=doc("d",new JSONObject());other.setReportId("mysql-verify-other");repository.saveAll(List.of(other));
        var first=repository.page(PageRequest.of(0,2),request());var next=repository.page(PageRequest.of(1,2),request());
        assertEquals(3,first.getTotalElements());assertEquals(List.of("c","b"),first.getContent().stream().map(CheckErrorData::getId).toList());assertEquals("a",next.getContent().get(0).getId());assertNull(repository.getById("wrong","a"));
    }
    @Test void keywordSearchIsCaseInsensitiveAndWildcardsAreLiteral(){
        repository.saveAll(List.of(doc("a",JSONObject.of("value","Rain_50%")),doc("b",JSONObject.of("value","RAIN-500"))));
        var dto=request();dto.setKeyWordData(Map.of("value","rain"));assertEquals(2,repository.page(PageRequest.of(0,10),dto).getTotalElements());
        dto.setKeyWordData(Map.of("value","_50%"));assertEquals(1,repository.page(PageRequest.of(0,10),dto).getTotalElements());
    }
    @Test void unusualJsonKeysAndSqlPayloadAreBoundAsData(){
        String key="测站\".x";String value="' OR 1=1 -- %_!";
        repository.saveAll(List.of(doc("a",JSONObject.of(key,value)),doc("b",JSONObject.of("value","other"))));
        var dto=request();dto.setKeyWordData(Map.of(key,value));assertEquals(1,repository.page(PageRequest.of(0,10),dto).getTotalElements());
        dto.setReportId("' OR 1=1 --");assertEquals(0,repository.page(PageRequest.of(0,10),dto).getTotalElements());
    }
    @Test void remarksAndBulkIgnoreCannotCrossReports(){
        repository.saveAll(List.of(doc("a",new JSONObject()),doc("b",new JSONObject())));
        var other=doc("c",new JSONObject());other.setReportId("mysql-verify-other");repository.saveAll(List.of(other));
        assertThrows(IllegalArgumentException.class,()->repository.updateRemark("wrong","a","unexpected","test"));assertTrue(repository.updateRemark(report,"a","已核实\n保留","test"));
        assertThrows(IllegalArgumentException.class,()->repository.changeState(report,List.of("a","b","c"),"3","","test"));assertTrue(repository.changeState(report,List.of("a","b"),"3","","test"));assertEquals(2,repository.getById(report,"a").getRepair());assertEquals(2,repository.getById(report,"b").getRepair());assertEquals(0,repository.getById("mysql-verify-other","c").getRepair());
        var dto=request();dto.setRepair(2);assertEquals(2,repository.page(PageRequest.of(0,10),dto).getTotalElements());
    }
    @Test void repairedDataKeepsPreviousSnapshot(){
        repository.saveAll(List.of(doc("a",JSONObject.of("amount",120.5))));
        assertTrue(repository.updateData(report,"a",Map.of("amount",80),"test",()->true));var read=repository.getById(report,"a");
        assertEquals(80,read.getJsonData().getIntValue("amount"));assertEquals(120.5,read.getJsonDataOld().getDoubleValue("amount"));assertEquals(1,read.getRepair());
    }
    @Test void invalidJsonRollsBackTheEntireRuleBatch(){
        var bad=doc("b",new JSONObject());bad.setDataJsonStr("invalid JSON");
        assertThrows(Exception.class,()->repository.saveAll(List.of(doc("a",new JSONObject()),bad)));
        assertEquals(0,repository.page(PageRequest.of(0,10),request()).getTotalElements());
    }
    @Test void noErrorsCreatesNoProblemRows(){repository.saveAll(List.of());assertEquals(0,repository.page(PageRequest.of(0,10),request()).getTotalElements());}
    @Test void reopeningRepairKeepsCurrentDataAndRecordsHistory(){
        repository.saveAll(List.of(doc("a",JSONObject.of("amount",120.5))));
        repository.updateData(report,"a",Map.of("amount",90),"operator",()->true);
        repository.changeState(report,List.of("a"),"4","","operator");
        var data=repository.getById(report,"a");assertEquals(0,data.getRepair());assertEquals(90,data.getJsonData().getIntValue("amount"));
        var history=repository.history(report,"a");assertEquals(2,history.size());assertEquals("REOPEN_REPAIR",history.get(0).get("action"));assertEquals("REPAIR",history.get(1).get("action"));assertEquals("operator",history.get(1).get("operator"));
    }
    @Test void deferredIgnoredAndReturnedStatesRoundTripInBatches(){
        repository.saveAll(List.of(doc("a",new JSONObject()),doc("b",new JSONObject())));
        for(String action:List.of("8","6","3","5","8","7")) assertTrue(repository.changeState(report,List.of("a","b"),action,"核对来源数据","operator"));
        assertEquals(0,repository.getById(report,"a").getRepair());assertEquals(0,repository.getById(report,"b").getRepair());assertEquals(6,repository.history(report,"a").size());
    }
    @Test void invalidMixedStateBatchIsNotPartiallyApplied(){
        var fixed=doc("b",new JSONObject());fixed.setRepair(1);repository.saveAll(List.of(doc("a",new JSONObject()),fixed));
        assertThrows(IllegalArgumentException.class,()->repository.changeState(report,List.of("a","b"),"4","","test"));
        assertEquals(0,repository.getById(report,"a").getRepair());assertEquals(1,repository.getById(report,"b").getRepair());assertTrue(repository.history(report,"b").isEmpty());
    }
    @Test void reasonIsRequiredForDeferAndReturn(){
        repository.saveAll(List.of(doc("a",new JSONObject())));
        assertThrows(IllegalArgumentException.class,()->repository.changeState(report,List.of("a"),"8"," ","test"));
        assertThrows(IllegalArgumentException.class,()->repository.changeState(report,List.of("a"),"7",null,"test"));
        assertThrows(IllegalArgumentException.class,()->repository.changeState(report,List.of("a"),"unsupported","","test"));assertEquals(0,repository.getById(report,"a").getRepair());
    }
    @Test void duplicateEmptyAndOversizedRequestsAreRejected(){
        repository.saveAll(List.of(doc("a",new JSONObject())));
        assertThrows(IllegalArgumentException.class,()->repository.changeState(report,List.of("a","a"),"3","","test"));
        assertThrows(IllegalArgumentException.class,()->repository.changeState(report,List.of(),"3","","test"));
        assertThrows(IllegalArgumentException.class,()->repository.changeState(report,java.util.stream.IntStream.range(0,501).mapToObj(String::valueOf).toList(),"3","","test"));assertEquals(0,repository.getById(report,"a").getRepair());
    }
    @Test void auditReadIsScopedToReportAndRecord(){
        repository.saveAll(List.of(doc("a",new JSONObject())));repository.updateRemark(report,"a","核验","test");
        assertEquals(1,repository.history(report,"a").size());assertTrue(repository.history("other-report","a").isEmpty());assertTrue(repository.history(report,"other-id").isEmpty());
    }
    @Test void auditFailureRollsBackStateAndRemark(){
        repository.saveAll(List.of(doc("a",new JSONObject())));jdbc.execute("RENAME TABLE quality_error_data_action TO quality_error_data_action_test_unavailable");
        try {assertThrows(Exception.class,()->repository.changeState(report,List.of("a"),"3","","test"));assertEquals(0,repository.getById(report,"a").getRepair());}
        finally {jdbc.execute("RENAME TABLE quality_error_data_action_test_unavailable TO quality_error_data_action");}
        assertTrue(repository.history(report,"a").isEmpty());
    }
    @Test void repairedOrIgnoredIssuesCannotBeWrittenWithoutReopening(){
        var fixed=doc("a",JSONObject.of("amount",90));fixed.setRepair(1);repository.saveAll(List.of(fixed));
        assertThrows(IllegalArgumentException.class,()->repository.updateData(report,"a",Map.of("amount",80),"test",()->true));assertEquals(90,repository.getById(report,"a").getJsonData().getIntValue("amount"));
    }

    @Test void auditFailurePreventsSourceRepairAndSourceFailureLeavesNoHistory(){
        repository.saveAll(List.of(doc("a",JSONObject.of("amount",120.5))));
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        jdbc.execute("RENAME TABLE quality_error_data_action TO quality_error_data_action_test_unavailable");
        try {assertThrows(Exception.class,()->repository.updateData(report,"a",Map.of("amount",90),"test",()->{changed.set(true);return true;}));assertFalse(changed.get());}
        finally {jdbc.execute("RENAME TABLE quality_error_data_action_test_unavailable TO quality_error_data_action");}
        assertThrows(IllegalArgumentException.class,()->repository.updateData(report,"a",Map.of("amount",90),"test",()->false));
        assertEquals(0,repository.getById(report,"a").getRepair());assertTrue(repository.history(report,"a").isEmpty());
    }

}
