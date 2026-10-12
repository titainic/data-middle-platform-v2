package tech.qiantong.qdata.server.controller;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OverviewControllerTest {
 @Test void noModulePermissionDoesNotQueryBusinessData(){
  var jdbc=mock(JdbcTemplate.class);
  try(var security=mockStatic(SecurityUtils.class)){
   security.when(SecurityUtils::getUserId).thenReturn(2L);security.when(SecurityUtils::getUsername).thenReturn("reader");
   var result=new OverviewController(jdbc,mock(tech.qiantong.qdata.module.da.api.service.DataResourceAccess.class),mock(tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess.class)).overview().getData();assertEquals("accessible",result.get("scope"));
   assertNull(((Map<?,?>)result.get("totals")).get("assets"));verifyNoInteractions(jdbc);
  }
 }
 @Test void modulePermissionOnlyExposesTheAuthorizedModule(){
  var jdbc=mock(JdbcTemplate.class);
  when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenAnswer(call->{
   String sql=call.getArgument(0);assertFalse(sql.contains("dpp_etl_task"));assertTrue(sql.contains("da_asset"));assertFalse(sql.contains("owner'--"));return 0L;
  });
  try(var security=mockStatic(SecurityUtils.class)){
   security.when(SecurityUtils::getUserId).thenReturn(2L);security.when(SecurityUtils::getUsername).thenReturn("owner'--");
   security.when(()->SecurityUtils.hasPermi(anyString())).thenAnswer(call->"da:asset:list".equals(call.getArgument(0)));
   var result=new OverviewController(jdbc,mock(tech.qiantong.qdata.module.da.api.service.DataResourceAccess.class),mock(tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess.class)).overview().getData();assertEquals(0L,((Map<?,?>)result.get("totals")).get("assets"));
   verify(jdbc,times(2)).queryForObject(anyString(),eq(Long.class),any(Object[].class));
  }
 }
 @Test void registeredDevelopmentMenuPermissionEnablesItsStatistics(){
  var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
  try(var security=mockStatic(SecurityUtils.class)){
   security.when(SecurityUtils::getUserId).thenReturn(2L);security.when(SecurityUtils::getUsername).thenReturn("developer");
   security.when(()->SecurityUtils.hasPermi(anyString())).thenAnswer(call->"dpp:developTask:list".equals(call.getArgument(0)));
   var totals=(Map<?,?>)new OverviewController(jdbc,mock(tech.qiantong.qdata.module.da.api.service.DataResourceAccess.class),mock(tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess.class)).overview().getData().get("totals");assertEquals(0L,totals.get("development"));assertNull(totals.get("integration"));
  }
 }

}
