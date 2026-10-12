package tech.qiantong.qdata.acceptance;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.dp.service.model.impl.DpModelServiceImpl;
import tech.qiantong.qdata.module.dp.dal.mapper.model.DpModelMapper;
import tech.qiantong.qdata.module.dp.api.model.dto.DpModelRespDTO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class LegacyWarehouseLookupIntegrationTest {
    SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;org.apache.ibatis.session.SqlSession session;DpModelServiceImpl service;
    static final long ODS=991001,DWD=991002;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_FILE_LIFECYCLE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_file_lifecycle_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=manager.getTransaction(new DefaultTransactionDefinition());
        for(var row:List.of(Map.entry(ODS,"ods_iot_db"),Map.entry(DWD,"dwd_iot_db")))jdbc.update("INSERT INTO dp_model(id,datasource_id,model_name,model_comment,database_name,warehouse_config,del_flag) VALUES(?,9,'legacy_lookup_water','synthetic identity fixture',?,'{}','0')",row.getKey(),row.getValue());
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("legacy-lookup",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());config.addMapper(DpModelMapper.class);session=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config).openSession();service=spy(new DpModelServiceImpl());ReflectionTestUtils.setField(service,"dpModelMapper",session.getMapper(DpModelMapper.class));
        for(long id:new long[]{ODS,DWD}){var response=new DpModelRespDTO();response.setId(id);doReturn(response).when(service).getDpModelByIdApi(id);}
    }
    @AfterEach void close(){session.close();manager.rollback(tx);source.destroy();}
    @Test void legacyShortNameRejectsAmbiguityWithoutSelectingFirstModel(){var failure=assertThrows(IllegalStateException.class,()->service.getWarehouseModelByTableApi(9L,"legacy_lookup_water"));assertTrue(failure.getMessage().contains("请提供数据库名"));verify(service,never()).getDpModelByIdApi(anyLong());}
    @Test void qualifiedLookupSelectsTheCorrectDatabase(){assertEquals(ODS,service.getWarehouseModelByTableApi(9L,"ods_iot_db","legacy_lookup_water").getId());assertEquals(DWD,service.getWarehouseModelByTableApi(9L,"dwd_iot_db","legacy_lookup_water").getId());}
    @Test void unknownDatabaseDoesNotFallBackToAnother(){assertNull(service.getWarehouseModelByTableApi(9L,"ads_iot_db","legacy_lookup_water"));verify(service,never()).getDpModelByIdApi(anyLong());}
    @Test void unknownConnectionDoesNotFallBackToKnownSource(){assertNull(service.getWarehouseModelByTableApi(99999L,"legacy_lookup_water"));verify(service,never()).getDpModelByIdApi(anyLong());}
    @Test void aSingleActiveLegacyMatchRemainsCompatible(){jdbc.update("UPDATE dp_model SET del_flag='1' WHERE id=?",DWD);assertEquals(ODS,service.getWarehouseModelByTableApi(9L,"legacy_lookup_water").getId());}
}
