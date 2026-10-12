package tech.qiantong.qdata.module.ds.handler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import tech.qiantong.qdata.module.dp.api.warehouse.ServingRequestException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServiceParameterBoundaryTest {
    private DsApiDO definition(){var api=new DsApiDO();api.setResParams("[]");api.setReqParams("[{\"paramName\":\"pageNum\",\"paramType\":\"2\"}]");return api;}
    @Test void invalidNumericTypeIsAClientError(){var error=assertThrows(ServingRequestException.class,()->new RequestInterceptor(null).preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),definition(),new HashMap<>(Map.of("pageNum","x"))));assertEquals(400,error.getStatus());assertTrue(error.getMessage().contains("pageNum"));}
    @Test void validIntegerContinuesToQueryValidation(){assertDoesNotThrow(()->new RequestInterceptor(null).preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),definition(),new HashMap<>(Map.of("pageNum","2")))) ;}
}
