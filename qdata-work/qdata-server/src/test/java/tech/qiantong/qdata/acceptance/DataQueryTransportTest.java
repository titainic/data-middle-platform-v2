package tech.qiantong.qdata.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.datasource.DaDatasourceController;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourcePageReqVO;
import tech.qiantong.qdata.module.da.service.asset.IDaAssetService;
import tech.qiantong.qdata.module.da.service.datasource.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Transport and authorization delegation, with actual MVC body/form binding. No source connection. */
class DataQueryTransportTest {
    IDaDatasourceService service=mock(IDaDatasourceService.class);
    DatasourceProjectAccess projects=mock(DatasourceProjectAccess.class);
    DataResourceAccess resources=mock(DataResourceAccess.class);
    MockMvc mvc;
    @BeforeEach void setup(){
        var controller=new DaDatasourceController(service,mock(IDaAssetService.class),mock(HiveNamespaceBrowser.class));
        ReflectionTestUtils.setField(controller,"projectAccess",projects);ReflectionTestUtils.setField(controller,"resourceAccess",resources);
        mvc=MockMvcBuilders.standaloneSetup(controller).build();
    }
    @Test void longEncodedQueryTravelsInJsonBodyAndRetainsPageAndScope() throws Exception {
        String encoded="e".repeat(24000);String body=new ObjectMapper().writeValueAsString(Map.of("id",9,"sqlText",encoded,"pageNum",2,"pageSize",20));
        var response=mvc.perform(post("/da/dataSource/executeSqlQuery").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertEquals(200,response.getResponse().getStatus());assertNull(response.getRequest().getQueryString());
        var request=ArgumentCaptor.forClass(DaDatasourcePageReqVO.class);var order=inOrder(projects,resources,service);
        order.verify(projects).encodedQueryScope(9L,encoded,"da:dataQuery:list");order.verify(resources).requireDatasource(9L);order.verify(service).executeSqlQuery(request.capture());
        assertEquals(encoded,request.getValue().getSqlText());assertEquals(2,request.getValue().getPageNum());assertEquals(20,request.getValue().getPageSize());
    }
    @Test void legacyGetQueryRetainsItsExistingScopeChecks() throws Exception {
        var response=mvc.perform(get("/da/dataSource/executeSqlQuery").param("id","9").param("sqlText","encoded").param("pageNum","1").param("pageSize","20")).andReturn();
        assertEquals(200,response.getResponse().getStatus());verify(projects).encodedQueryScope(9L,"encoded","da:dataQuery:list");verify(resources).requireDatasource(9L);verify(service).executeSqlQuery(any());
    }
    @Test void longFormExportUsesSameScopeAndPreservesRequestedPage() throws Exception {
        String encoded="f".repeat(24000);
        var response=mvc.perform(post("/da/dataSource/exportSqlQueryResult/export").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("id","9").param("sqlText",encoded).param("pageNum","2").param("pageSize","5000")).andReturn();
        assertEquals(200,response.getResponse().getStatus());var request=ArgumentCaptor.forClass(DaDatasourcePageReqVO.class);var order=inOrder(projects,resources,service);
        order.verify(projects).encodedQueryScope(9L,encoded,"da:dataQuery:list");order.verify(resources).requireDatasource(9L);order.verify(service).exportSqlQueryResult(any(),request.capture());
        assertEquals(encoded,request.getValue().getSqlText());assertEquals(2,request.getValue().getPageNum());assertEquals(5000,request.getValue().getPageSize());
    }
    @Test void legacyGetExportRemainsCompatible() throws Exception {
        var response=mvc.perform(get("/da/dataSource/exportSqlQueryResult/export").param("id","9").param("sqlText","encoded")).andReturn();
        assertEquals(200,response.getResponse().getStatus());verify(projects).encodedQueryScope(9L,"encoded","da:dataQuery:list");verify(resources).requireDatasource(9L);verify(service).exportSqlQueryResult(any(),any());
    }
    @Test void workbenchUsesExactTextOnlyForBrowserUnsafeValues() throws Exception {
        var row=new java.util.LinkedHashMap<String,Object>();row.put("wide",new java.math.BigDecimal("12345678901234567890.123456789"));row.put("large",9007199254740993L);row.put("ordinary",new java.math.BigDecimal("7.120"));row.put("empty",null);
        when(service.executeSqlQuery(any())).thenReturn(new tech.qiantong.qdata.common.database.core.PageResult<>(1,java.util.List.of(row)));
        String body=new ObjectMapper().writeValueAsString(Map.of("id",9,"sqlText","encoded","pageNum",1,"pageSize",20));
        var response=mvc.perform(post("/da/dataSource/executeSqlQuery").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();assertEquals(200,response.getStatus());
        var data=new ObjectMapper().readTree(response.getContentAsString()).path("data").path("data").get(0);assertTrue(data.path("wide").isTextual());assertEquals("12345678901234567890.123456789",data.path("wide").asText());assertEquals("9007199254740993",data.path("large").asText());assertTrue(data.path("ordinary").isNumber());assertTrue(data.path("empty").isNull());
        assertInstanceOf(java.math.BigDecimal.class,row.get("wide"));
    }
    @Test void rawServiceRowsKeepNumericJsonWithoutAGlobalStringSerializer() throws Exception {
        String json=new ObjectMapper().writeValueAsString(Map.of("value",new java.math.BigDecimal("12345678901234567890.123456789")));
        assertEquals("{\"value\":12345678901234567890.123456789}",json);
    }
}
