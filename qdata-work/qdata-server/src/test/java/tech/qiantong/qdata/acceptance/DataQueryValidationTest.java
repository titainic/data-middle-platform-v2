package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.database.*;
import tech.qiantong.qdata.common.database.utils.AesEncryptUtil;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourcePageReqVO;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.service.datasource.HiveNamespaceBrowser;
import tech.qiantong.qdata.module.da.service.datasource.impl.DaDatasourceServiceImpl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataQueryValidationTest {
    DaDatasourceServiceImpl service=spy(new DaDatasourceServiceImpl());
    DaDatasourceDO source=new DaDatasourceDO();DataSourceFactory factory=mock(DataSourceFactory.class);DbQuery query=mock(DbQuery.class);
    @BeforeEach void setup(){
        source.setId(9L);source.setDatasourceType("Hive");source.setIp("fixture.invalid");source.setPort(10000L);source.setDatasourceConfig("{\"dbname\":\"fixture\",\"username\":\"fixture\"}");
        doReturn(source).when(service).getDaDatasourceById(9L);
        ReflectionTestUtils.setField(service,"dataSourceFactory",factory);when(factory.createDbQuery(any())).thenReturn(query);when(query.valid()).thenReturn(true);
        ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(mock(HiveNamespaceBrowser.class));ReflectionTestUtils.setField(service,"hiveNamespaces",provider);
    }
    String validate(String sql)throws Exception{return ReflectionTestUtils.invokeMethod(service,"decryptSqlText",AesEncryptUtil.encrypt(sql),9L);}
    @Test void hiveWithQueryAndTrailingDelimiterRemainReadOnly()throws Exception{
        String sql="WITH x AS (SELECT 1 AS value) SELECT value FROM x";assertEquals(sql,validate(sql+";"));verifyNoInteractions(factory);
    }
    @Test void commentsAndKeywordsInsideLiteralsAreNotMistakenForWrites()throws Exception{
        String sql="/* read-only example */ SELECT 'drop; update' AS text_value";assertEquals(sql,validate(sql));verifyNoInteractions(factory);
    }
    @Test void scriptsAndWithInsertCannotOpenAConnection(){
        for(String sql:java.util.List.of("SELECT 1; SELECT 2","WITH x AS (SELECT 1 AS id) INSERT INTO fixture_table SELECT id FROM x","DROP TABLE fixture_table"))assertThrows(RuntimeException.class,()->validate(sql));verifyNoInteractions(factory);
    }
    @Test void mysqlFilesAndUpdateLocksAreRejected(){
        source.setDatasourceType("MySql");for(String sql:java.util.List.of("SELECT 1 INTO OUTFILE '/tmp/never-written'","SELECT * FROM fixture_table FOR UPDATE"))assertThrows(RuntimeException.class,()->validate(sql));verifyNoInteractions(factory);
    }
    @Test void postgresNestedWriteIsRejectedUsingTheRegisteredDialect(){
        source.setDatasourceType("PostgreSql");assertThrows(RuntimeException.class,()->validate("WITH x AS (DELETE FROM fixture_table RETURNING id) SELECT * FROM x"));verifyNoInteractions(factory);
    }
    @Test void exportQueryFailureStillClosesItsSource()throws Exception{
        var request=new DaDatasourcePageReqVO();request.setId(9L);request.setSqlText(AesEncryptUtil.encrypt("SELECT 1 AS value"));
        when(query.queryByPage(anyString(),anyLong(),anyLong())).thenThrow(new IllegalStateException("synthetic query failure"));
        assertThrows(IllegalStateException.class,()->service.exportSqlQueryResult(new MockHttpServletResponse(),request));verify(query).close();
    }
    @Test void invalidSourceIsClosedBeforeItCanBeReturned()throws Exception{
        when(query.valid()).thenReturn(false);var request=new DaDatasourcePageReqVO();request.setId(9L);request.setSqlText(AesEncryptUtil.encrypt("SELECT 1 AS value"));
        assertThrows(RuntimeException.class,()->service.executeSqlQuery(request));verify(query).close();verify(query,never()).queryByPage(anyString(),anyLong(),anyLong());
    }
    @Test void actualWorkbookPreservesWideNumbersAndBlankNulls()throws Exception{
        var row=new java.util.LinkedHashMap<String,Object>();row.put("wide",new java.math.BigDecimal("12345678901234567890.123456789"));row.put("large",9007199254740993L);row.put("empty",null);
        when(query.queryByPage(anyString(),anyLong(),anyLong())).thenReturn(new tech.qiantong.qdata.common.database.core.PageResult<>(1,java.util.List.of(row)));
        var request=new DaDatasourcePageReqVO();request.setId(9L);request.setSqlText(AesEncryptUtil.encrypt("SELECT 1 AS value"));var response=new MockHttpServletResponse();service.exportSqlQueryResult(response,request);
        try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))){var data=workbook.getSheetAt(0).getRow(1);assertEquals("12345678901234567890.123456789",data.getCell(0).getStringCellValue());assertEquals("9007199254740993",data.getCell(1).getStringCellValue());assertEquals("",data.getCell(2).getStringCellValue());}
        verify(query).close();
    }
}
