package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.module.da.service.asset.ProjectDataResourceAccess;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.*;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.*;
import tech.qiantong.qdata.module.mc.service.metadata.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class MetadataProjectCapabilityIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    MetadataResourceAccess access;ProjectDataResourceAccess data;DataSourceFactory transport;IMcDbService databases;IMcTableService tables;IMcColumnService columns;
    McDbController dbController;McTableController tableController;McColumnController columnController;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_METADATA_PROJECT_URL");assertNotNull(url);assertTrue(url.contains("/qdata_metadata_project_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        transport=mock(DataSourceFactory.class);var sourceService=mock(IDaDatasourceService.class);for(long id:new long[]{9,12,13,16,17,18,19})when(sourceService.getDaDatasourceById(id)).thenReturn(HiveEndpointFixture.source(jdbc,id));
        var namespaces=new HiveNamespaceBrowser(jdbc,sourceService,transport);ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(namespaces);data=new ProjectDataResourceAccess(jdbc,provider);access=new MetadataResourceAccess(jdbc,data,new MetadataProjectAccess(jdbc,data,new ProjectPermissionService(jdbc)));
        databases=mock(IMcDbService.class);tables=mock(IMcTableService.class);columns=mock(IMcColumnService.class);dbController=new McDbController();tableController=new McTableController();columnController=new McColumnController();ReflectionTestUtils.setField(dbController,"resources",access);ReflectionTestUtils.setField(dbController,"mcDbService",databases);ReflectionTestUtils.setField(tableController,"resources",access);ReflectionTestUtils.setField(tableController,"mcTableService",tables);ReflectionTestUtils.setField(columnController,"resources",access);ReflectionTestUtils.setField(columnController,"mcColumnService",columns);
        seed(144,16);seed(143,17);seed(145,18);actor(927);
    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("metadata-project-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seed(long suffix,long source){String name="metadata_scope_"+suffix;copy("mc_db",127,900000+suffix,Map.of("datasource_id",source,"db_name",name));copy("mc_table",1864,910000+suffix,Map.of("datasource_id",source,"db_id",900000+suffix,"db_name",name,"table_name","scope_table_"+suffix,"creator_id",927L));copy("mc_column",24616,920000+suffix,Map.of("datasource_id",source,"db_id",900000+suffix,"table_id",910000+suffix,"column_name","sample_value","creator_id",927L));}
    McTableSaveReqVO table(long id){var r=new McTableSaveReqVO();r.setId(id);r.setDescription("scope update");return r;}
    McColumnSaveReqVO column(long id){var r=new McColumnSaveReqVO();r.setId(id);r.setColumnName("sample_value");r.setColumnType("DOUBLE");return r;}
    @Test void writerAndReaderTablesHaveDifferentFunctionalScopes(){assertTrue(access.tableIds("query").containsAll(List.of(910143L,910144L,910145L)));assertTrue(access.tableIds("edit").contains(910144L));assertFalse(access.tableIds("edit").contains(910143L));assertFalse(access.tableIds("edit").contains(910145L));}
    @Test void sameSourceSharedAcrossProjectsRequiresAllProjectsToEdit(){assertThrows(AccessDeniedException.class,()->access.database(900145L,"edit"));assertThrows(AccessDeniedException.class,()->access.column(920145L,"remove"));}
    @Test void readonlyMetadataCannotToggleOrBecomePortalVisible(){var state=new ToggleStatusVO();state.setId(910143L);state.setStatus("1");assertThrows(AccessDeniedException.class,()->tableController.toggle(state));state.setId(920143L);assertThrows(AccessDeniedException.class,()->columnController.toggle(state));var db=new McDbSaveReqVO();db.setId(900143L);db.setPortalVisible("1");assertThrows(AccessDeniedException.class,()->dbController.editPortalVisible(db));verifyNoInteractions(databases,tables,columns);}
    @Test void mixedTableDeleteChecksAllBeforeService(){assertThrows(AccessDeniedException.class,()->tableController.remove(new Long[]{910144L,910143L}));verifyNoInteractions(tables);}
    @Test void mixedDatabaseDeleteChecksAllBeforeService(){assertThrows(AccessDeniedException.class,()->dbController.remove(new Long[]{900144L,900145L}));verifyNoInteractions(databases);}
    @Test void mixedColumnDeleteChecksAllBeforeService(){assertThrows(AccessDeniedException.class,()->columnController.remove(new Long[]{920144L,920143L}));verifyNoInteractions(columns);}
    @Test void foreignTableCannotBeRetargetedIntoWritingProject(){var r=table(910143L);r.setDbId(900144L);r.setDatasourceId(16L);assertThrows(AccessDeniedException.class,()->tableController.edit(r));verifyNoInteractions(tables);}
    @Test void ownTableCannotChangeItsParentDatabase(){var r=table(910144L);r.setDbId(900143L);assertThrows(AccessDeniedException.class,()->access.tableRequest(r));}
    @Test void ownTableCannotChangeItsPhysicalName(){var r=table(910144L);r.setTableName("another_table");assertThrows(AccessDeniedException.class,()->access.tableRequest(r));}
    @Test void columnCannotMoveToAnotherTable(){var r=column(920144L);r.setTableId(910143L);assertThrows(AccessDeniedException.class,()->access.columnRequest(r));}
    @Test void columnCannotForgeDatabaseOrSourceIdentity(){var r=column(920144L);r.setDbId(900143L);assertThrows(AccessDeniedException.class,()->access.columnRequest(r));var bad=column(920144L);bad.setDatasourceId(17L);assertThrows(AccessDeniedException.class,()->access.columnRequest(bad));}
    @Test void readonlyParentCannotReceiveNewColumns(){var r=new McColumnSaveReqVO();r.setTableId(910143L);r.setColumnName("new_field");assertThrows(AccessDeniedException.class,()->access.columnRequest(r));}
    @Test void missingParentIsRejectedForNewTable(){var r=new McTableSaveReqVO();r.setTableName("orphan");assertThrows(IllegalArgumentException.class,()->access.tableRequest(r));}
    @Test void databaseCreationUsesRegisteredConnectionAndDraftStatus(){var r=new McDbSaveReqVO();r.setDatasourceId(16L);r.setDbName("new_catalog");r.setIp("untrusted.invalid");r.setDatasourceConfig("{\"password\":\"forged\"}");r.setTaskId(9L);r.setVersion(99);r.setStatus("1");r.setPortalVisible("1");r.setCreatorId(1L);access.databaseRequest(r);assertEquals("127.0.0.1",r.getIp());assertFalse(r.getDatasourceConfig().contains("forged"));assertNull(r.getTaskId());assertEquals(1,r.getVersion());assertEquals("0",r.getStatus());assertEquals("0",r.getPortalVisible());assertEquals(927L,r.getCreatorId());}
    @Test void readonlySourceCannotReceiveNewDatabase(){var r=new McDbSaveReqVO();r.setDatasourceId(17L);r.setDbName("denied");assertThrows(AccessDeniedException.class,()->access.databaseRequest(r));}
    @Test void existingDatabaseCannotChangeConnectionConfiguration(){var r=new McDbSaveReqVO();r.setId(900144L);r.setIp("untrusted.invalid");r.setDatasourceConfig("{\"password\":\"forged\"}");access.databaseRequest(r);assertEquals("127.0.0.1",r.getIp());assertFalse(r.getDatasourceConfig().contains("forged"));}
    @Test void tableCreationGetsParentIdentityAndDoesNotPublish(){var r=new McTableSaveReqVO();r.setDbId(900144L);r.setTableName("new_table");r.setStatus("1");r.setTaskId(9L);access.tableRequest(r);assertEquals(16L,r.getDatasourceId());assertEquals("metadata_scope_144",r.getDbName());assertEquals("0",r.getStatus());assertNull(r.getTaskId());}
    @Test void modificationCannotForgeAuditOrPublication(){var r=table(910144L);r.setCreatorId(1L);r.setCreateBy("forged");r.setStatus("1");r.setPortalVisible("1");access.tableRequest(r);assertEquals(927L,r.getCreatorId());assertEquals("qdata-test-fixture",r.getCreateBy());assertEquals("0",r.getStatus());assertEquals("0",r.getPortalVisible());}
    @Test void staleMetadataVersionIsRejected(){var r=table(910144L);r.setVersion(8);assertThrows(IllegalArgumentException.class,()->access.tableRequest(r));}
    @Test void queryPermissionCannotComeFromAnotherProject(){jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=20 AND m.perms='mc:metadata:table:query'");assertTrue(access.tableIds("list").contains(910143L));assertThrows(AccessDeniedException.class,()->tableController.getInfo(910143L));verifyNoInteractions(tables);}
    @Test void membershipWithoutFunctionalPermissionDoesNotRevealHiveMetadata(){jdbc.update("INSERT INTO att_project_user_rel(project_id,user_id,valid_flag,del_flag) VALUES(1,927,'1','0')");assertTrue(data.configurableDatasourceIds().contains(9L));assertThrows(AccessDeniedException.class,()->access.database(117L,"query"));assertThrows(AccessDeniedException.class,()->access.databaseRequest(null,9L,"dwd_iot_db"));}
    @Test void disabledSharedProjectStillPreventsOneProjectFromEditing(){jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=143");assertThrows(AccessDeniedException.class,()->access.table(910145L,"edit"));}
    @Test void listOverridesForgedAccessibleIdsBeforeService(){when(tables.getMcTablePage(any())).thenReturn(PageResult.empty());var request=new McTablePageReqVO();request.setAccessibleTableIds(List.of(1784L));tableController.list(request);assertFalse(request.getAccessibleTableIds().contains(1784L));assertTrue(request.getAccessibleTableIds().contains(910144L));}
    @Test void metadataProjectionCarriesCorrectRowCapabilities(){var row=new McTableRespVO();row.setId(910143L);assertFalse(access.present(row,"query").getCapabilities().get("edit"));row.setId(910144L);assertTrue(access.present(row,"query").getCapabilities().get("edit"));}
    @Test void columnCannotReferenceAnotherTablesHistory(){var r=column(920144L);Long log=jdbc.queryForObject("SELECT MIN(id) FROM mc_table_log",Long.class);assertNotNull(log);r.setMcTableLogId(log);assertThrows(AccessDeniedException.class,()->access.columnRequest(r));}
}
