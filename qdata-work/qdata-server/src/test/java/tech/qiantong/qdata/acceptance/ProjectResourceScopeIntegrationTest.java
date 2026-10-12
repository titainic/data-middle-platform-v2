package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.CheckErrorDataReqDTO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real retained MySQL metadata with two simulated subjects; does not mint login accounts or open Hive. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class ProjectResourceScopeIntegrationTest {
    @Test void referencedAssetAndColumnCannotBeRemovedOrRepointed(){
        var guard=new tech.qiantong.qdata.module.da.service.asset.AssetReferenceGuard(jdbc);
        var references=guard.references(113L);assertTrue(Boolean.TRUE.equals(references.get("referenced")));assertFalse(((List<?>)references.get("services")).isEmpty());
        assertThrows(IllegalArgumentException.class,()->guard.requireRemovable(List.of(113L)));
        Long column=jdbc.queryForObject("SELECT MIN(id) FROM da_asset_column WHERE asset_id=99 AND del_flag='0'",Long.class);assertThrows(IllegalArgumentException.class,()->guard.requireColumnsRemovable(List.of(column)));
        var changed=new tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetSaveReqVO();changed.setId(113L);changed.setDatabaseName("other_database");assertThrows(IllegalArgumentException.class,()->guard.requireStableIdentity(changed));
        changed.setDatabaseName(null);changed.setDescription("Metadata-only description");assertDoesNotThrow(()->guard.requireStableIdentity(changed));
    }
    @Test void controllerStopsForeignAssetBeforeTheDataService(){
        var service=mock(tech.qiantong.qdata.module.da.service.asset.IDaAssetService.class);var controller=new tech.qiantong.qdata.module.da.controller.admin.asset.DaAssetController();org.springframework.test.util.ReflectionTestUtils.setField(controller,"resourceAccess",resources);org.springframework.test.util.ReflectionTestUtils.setField(controller,"readAccess",new tech.qiantong.qdata.module.da.service.asset.AssetProjectReadAccess(jdbc,resources,mock(tech.qiantong.qdata.security.web.service.ProjectPermissionService.class)));org.springframework.test.util.ReflectionTestUtils.setField(controller,"daAssetService",service);
        try(var actor=mockStatic(SecurityUtils.class)){actor.when(SecurityUtils::getUserId).thenReturn(900002L);assertThrows(AccessDeniedException.class,()->controller.getInfo(100L));verifyNoInteractions(service);}
    }
    JdbcTemplate jdbc;DataSourceFactory factory;ProjectDataResourceAccess resources;QualityResourceAccess quality;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_RESOURCE_SCOPE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_resource_scope_"));
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD")));
        factory=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);var hive=HiveEndpointFixture.source(jdbc,9L);when(sources.getDaDatasourceById(9L)).thenReturn(hive);
        var namespaces=new HiveNamespaceBrowser(jdbc,sources,factory);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);
        resources=new ProjectDataResourceAccess(jdbc,provider);var projectPermissions=mock(tech.qiantong.qdata.security.web.service.ProjectPermissionService.class);when(projectPermissions.has(any(),anyString())).thenReturn(true);quality=new QualityResourceAccess(jdbc,resources,new tech.qiantong.qdata.module.dpp.service.qa.QualityProjectAccess(jdbc,resources,projectPermissions));
    }
    @AfterEach void noHiveConnection(){verifyNoInteractions(factory);}
    @Test void owningProjectCanReadItsFixedTaskAndReport(){try(var actor=mockStatic(SecurityUtils.class)){actor.when(SecurityUtils::getUserId).thenReturn(2L);assertTrue(resources.visibleAssetIds().contains(100L));assertTrue(resources.visibleDatasourceIds().contains(9L));assertTrue(quality.visibleTasks().containsAll(List.of(18L,30L)));assertDoesNotThrow(()->quality.log(563L));assertTrue(quality.visibleLogs().contains(563L));}}
    @Test void anotherProjectCannotReadTaskLogSnapshotOrSourceTable(){try(var actor=mockStatic(SecurityUtils.class)){actor.when(SecurityUtils::getUserId).thenReturn(900002L);assertFalse(resources.visibleAssetIds().contains(100L));assertFalse(resources.visibleDatasourceIds().contains(9L));assertFalse(quality.visibleTasks().contains(30L));assertThrows(AccessDeniedException.class,()->quality.log(563L));assertThrows(AccessDeniedException.class,()->resources.requireTable(9L,"dwd_iot_db.water_quality"));assertFalse(quality.visibleLogs().contains(563L));}}
    @Test void anUnassignedSubjectDoesNotGainAccessFromEmptyFilters(){try(var actor=mockStatic(SecurityUtils.class)){actor.when(SecurityUtils::getUserId).thenReturn(900003L);assertTrue(resources.visibleAssetIds().isEmpty());assertTrue(resources.visibleDatasourceIds().isEmpty());assertTrue(quality.visibleTasks().isEmpty());assertTrue(quality.visibleLogs().isEmpty());}}
    @Test void owningProjectCanMaintainDisabledConnectionWithoutExecutingIt(){
        try(var actor=mockStatic(SecurityUtils.class)){
            actor.when(SecurityUtils::getUserId).thenReturn(2L);
            assertEquals("0",jdbc.queryForObject("SELECT valid_flag FROM da_datasource WHERE id=12",String.class));
            assertTrue(resources.configurableDatasourceIds().contains(12L));
            assertDoesNotThrow(()->resources.requireDatasourceConfiguration(12L));
            assertFalse(resources.visibleDatasourceIds().contains(12L));
            assertThrows(AccessDeniedException.class,()->resources.requireTable(12L,"serving_iot_db.example"));
        }
    }
    @Test void foreignProjectCannotReadOrEnableDisabledConfiguration(){
        var service=mock(IDaDatasourceService.class);
        var controller=new tech.qiantong.qdata.module.da.controller.admin.datasource.DaDatasourceController(service,mock(tech.qiantong.qdata.module.da.service.asset.IDaAssetService.class),mock(HiveNamespaceBrowser.class));
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"resourceAccess",resources);
        try(var actor=mockStatic(SecurityUtils.class)){
            actor.when(SecurityUtils::getUserId).thenReturn(900002L);
            assertFalse(resources.configurableDatasourceIds().contains(12L));
            assertThrows(AccessDeniedException.class,()->controller.getInfo(12L));
            assertThrows(AccessDeniedException.class,()->controller.editDatasourceStatus(12L,1L));
            verifyNoInteractions(service);
        }
    }
    @Test void connectionStateFilterAppliesBeforePaginationInRealMysql(){
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.setEnvironment(new org.apache.ibatis.mapping.Environment("scope-probe",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),jdbc.getDataSource()));
        config.setMapUnderscoreToCamelCase(true);
        var pagination=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));
        config.addInterceptor(pagination);
        config.addMapper(tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper.class);
        var sessions=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);
        try(var session=sessions.openSession()){
            var mapper=session.getMapper(tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper.class);
            var request=new tech.qiantong.qdata.module.da.controller.admin.datasource.vo.DaDatasourcePageReqVO();
            request.setAccessibleDatasourceIds(List.of(9L,12L));request.setPageSize(1);request.setOrderByColumn("id");
            request.setValidFlag(true);var enabled=mapper.selectPage(request);
            assertEquals(1L,enabled.getTotal());assertEquals(9L,((DaDatasourceDO)enabled.getRows().get(0)).getId());
            request.setValidFlag(false);var disabled=mapper.selectPage(request);
            assertEquals(1L,disabled.getTotal());assertEquals(12L,((DaDatasourceDO)disabled.getRows().get(0)).getId());
            request.setValidFlag(null);assertEquals(2L,mapper.selectPage(request).getTotal());
            request.setAccessibleDatasourceIds(List.of());assertEquals(0L,mapper.selectPage(request).getTotal());
        }
    }
    @Test void issueIdentifiersCannotCrossReportBoundaries(){try(var actor=mockStatic(SecurityUtils.class)){actor.when(SecurityUtils::getUserId).thenReturn(2L);var issue=jdbc.queryForMap("SELECT e.id,e.report_id FROM quality_error_data e JOIN dpp_evaluate_log r ON r.id=e.report_id WHERE r.task_log_id=557 LIMIT 1");String another=jdbc.queryForObject("SELECT MIN(id) FROM dpp_evaluate_log WHERE task_log_id=556",String.class);var request=new CheckErrorDataReqDTO();request.setReportId(another);request.setId(String.valueOf(issue.get("id")));assertThrows(AccessDeniedException.class,()->quality.errorData(request));}}
    @Test void hiveRepairCannotBeRedirectedUsingClientDatasourceFields(){try(var actor=mockStatic(SecurityUtils.class)){actor.when(SecurityUtils::getUserId).thenReturn(2L);String report=jdbc.queryForObject("SELECT MIN(id) FROM dpp_evaluate_log WHERE task_log_id=556",String.class);var request=new CheckErrorDataReqDTO();request.setReportId(report);request.setUpdateType("1");request.setDatasourceId(12L);request.setTableName("unrelated_table");assertThrows(IllegalArgumentException.class,()->quality.errorData(request));}}
}
