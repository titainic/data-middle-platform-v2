package tech.qiantong.qdata.module.ds.annotation.handler;
import cn.dev33.satoken.oauth2.data.model.ClientTokenModel;
import cn.dev33.satoken.oauth2.template.SaOAuth2Util;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import tech.qiantong.qdata.module.ds.annotation.DsCheckClientToken;
import tech.qiantong.qdata.module.ds.handler.MappingHandlerMapping;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DsClientAuthorizationTest {
    public static class Endpoint {@DsCheckClientToken public void invoke(){}}
    @Test void invalidTokenReturns401WithoutGrantQuery() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var interceptor=new DsCheckClientTokenInterceptor();ReflectionTestUtils.setField(interceptor,"jdbc",jdbc);ReflectionTestUtils.setField(interceptor,"audit",mock(tech.qiantong.qdata.module.ds.handler.ServiceCallAudit.class));var request=new MockHttpServletRequest();request.addHeader("X-Client-Token","invalid-test-token");var response=new MockHttpServletResponse();
        try(var oauth=mockStatic(SaOAuth2Util.class)){oauth.when(()->SaOAuth2Util.checkClientToken("invalid-test-token")).thenThrow(new IllegalArgumentException());assertFalse(interceptor.preHandle(request,response,new HandlerMethod(new Endpoint(),"invoke")));assertEquals(401,response.getStatus());verifyNoInteractions(jdbc);}
    }
    @Test void issuedTokenStillRequiresCurrentGrant() throws Exception {checkGrant(0L,false,403);}
    @Test void validGrantPropagatesActualClientIdentity() throws Exception {checkGrant(1L,true,200);}
    private void checkGrant(long allowed,boolean expected,int status) throws Exception {
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(allowed);var interceptor=new DsCheckClientTokenInterceptor();ReflectionTestUtils.setField(interceptor,"jdbc",jdbc);ReflectionTestUtils.setField(interceptor,"audit",mock(tech.qiantong.qdata.module.ds.handler.ServiceCallAudit.class));var request=new MockHttpServletRequest();request.addHeader("X-Client-Token","test-token");var response=new MockHttpServletResponse();var token=mock(ClientTokenModel.class);when(token.getClientId()).thenReturn("12");var api=new DsApiDO();api.setId(5L);
        try(var oauth=mockStatic(SaOAuth2Util.class);var mapping=mockStatic(MappingHandlerMapping.class)){oauth.when(()->SaOAuth2Util.checkClientToken("test-token")).thenReturn(token);mapping.when(()->MappingHandlerMapping.getMappingApiInfo(request)).thenReturn(api);assertEquals(expected,interceptor.preHandle(request,response,new HandlerMethod(new Endpoint(),"invoke")));assertEquals(status,response.getStatus());if(expected)assertEquals("12",request.getAttribute("qdata.clientId"));else assertEquals("12",request.getAttribute("qdata.clientId"));}
    }
}
