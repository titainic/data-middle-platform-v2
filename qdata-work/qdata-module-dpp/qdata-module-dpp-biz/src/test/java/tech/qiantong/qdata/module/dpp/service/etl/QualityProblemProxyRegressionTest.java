package tech.qiantong.qdata.module.dpp.service.etl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.httpClient.HttpUtils;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.CheckErrorDataReqDTO;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEvaluateLogServiceImpl;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class QualityProblemProxyRegressionTest {
    DppEvaluateLogServiceImpl service(){var s=new DppEvaluateLogServiceImpl();ReflectionTestUtils.setField(s,"url","http://quality.test/quality/qualityTaskExecutor");return s;}
    @Test void authenticatedOperatorReplacesClientSuppliedName() throws Exception {
        try(var http=mockStatic(HttpUtils.class);var security=mockStatic(SecurityUtils.class)){
            security.when(SecurityUtils::getUsername).thenReturn("actual-user");
            var response=mock(HttpUtils.ResponseObject.class);when(response.getBody()).thenReturn("{\"code\":200,\"data\":true}");
            http.when(()->HttpUtils.sendPost(anyString(),anyMap(),anyList())).thenAnswer(inv->{Map<?,?> body=inv.getArgument(1);assertEquals("actual-user",body.get("operator"));return response;});
            var dto=new CheckErrorDataReqDTO();dto.setOperator("spoofed");dto.setReportId("1");dto.setUpdateType("3");assertTrue(service().updateErrorData(dto));
        }
    }
    @Test void backendRejectionIsNotReportedAsSuccess() throws Exception {
        try(var http=mockStatic(HttpUtils.class);var security=mockStatic(SecurityUtils.class)){
            security.when(SecurityUtils::getUsername).thenReturn("actual-user");var response=mock(HttpUtils.ResponseObject.class);
            when(response.getBody()).thenReturn("{\"code\":500,\"msg\":\"状态已变化\"}");http.when(()->HttpUtils.sendPost(anyString(),anyMap(),anyList())).thenReturn(response);
            var error=assertThrows(ServiceException.class,()->service().updateErrorData(new CheckErrorDataReqDTO()));assertEquals("状态已变化",error.getMessage());
        }
    }
    @Test void historyContractRetainsRowsAndOperator() throws Exception {
        try(var http=mockStatic(HttpUtils.class)){
            var response=mock(HttpUtils.ResponseObject.class);when(response.getBody()).thenReturn("{\"code\":200,\"data\":[{\"action\":\"REVOKE_IGNORE\",\"operator\":\"actual-user\"}]}");http.when(()->HttpUtils.sendPost(anyString(),anyMap(),anyList())).thenReturn(response);
            var rows=service().errorDataHistory(new CheckErrorDataReqDTO());assertEquals(1,rows.size());assertEquals("actual-user",rows.get(0).get("operator"));
        }
    }
}
