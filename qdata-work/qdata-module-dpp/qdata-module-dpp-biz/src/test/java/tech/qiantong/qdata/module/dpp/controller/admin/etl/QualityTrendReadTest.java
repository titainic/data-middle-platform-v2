package tech.qiantong.qdata.module.dpp.controller.admin.etl;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppQualityLogDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppQualityLogService;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEvaluateLogServiceImpl;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QualityTrendReadTest {
 JdbcTemplate jdbc;IDppQualityLogService logs;DppEvaluateLogServiceImpl service;
 Date date(String value){return Date.from(LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant());}
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);logs=mock(IDppQualityLogService.class);service=new DppEvaluateLogServiceImpl();ReflectionTestUtils.setField(service,"trendJdbc",jdbc);ReflectionTestUtils.setField(service,"dppQualityLogService",logs);
  var row=new DppQualityLogDO();row.setQualityId("39");row.setScore(99L);when(logs.getDppQualityLogById(1L)).thenReturn(row);
 }
 @Test void reportsCountRowEvaluationsNotStoredQualityScoresAndIncludeWholeEndDay(){
  when(jdbc.queryForList(anyString(),eq("39"),eq("2024-02-24 00:00:00"),eq("2024-03-02 00:00:00"))).thenReturn(List.of(Map.of("day",java.sql.Date.valueOf("2024-02-29"),"checked_count",7L),Map.of("day",java.sql.Date.valueOf("2024-03-01"),"checked_count",15L)));
  var result=service.statisticsEvaluateTow(1L,date("2024-03-01"),date("2024-02-23"),0);
  assertEquals(List.of("2024-02-24","2024-02-25","2024-02-26","2024-02-27","2024-02-28","2024-02-29","2024-03-01"),result.getJSONArray("title").toJavaList(String.class));
  assertEquals(List.of(0L,0L,0L,0L,0L,7L,15L),result.getJSONArray("value").toJavaList(Long.class));assertEquals("rule_check_rows",result.getString("metric"));assertEquals("row_evaluations",result.getString("unit"));
  verify(jdbc).queryForList(contains("SUM(e.total)"),eq("39"),eq("2024-02-24 00:00:00"),eq("2024-03-02 00:00:00"));verify(logs,never()).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
 }
 @Test void selectedStartClampsQueryWithoutChangingRequestedAxis(){
  when(jdbc.queryForList(anyString(),anyString(),anyString(),anyString())).thenReturn(List.of());var result=service.statisticsEvaluateTow(1L,date("2024-03-01"),date("2024-02-29"),1);
  assertEquals(15,result.getJSONArray("title").size());assertEquals(0,result.getJSONArray("value").stream().mapToLong(x->((Number)x).longValue()).sum());verify(jdbc).queryForList(anyString(),eq("39"),eq("2024-02-29 00:00:00"),eq("2024-03-02 00:00:00"));
 }
 @Test void invalidRangesFailBeforeTheDatabase(){
  assertThrows(IllegalArgumentException.class,()->service.statisticsEvaluateTow(1L,null,null,3));assertThrows(IllegalArgumentException.class,()->service.statisticsEvaluateTow(1L,date("2024-02-01"),date("2024-03-01"),0));verifyNoInteractions(jdbc);
 }
}
