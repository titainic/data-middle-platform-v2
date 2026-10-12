package tech.qiantong.qdata.module.ds.service.api.impl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.da.api.service.assetchild.api.IDaApiOutService;
import tech.qiantong.qdata.module.da.api.service.assetchild.gis.IDaAssetGisOutService;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class ServiceForwardingRoutingTest {
    ApiMappingEngine engine;IDaApiOutService api;IDaAssetGisOutService gis;MockHttpServletResponse response;
    @BeforeEach void setup(){engine=new ApiMappingEngine();api=mock(IDaApiOutService.class);gis=mock(IDaAssetGisOutService.class);response=new MockHttpServletResponse();ReflectionTestUtils.setField(engine,"iDaApiOutService",api);ReflectionTestUtils.setField(engine,"iDaAssetGisOutService",gis);}
    DsApiDO definition(String type){var d=new DsApiDO();d.setTransmitType(type);d.setApiId("12");return d;}
    @Test void gisForwardingReachesGisTransport(){engine.executeServiceForwarding(definition("2"),Map.of("layer","water"),response);verify(gis).executeServiceForwarding(eq(response),eq(12L),anyMap());verifyNoInteractions(api);}
    @Test void apiForwardingReachesApiTransport(){engine.executeServiceForwarding(definition("1"),Map.of("sensor","water"),response);verify(api).executeServiceForwarding(eq(response),eq(12L),anyMap());verifyNoInteractions(gis);}
    @ParameterizedTest @ValueSource(strings={"","0","3"}) void unsupportedTypeFailsExplicitly(String type){assertThrows(IllegalArgumentException.class,()->engine.executeServiceForwarding(definition(type),Map.of(),response));verifyNoInteractions(api,gis);}
}
