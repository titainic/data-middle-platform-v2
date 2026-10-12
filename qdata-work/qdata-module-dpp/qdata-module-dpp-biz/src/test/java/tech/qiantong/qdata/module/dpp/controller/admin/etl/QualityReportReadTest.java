package tech.qiantong.qdata.module.dpp.controller.admin.etl;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.*;
import tech.qiantong.qdata.module.dpp.dal.dataobject.qa.DppQualityTaskEvaluateDO;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEvaluateLogMapper;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskObjRespVO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppQualityLogService;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEvaluateLogServiceImpl;
import tech.qiantong.qdata.module.dpp.service.qa.IDppQualityTaskEvaluateService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityReportReadTest {
 DppEvaluateLogServiceImpl service;IDppQualityTaskEvaluateService rules;DppEvaluateLogDO report;
 @BeforeEach void setup(){
  service=spy(new DppEvaluateLogServiceImpl());var logs=mock(IDppQualityLogService.class);var mapper=mock(DppEvaluateLogMapper.class);rules=mock(IDppQualityTaskEvaluateService.class);
  ReflectionTestUtils.setField(service,"dppQualityLogService",logs);ReflectionTestUtils.setField(service,"dppEvaluateLogMapper",mapper);ReflectionTestUtils.setField(service,"dppQualityTaskEvaluateService",rules);
  var log=new DppQualityLogDO();log.setId(1L);log.setQualityId("39");when(logs.getDppQualityLogById(1L)).thenReturn(log);doReturn(List.of()).when(service).buildTaskObjRespList(log);
  report=new DppEvaluateLogDO();report.setId(2L);report.setEvaluateId("3");report.setTableName("original.water");report.setColumnName("ph");report.setTotal(10L);report.setProblemTotal(2L);when(mapper.selectList("task_log_id",1L)).thenReturn(List.of(report));
 }
 @Test void missingCurrentRuleDoesNotHideHistoricalReport(){var rows=service.statisticsEvaluateTable(1L);assertEquals(1,rows.size());assertEquals(2L,rows.get(0).getId());assertEquals("original.water",rows.get(0).getTableName());assertEquals(new java.math.BigDecimal("20.00"),rows.get(0).getProportion());assertNull(rows.get(0).getDatasourceId());}
 @Test void changedObjectDoesNotRewriteHistoricalSourceAndNullCountsRemainReadable(){
  var rule=new DppQualityTaskEvaluateDO();rule.setObjId(12L);when(rules.getDppQualityTaskEvaluateById(3L)).thenReturn(rule);var object=new DppQualityTaskObjRespVO();object.setId(12L);object.setTableName("changed.air");object.setDatasourceId(99L);doReturn(List.of(object)).when(service).buildTaskObjRespList(any());report.setTotal(null);report.setProblemTotal(null);
  var row=service.statisticsEvaluateTable(1L).get(0);assertEquals("original.water",row.getTableName());assertNull(row.getDatasourceId());assertEquals(java.math.BigDecimal.ZERO,row.getProportion());
 }
}
