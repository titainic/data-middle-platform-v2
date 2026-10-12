package tech.qiantong.qdata.module.dpp.controller.admin.etl;

import org.junit.jupiter.api.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppQualityLogPageReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskAssetReqVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppQualityLogDO;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppQualityLogMapper;
import tech.qiantong.qdata.module.dpp.dal.mapper.qa.DppQualityTaskMapper;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEvaluateLogService;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppQualityLogServiceImpl;
import tech.qiantong.qdata.module.system.service.ISysMessageService;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityScoreReadTest {
 DppQualityLogMapper mapper;IDppEvaluateLogService reports;DppQualityLogServiceImpl service;
 @BeforeEach void setup(){mapper=mock(DppQualityLogMapper.class);reports=mock(IDppEvaluateLogService.class);service=new DppQualityLogServiceImpl(mapper,mock(DppQualityTaskMapper.class),reports,mock(ISysMessageService.class));}
 DppQualityLogDO row(){var r=new DppQualityLogDO();r.setId(1L);r.setQualityId("39");r.setScore(99L);return r;}
 @Test void detailAndListShareTheReportDerivedScoreIncludingZero(){
  when(reports.sumTotalAndProblemTotalByTaskLogId("1")).thenReturn(Map.of("total",3L,"problemTotal",3L));when(mapper.selectById(1L)).thenReturn(row());var page=new PageResult<DppQualityLogDO>(List.of(row()),1L);when(mapper.selectPage(any(DppQualityLogPageReqVO.class))).thenReturn(page);
  var detail=service.getDppQualityLogById(1L);var listed=service.getDppQualityLogPage(new DppQualityLogPageReqVO()).getRows().get(0);assertEquals(0L,detail.getScore());assertEquals(3L,detail.getProblemData());assertEquals(detail.getScore(),listed.getScore());verify(mapper,never()).updateById(any(DppQualityLogDO.class));
 }
 @Test void decimalJdbcAggregatesRetainTheExistingRoundingFormula(){when(reports.sumTotalAndProblemTotalByTaskLogId("1")).thenReturn(Map.of("total",new BigDecimal("7"),"problemTotal",new BigDecimal("1")));when(mapper.selectById(1L)).thenReturn(row());assertEquals(85L,service.getDppQualityLogById(1L).getScore());}
 @Test void missingReportsUseTheSameExistingZeroDefaultAsTheList(){when(reports.sumTotalAndProblemTotalByTaskLogId("1")).thenReturn(new HashMap<>());when(mapper.selectById(1L)).thenReturn(row());assertEquals(0L,service.getDppQualityLogById(1L).getScore());}
 @Test void missingLogDoesNotQueryAnotherReportsStatistics(){assertNull(service.getDppQualityLogById(9L));verifyNoInteractions(reports);}
 @Test void latestAssetLogUsesTheSameDerivedScore(){
  when(reports.sumTotalAndProblemTotalByTaskLogId("1")).thenReturn(Map.of("total",3L,"problemTotal",0L));var page=new Page<DppQualityLogDO>(1,1);page.setRecords(List.of(row()));when(mapper.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(page);var request=new DppQualityTaskAssetReqVO();request.setId(39L);assertEquals(100L,service.getDppQualityLogById(request).getScore());
 }
}
