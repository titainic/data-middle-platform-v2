package tech.qiantong.qdata.module.dp.warehouse;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.dp.api.warehouse.ServingRequestException;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WarehouseServingQueryTest {
    @Test void responseAndCacheRetainNullFieldsDespiteGlobalNullOmission()throws Exception {
        var row=new WarehouseServingQuery.ServiceRow();row.put("avg_ph",null);row.put("location_code","A");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        assertTrue(mapper.writeValueAsString(new WarehouseServingQuery.PublishedPage(1,List.of(row),release,1)).contains("\"avg_ph\":null"));
        var cached=JSON.parseObject(JSON.toJSONString(row,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));assertTrue(cached.containsKey("avg_ph"));assertTrue(mapper.writeValueAsString(new WarehouseServingQuery.ServiceRow(cached)).contains("\"avg_ph\":null"));
    }
    JdbcTemplate jdbc;IRedisService redis;DataSourceFactory factory;WarehouseServingQuery query;WarehouseServingService serving;
    final String release="7f985378-fd3b-448f-b7da-bd20a8cc7321";
    @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);redis=mock(IRedisService.class);factory=mock(DataSourceFactory.class);serving=mock(WarehouseServingService.class);query=new WarehouseServingQuery(jdbc,serving,mock(WarehouseSpaceService.class),factory,redis);var column=new WarehouseDefinition.Column();column.setName("location_code");column.setType("STRING");when(jdbc.queryForMap(startsWith("SELECT * FROM dp_warehouse_serving_product"),anyLong())).thenReturn(Map.of("space_id",1L,"dataset_id",17L,"contract_version",1,"schema_json",JSON.toJSONString(List.of(column))));when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(1L);when(redis.allowRequest(anyString(),eq(100L),eq(60L))).thenReturn(true);when(jdbc.queryForList(startsWith("SELECT locations_json"),eq(String.class),anyString(),anyLong())).thenReturn(List.of());}
    @Test void revokedGrantIsCheckedBeforeTheCache(){when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM att_client"),eq(Long.class),any(Object[].class))).thenReturn(0L);var error=assertThrows(ServingRequestException.class,()->query.query(1,"2",Map.of()));assertEquals(403,error.getStatus());verifyNoInteractions(redis,factory);}
    @Test void throttlesBeforeCacheOrDatabase(){when(redis.allowRequest(anyString(),eq(100L),eq(60L))).thenReturn(false);var error=assertThrows(ServingRequestException.class,()->query.query(1,"2",Map.of()));assertEquals(429,error.getStatus());verify(redis,never()).get(anyString());verifyNoInteractions(factory);}
    @Test void laterPagesRequireExplicitVersion(){var error=assertThrows(ServingRequestException.class,()->query.query(1,"2",Map.of("pageNum",2)));assertEquals(400,error.getStatus());verify(redis,never()).get(anyString());}
    @Test void rejectsUnknownParametersAndOversizedPages(){for(var parameters:List.of(Map.<String,Object>of("sql","SELECT *"),Map.<String,Object>of("pageSize",201),Map.<String,Object>of("pageSize","99999999999999999999"))){assertEquals(400,assertThrows(ServingRequestException.class,()->query.query(1,"2",parameters)).getStatus());}verifyNoInteractions(factory);}
    @Test void cachedPageRetainsPublishedVersionAndData(){when(redis.get(anyString())).thenReturn("{\"total\":1,\"data\":[{\"location_code\":\"A\"}]}");var page=(WarehouseServingQuery.PublishedPage)query.query(1,"2",Map.of("releaseId",release));assertEquals(release,page.getReleaseId());assertEquals("A",page.getData().get(0).get("location_code"));verifyNoInteractions(factory);}
    @Test void fieldScopeFiltersColumnsAndRejectsAnEmptyProjection(){var a=new WarehouseDefinition.Column();a.setName("device_id");var b=new WarehouseDefinition.Column();b.setName("location_code");assertEquals(List.of(b),WarehouseServingQuery.selectColumns(List.of(a,b),List.of("location_code")));assertEquals(403,assertThrows(ServingRequestException.class,()->WarehouseServingQuery.selectColumns(List.of(a),List.of("location_code"))).getStatus());}
    @Test void changedScopeCannotReuseAnotherScopeCacheKey(){when(redis.get(anyString())).thenReturn("{\"total\":0,\"data\":[]}");query.query(1,"2",Map.of("releaseId",release));when(jdbc.queryForList(startsWith("SELECT locations_json,columns_json"),eq("2"),eq(1L))).thenReturn(List.of(Map.of("locations_json","[\"A\"]","columns_json","[]","masked_columns_json","[]")));query.query(1,"2",Map.of("releaseId",release));var keys=org.mockito.ArgumentCaptor.forClass(String.class);verify(redis,times(2)).get(keys.capture());assertNotEquals(keys.getAllValues().get(0),keys.getAllValues().get(1));}
    private void dependency() {
        when(serving.target(1L)).thenReturn(Map.of("datasource_id",12L,"database_name","serving_test"));
        when(serving.property(12L,15000)).thenReturn(new tech.qiantong.qdata.common.database.constants.DbQueryProperty("Doris","localhost",9030L,"{\"username\":\"test\",\"password\":\"fixture-only\",\"dbname\":\"serving_test\"}"));
        var product=new java.util.HashMap<>(jdbc.queryForMap("SELECT * FROM dp_warehouse_serving_product WHERE api_id=?",1L));
        product.put("table_name","readings");when(jdbc.queryForMap(startsWith("SELECT * FROM dp_warehouse_serving_product"),anyLong())).thenReturn(product);
    }
    @Test void failedConnectionFactoryOpensCircuitAndCachedAuthorizedDataStillWorks() {
        dependency();when(factory.createDbQuery(any())).thenThrow(new IllegalStateException("secret must not leak"));
        for(int i=0;i<5;i++){var error=assertThrows(ServingRequestException.class,()->query.query(1,"2",Map.of("releaseId",release)));assertEquals(503,error.getStatus());assertFalse(error.getMessage().contains("secret"));}
        assertTrue(assertThrows(ServingRequestException.class,()->query.query(1,"2",Map.of("releaseId",release))).getMessage().contains("依赖"));
        verify(factory,times(5)).createDbQuery(any());
        when(redis.get(anyString())).thenReturn("{\"total\":0,\"data\":[]}");
        assertEquals(0,((WarehouseServingQuery.PublishedPage)query.query(1,"2",Map.of("releaseId",release))).getTotal());
    }
    @Test void failedConnectionIsClosedAndPermitReleased() {
        dependency();var db=mock(tech.qiantong.qdata.common.database.DbQuery.class);when(factory.createDbQuery(any())).thenReturn(db);when(db.getConnection()).thenThrow(new IllegalStateException("connection refused"));
        for(int i=0;i<5;i++)assertEquals(503,assertThrows(ServingRequestException.class,()->query.query(1,"2",Map.of("releaseId",release))).getStatus());
        verify(db,times(5)).close();
    }
    @Test void redisFailureFailsClosedWithoutAttemptingServingDatabase() {
        when(redis.allowRequest(anyString(),eq(100L),eq(60L))).thenThrow(new IllegalStateException("redis unavailable"));
        assertEquals(503,assertThrows(ServingRequestException.class,()->query.query(1,"2",Map.of("releaseId",release))).getStatus());verifyNoInteractions(factory);
    }
}
