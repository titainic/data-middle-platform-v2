package tech.qiantong.qdata.module.ds.handler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tech.qiantong.qdata.module.ds.async.AsyncTask;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import tech.qiantong.qdata.module.ds.dal.dataobject.apiLog.DsApiLogDO;
import tech.qiantong.qdata.module.dp.api.warehouse.PublishedPageMetadata;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceCallAuditTest {
    @Test void anonymousDenialHasTheRequiredAuditIdentity(){var sink=mock(AsyncTask.class);org.springframework.beans.factory.ObjectProvider<AsyncTask> provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(sink);var api=new DsApiDO();api.setId(14L);api.setCatId(1L);new ServiceCallAudit(provider).denied(new MockHttpServletRequest("GET","/services/test"),api,401,"Invalid token");var record=org.mockito.ArgumentCaptor.forClass(DsApiLogDO.class);verify(sink).doTask(record.capture());assertEquals("anonymous",record.getValue().getCallerId());assertNotNull(record.getValue().getCallerStartDate());}
    @Test void recordsEffectiveVersionWithoutCredentialsOrResultRows(){var page=mock(PublishedPageMetadata.class);when(page.getReleaseId()).thenReturn("published-version");when(page.getContractVersion()).thenReturn(2);String audit=ServiceCallAudit.parameters(Map.of("pageNum",2,"client_token","private-test-token","client_secret","private-test-secret","locationCode","A"),page);assertTrue(audit.contains("published-version"));assertTrue(audit.contains("contractVersion"));assertFalse(audit.contains("private-test"));assertFalse(audit.contains("client_token"));}
    @Test void authorizationFailureProducesAnAuditRecord(){var sink=mock(AsyncTask.class);org.springframework.beans.factory.ObjectProvider<AsyncTask> provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(sink);var service=new ServiceCallAudit(provider);verifyNoInteractions(provider);var request=new MockHttpServletRequest("GET","/services/v1.0.0/iot/report");request.setAttribute("qdata.clientId","11");request.addParameter("client_token","private-test-token");request.addParameter("pageNum","1");var api=new DsApiDO();api.setId(14L);service.denied(request,api,403,"Grant expired");var record=org.mockito.ArgumentCaptor.forClass(DsApiLogDO.class);verify(sink).doTask(record.capture());assertEquals(0,record.getValue().getStatus());assertEquals("11",record.getValue().getCallerId());assertTrue(record.getValue().getMsg().contains("403"));assertFalse(record.getValue().getCallerParams().contains("private-test-token"));}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={200,404,503})
    void forwardedHttpStatusIsReflectedInAudit(int status)throws Exception {
        var sink=mock(AsyncTask.class);var engine=mock(tech.qiantong.qdata.module.ds.service.api.impl.ApiMappingEngine.class);
        var handler=new RequestHandler();handler.setApiMappingEngine(engine);handler.setRequestInterceptor(mock(RequestInterceptor.class));handler.setObjectMapper(new com.fasterxml.jackson.databind.ObjectMapper());org.springframework.test.util.ReflectionTestUtils.setField(handler,"asyncTask",sink);
        var request=new MockHttpServletRequest("GET","/services/fixture");request.setAttribute("qdata.clientId","16");var response=new org.springframework.mock.web.MockHttpServletResponse();var api=new DsApiDO();api.setId(100L);api.setApiServiceType("3");
        doAnswer(call->{((jakarta.servlet.http.HttpServletResponse)call.getArgument(2)).setStatus(status);return null;}).when(engine).executeServiceForwarding(any(),anyMap(),any());
        try(var mappings=mockStatic(MappingHandlerMapping.class)){mappings.when(()->MappingHandlerMapping.getMappingApiInfo(request)).thenReturn(api);handler.invoke(request,response,Map.of(),Map.of(),Map.of());}
        var audit=org.mockito.ArgumentCaptor.forClass(DsApiLogDO.class);verify(sink).doTask(audit.capture());assertEquals(status>=400?0:1,audit.getValue().getStatus());assertEquals(status>=400?0:1,audit.getValue().getCallerSize());if(status>=400)assertTrue(audit.getValue().getMsg().contains(String.valueOf(status)));
    }
    @Test void boundedForwardingFailureReturnsHttp503AndFailureAudit()throws Exception {
        var sink=mock(AsyncTask.class);var engine=mock(tech.qiantong.qdata.module.ds.service.api.impl.ApiMappingEngine.class);var handler=new RequestHandler();handler.setApiMappingEngine(engine);handler.setRequestInterceptor(mock(RequestInterceptor.class));handler.setObjectMapper(new com.fasterxml.jackson.databind.ObjectMapper());org.springframework.test.util.ReflectionTestUtils.setField(handler,"asyncTask",sink);
        var request=new MockHttpServletRequest("GET","/services/fixture");request.setAttribute("qdata.clientId","16");var response=new org.springframework.mock.web.MockHttpServletResponse();var api=new DsApiDO();api.setId(100L);api.setApiServiceType("3");
        doThrow(new tech.qiantong.qdata.common.exception.DependencyUnavailableException("服务依赖响应超时，请稍后重试")).when(engine).executeServiceForwarding(any(),anyMap(),any());
        Object result;try(var mappings=mockStatic(MappingHandlerMapping.class)){mappings.when(()->MappingHandlerMapping.getMappingApiInfo(request)).thenReturn(api);result=handler.invoke(request,response,Map.of(),Map.of(),Map.of());}
        assertEquals(503,response.getStatus());assertEquals(503,((tech.qiantong.qdata.common.core.domain.AjaxResult)result).get("code"));var audit=org.mockito.ArgumentCaptor.forClass(DsApiLogDO.class);verify(sink).doTask(audit.capture());assertEquals(0,audit.getValue().getStatus());assertEquals("16",audit.getValue().getCallerId());
    }
}
