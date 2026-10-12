package tech.qiantong.qdata.module.ds.service.api.impl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.module.ds.service.api.IDsApiService;
import tech.qiantong.qdata.module.ds.handler.MappingHandlerMapping;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseApiRouteTest {
    @Test void documentationUsesActualColumnTypesAndOnlySupportedFilters(){
        var api=new DsApiDO();WarehouseApiRegistrarImpl.describe(api,java.util.List.of(java.util.Map.of("name","avg_ph","type","DECIMAL(10,3)"),java.util.Map.of("name","event_date","type","DATE"),java.util.Map.of("name","location_code","type","STRING")));
        assertTrue(api.getResParams().contains("DECIMAL(10,3)"));assertTrue(api.getReqParams().contains("dateFrom"));assertTrue(api.getReqParams().contains("locationCode"));assertFalse(api.getReqParams().contains("deviceId"));
    }
    @Test void publishingNewDataKeepsAnExistingRouteInstalled(){
        var services=mock(IDsApiService.class);ObjectProvider<IDsApiService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(services);var mapping=mock(MappingHandlerMapping.class);var jdbc=mock(JdbcTemplate.class);
        var api=new DsApiDO();api.setId(14L);api.setApiVersion("v1.0.0");api.setApiUrl("iot/1/report");api.setReqMethod("1");when(services.getDsApiById(14L)).thenReturn(api);
        try(var registry=mockStatic(MappingHandlerMapping.class,CALLS_REAL_METHODS)){registry.when(()->MappingHandlerMapping.getMappingApiInfo("get:/services/v1.0.0/iot/1/report")).thenReturn(api);new WarehouseApiRegistrarImpl(provider,jdbc,mapping).activate(14L);verifyNoInteractions(mapping);verify(jdbc).update("UPDATE ds_api SET status='1' WHERE id=? AND del_flag='0'",14L);}
    }
    @Test void anExistingDifferentApiCannotBeDisplaced(){
        var services=mock(IDsApiService.class);ObjectProvider<IDsApiService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(services);var mapping=mock(MappingHandlerMapping.class);var jdbc=mock(JdbcTemplate.class);
        var api=new DsApiDO();api.setId(14L);api.setApiVersion("v1.0.0");api.setApiUrl("/iot/1/report");api.setReqMethod("1");when(services.getDsApiById(14L)).thenReturn(api);var other=new DsApiDO();other.setId(15L);
        try(var registry=mockStatic(MappingHandlerMapping.class,CALLS_REAL_METHODS)){registry.when(()->MappingHandlerMapping.getMappingApiInfo("get:/services/v1.0.0/iot/1/report")).thenReturn(other);assertThrows(IllegalArgumentException.class,()->new WarehouseApiRegistrarImpl(provider,jdbc,mapping).activate(14L));verifyNoInteractions(mapping,jdbc);}
    }
}
