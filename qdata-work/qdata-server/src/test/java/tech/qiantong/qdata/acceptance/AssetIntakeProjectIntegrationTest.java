package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tech.qiantong.qdata.module.da.service.asset.*;
import tech.qiantong.qdata.module.da.service.assetColumn.IDaAssetColumnService;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.module.da.controller.admin.asset.DaAssetController;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.*;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.DaAssetColumnController;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.*;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class AssetIntakeProjectIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    AssetMutationAccess mutations;AssetProjectReadAccess read;DataSourceFactory transport;DaAssetController controller;DaAssetColumnController columnController;IDaAssetService service;IDaAssetColumnService columnService;
    AssetIntakeAccess intake;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_ASSET_INTAKE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_asset_intake_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        transport=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);for(long id:new long[]{9,13,16,17,18})when(sources.getDaDatasourceById(id)).thenAnswer(call->HiveEndpointFixture.source(jdbc,id));
        ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(new HiveNamespaceBrowser(jdbc,sources,transport));var data=new ProjectDataResourceAccess(jdbc,provider);var permissions=new ProjectPermissionService(jdbc);mutations=new AssetMutationAccess(jdbc,data,permissions);read=new AssetProjectReadAccess(jdbc,data,permissions);
        service=mock(IDaAssetService.class);columnService=mock(IDaAssetColumnService.class);controller=new DaAssetController();columnController=new DaAssetColumnController();
        ReflectionTestUtils.setField(controller,"resourceAccess",data);ReflectionTestUtils.setField(controller,"readAccess",read);ReflectionTestUtils.setField(controller,"mutations",mutations);ReflectionTestUtils.setField(controller,"daAssetService",service);
        ReflectionTestUtils.setField(columnController,"resourceAccess",data);ReflectionTestUtils.setField(columnController,"readAccess",read);ReflectionTestUtils.setField(columnController,"mutations",mutations);ReflectionTestUtils.setField(columnController,"referenceGuard",new AssetReferenceGuard(jdbc));ReflectionTestUtils.setField(columnController,"daAssetColumnService",columnService);
        seed(144,16,List.of(144L));seed(143,17,List.of(143L));seed(145,18,List.of(143L,144L));actor(927);intake=new AssetIntakeAccess(jdbc,data,permissions,mutations,new AssetReferenceGuard(jdbc));
    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("asset-mutation-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seed(long suffix,long source,List<Long> projects){long id=900000+suffix;copy("da_asset",189,id,Map.of("name","asset_write_"+suffix,"datasource_id",String.valueOf(source),"creator_id",925L));for(Long project:projects)jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag) SELECT ?,id,code,'1','0' FROM att_project WHERE id=?",id,project);copy("da_asset_column",4044,910000+suffix,Map.of("asset_id",id,"creator_id",925L));}
    DaAssetSaveReqVO edit(long id){var r=new DaAssetSaveReqVO();r.setId(id);r.setDescription("scope edit");return r;}
    DaAssetColumnSaveReqVO column(long id){var r=new DaAssetColumnSaveReqVO();r.setId(id);r.setColumnComment("scope comment");return r;}

    DaAssetSaveReqVO create(long project){var r=new DaAssetSaveReqVO();r.setProjectId(project);r.setName("intake fixture");r.setType("1");r.setCreateType("1");return r;}
    DaAssetRespVO imported(long id){var r=new DaAssetRespVO();r.setId(id);r.setDescription("imported metadata");return r;}
    DaAssetColumnRespVO importedColumn(long id){var r=new DaAssetColumnRespVO();r.setId(id);r.setDescription("imported field");return r;}
    void revoke(String permission){jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=19 AND m.perms=?",permission);}
    @Test void creationProjectsExcludeReadonlyMembership(){assertEquals(List.of(144L),intake.projects().stream().map(p->((Number)p.get("id")).longValue()).toList());}
    @Test void creationRequiresExplicitProject(){var r=create(144);r.setProjectId(null);assertThrows(AccessDeniedException.class,()->intake.create(r));}
    @Test void readonlyProjectCannotUseWriterGrant(){assertThrows(AccessDeniedException.class,()->intake.create(create(143)));}
    @Test void nonmemberProjectCannotUseGlobalPermissionUnion(){assertThrows(AccessDeniedException.class,()->intake.create(create(1)));}
    @Test void logicalCreationNormalizesAuditAndProjectCode(){var r=create(144);r.setProjectCode("forged");r.setCreatorId(1L);r.setCreateBy("forged");r.setSourceType("0");r.setDataCount(999L);intake.create(r);assertEquals(927L,r.getCreatorId());assertEquals("asset-mutation-fixture",r.getCreateBy());assertEquals("1789410998694000",r.getProjectCode());assertEquals("1",r.getSourceType());assertEquals(0L,r.getDataCount());}
    @Test void newAssetCannotSupplyExistingId(){var r=create(144);r.setId(900143L);assertThrows(IllegalArgumentException.class,()->intake.create(r));}
    @Test void newAssetCannotInjectManagedDataset(){var r=create(144);r.setDatasetId(1L);assertThrows(IllegalArgumentException.class,()->intake.create(r));}
    @Test void logicalAssetCannotSneakInMetadataLink(){var r=create(144);r.setTableId(1864L);assertThrows(IllegalArgumentException.class,()->intake.create(r));}
    @Test void tableRegistrationRequiresMetadataId(){var r=create(144);r.setCreateType("2");assertThrows(IllegalArgumentException.class,()->intake.create(r));}
    @Test void authorizedMetadataSuppliesCanonicalIdentity(){var r=create(144);r.setCreateType("2");r.setTableId(1865L);intake.create(r);assertEquals("16",r.getDatasourceId());assertEquals("metadata_project_writer_20260915",r.getTableName());assertNotNull(r.getDatabaseName());}
    @Test void readonlyMetadataCannotBeRegisteredInWriterProject(){var r=create(144);r.setCreateType("2");r.setTableId(1864L);assertThrows(AccessDeniedException.class,()->intake.create(r));}
    @Test void metadataIdentityCannotBeSpoofed(){var r=create(144);r.setCreateType("2");r.setTableId(1865L);r.setDatabaseName("forged");assertThrows(AccessDeniedException.class,()->intake.create(r));}
    @Test void metadataOptionsAreProjectScopedAndContainNoConnectionConfig(){var rows=intake.metadata(144L,null,"metadata_");assertTrue(rows.stream().anyMatch(p->((Number)p.get("id")).longValue()==1865));assertTrue(rows.stream().noneMatch(p->((Number)p.get("id")).longValue()==1864));assertTrue(rows.stream().allMatch(p->p.keySet().stream().noneMatch(k->k.toLowerCase().contains("config") || k.toLowerCase().contains("password"))));}
    @Test void readonlyProjectCannotListRegistrationChoices(){assertThrows(AccessDeniedException.class,()->intake.metadata(143L,null,""));}
    @Test void readonlyAssetCannotListBindingChoices(){assertThrows(AccessDeniedException.class,()->intake.metadata(null,900143L,""));}
    @Test void batchPreflightsEveryProject(){assertThrows(AccessDeniedException.class,()->intake.batch(List.of(create(144),create(143))));}
    @Test void duplicateMetadataInBatchIsRejected(){var a=create(144);a.setCreateType("2");a.setTableId(1865L);var b=create(144);b.setCreateType("2");b.setTableId(1865L);assertThrows(IllegalArgumentException.class,()->intake.batch(List.of(a,b)));}
    @Test void nestedForeignApiIdCannotBeInjectedIntoNewAsset(){var r=create(144);var api=new DaAssetApiSaveReqVO();api.setId(999L);r.setDaAssetApi(api);assertThrows(AccessDeniedException.class,()->intake.create(r));}
    @Test void newApiParametersCannotReferenceExistingParent(){var r=create(144);var p=new DaAssetApiParamSaveReqVO();p.setParentId(999L);r.setDaAssetApiParamList(List.of(p));assertThrows(AccessDeniedException.class,()->intake.create(r));}
    @Test void externalRegistrationRequiresFullConfiguration(){var r=create(144);r.setType("2");r.setCreateType("2");assertThrows(IllegalArgumentException.class,()->intake.create(r));}
    @Test void assetImportRequiresImportAndEditInSameProject(){revoke("da:asset:import");assertThrows(AccessDeniedException.class,()->intake.assets(List.of(imported(900144)),true));}
    @Test void assetImportCannotBorrowEditFromAnotherProject(){assertThrows(AccessDeniedException.class,()->intake.assets(List.of(imported(900143)),true));}
    @Test void importedAuditAndCountsCannotOverwriteStoredValues(){var r=imported(900144);r.setCreatorId(1L);r.setDataCount(999L);var actual=intake.assets(List.of(r),true).get(0);assertEquals(925L,actual.getCreatorId());assertEquals(0L,actual.getDataCount());assertEquals(927L,actual.getUpdatorId());}
    @Test void importCannotBindWithoutFieldCreation(){jdbc.update("UPDATE da_asset SET create_type='1' WHERE id=900144");var r=imported(900144);r.setCreateType("2");assertThrows(IllegalArgumentException.class,()->intake.assets(List.of(r),true));}
    @Test void assetImportRejectsDuplicateIds(){assertThrows(IllegalArgumentException.class,()->intake.assets(List.of(imported(900144),imported(900144)),true));}
    @Test void assetImportPreflightsBeforeFirstBusinessWrite(){var service=spy(new tech.qiantong.qdata.module.da.service.asset.impl.DaAssetServiceImpl());ReflectionTestUtils.setField(service,"intake",intake);doReturn(1).when(service).updateDaAssetNew(any());assertThrows(AccessDeniedException.class,()->service.importDaAsset(List.of(imported(900144),imported(900143)),true,"forged"));verify(service,never()).updateDaAssetNew(any());}
    @Test void validImportUsesBusinessUpdateInsteadOfRawMapper(){var service=spy(new tech.qiantong.qdata.module.da.service.asset.impl.DaAssetServiceImpl());ReflectionTestUtils.setField(service,"intake",intake);doReturn(1).when(service).updateDaAssetNew(any());assertTrue(service.importDaAsset(List.of(imported(900144)),true,"forged").contains("1"));verify(service).updateDaAssetNew(argThat(r->r.getId()==900144L && r.getType()==null));}
    @Test void fieldImportCannotBorrowWriterPermission(){assertThrows(AccessDeniedException.class,()->intake.columns(List.of(importedColumn(910143)),true));}
    @Test void fieldImportRequiresSpecificImportPermission(){revoke("da:assetColumn:import");assertThrows(AccessDeniedException.class,()->intake.columns(List.of(importedColumn(910144)),true));}
    @Test void fieldImportPreservesPhysicalSchemaAndCreator(){var r=importedColumn(910144);r.setCreatorId(1L);r.setColumnLength(999L);var actual=intake.columns(List.of(r),true).get(0);assertEquals(925L,actual.getCreatorId());assertEquals("900144",actual.getAssetId());assertNotEquals(999L,actual.getColumnLength());}
    @Test void fieldImportCannotMoveToAnotherAsset(){var r=importedColumn(910144);r.setAssetId("900143");assertThrows(AccessDeniedException.class,()->intake.columns(List.of(r),true));}
    @Test void fieldImportPreflightsBeforeAnyWrite(){var service=spy(new tech.qiantong.qdata.module.da.service.assetColumn.impl.DaAssetColumnServiceImpl());ReflectionTestUtils.setField(service,"intake",intake);doReturn(1).when(service).updateDaAssetColumn(any());assertThrows(AccessDeniedException.class,()->service.importDaAssetColumn(List.of(importedColumn(910144),importedColumn(910143)),true,"forged"));verify(service,never()).updateDaAssetColumn(any());}
    @Test void newFieldImportRequiresAssetOwnership(){var r=new DaAssetColumnRespVO();r.setAssetId("900143");r.setColumnName("extra");r.setColumnType("STRING");assertThrows(AccessDeniedException.class,()->intake.columns(List.of(r),false));}
    @Test void newFieldImportRejectsDuplicateNames(){var r=new DaAssetColumnRespVO();r.setAssetId("900144");r.setColumnName("scope_value");r.setColumnType("STRING");assertThrows(IllegalArgumentException.class,()->intake.columns(List.of(r),false));}
    @Test void newFieldImportSetsAudit(){var r=new DaAssetColumnRespVO();r.setAssetId("900144");r.setColumnName("new_value");r.setColumnType("STRING");r.setCreatorId(1L);var actual=intake.columns(List.of(r),false).get(0);assertEquals(927L,actual.getCreatorId());}

    @Test void writerCanInspectUploadedFileWithoutDevelopmentPermission(){jdbc.update("DELETE rm FROM system_role_menu rm JOIN system_menu m ON m.menu_id=rm.menu_id WHERE rm.role_id=19 AND m.perms LIKE 'dpp:%'");assertDoesNotThrow(()->intake.fileInspection(144L,null));}
    @Test void fileInspectionCannotBorrowAnotherProjectsAddPermission(){assertThrows(AccessDeniedException.class,()->intake.fileInspection(143L,null));}
    @Test void existingFileInspectionRequiresAllOwnerEditPermissions(){jdbc.update("UPDATE da_asset SET type='6' WHERE id IN(900143,900144,900145)");assertDoesNotThrow(()->intake.fileInspection(null,900144L));assertThrows(AccessDeniedException.class,()->intake.fileInspection(null,900143L));assertThrows(AccessDeniedException.class,()->intake.fileInspection(null,900145L));}
    @Test void fileInspectionCannotSelectAFormerTableAsset(){assertThrows(IllegalArgumentException.class,()->intake.fileInspection(null,900144L));}
}
