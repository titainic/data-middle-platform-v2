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
class AssetMutationProjectIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus transaction;
    AssetMutationAccess mutations;AssetProjectReadAccess read;DataSourceFactory transport;DaAssetController controller;DaAssetColumnController columnController;IDaAssetService service;IDaAssetColumnService columnService;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_ASSET_WRITE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_asset_write_"));fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);manager=new DataSourceTransactionManager(fixture);transaction=manager.getTransaction(new DefaultTransactionDefinition());
        transport=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);for(long id:new long[]{9,13,16,17,18})when(sources.getDaDatasourceById(id)).thenAnswer(call->HiveEndpointFixture.source(jdbc,id));
        ObjectProvider<HiveNamespaceBrowser> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(new HiveNamespaceBrowser(jdbc,sources,transport));var data=new ProjectDataResourceAccess(jdbc,provider);var permissions=new ProjectPermissionService(jdbc);mutations=new AssetMutationAccess(jdbc,data,permissions);read=new AssetProjectReadAccess(jdbc,data,permissions);
        service=mock(IDaAssetService.class);columnService=mock(IDaAssetColumnService.class);controller=new DaAssetController();columnController=new DaAssetColumnController();
        ReflectionTestUtils.setField(controller,"resourceAccess",data);ReflectionTestUtils.setField(controller,"readAccess",read);ReflectionTestUtils.setField(controller,"mutations",mutations);ReflectionTestUtils.setField(controller,"daAssetService",service);
        ReflectionTestUtils.setField(columnController,"resourceAccess",data);ReflectionTestUtils.setField(columnController,"readAccess",read);ReflectionTestUtils.setField(columnController,"mutations",mutations);ReflectionTestUtils.setField(columnController,"referenceGuard",new AssetReferenceGuard(jdbc));ReflectionTestUtils.setField(columnController,"daAssetColumnService",columnService);
        seed(144,16,List.of(144L));seed(143,17,List.of(143L));seed(145,18,List.of(143L,144L));actor(927);
    }
    @AfterEach void close(){try{verifyNoInteractions(transport);}finally{manager.rollback(transaction);fixture.destroy();SecurityContextHolder.clearContext();}}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("asset-mutation-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    void seed(long suffix,long source,List<Long> projects){long id=900000+suffix;copy("da_asset",189,id,Map.of("name","asset_write_"+suffix,"datasource_id",String.valueOf(source),"creator_id",925L));for(Long project:projects)jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag) SELECT ?,id,code,'1','0' FROM att_project WHERE id=?",id,project);copy("da_asset_column",4044,910000+suffix,Map.of("asset_id",id,"creator_id",925L));}
    DaAssetSaveReqVO edit(long id){var r=new DaAssetSaveReqVO();r.setId(id);r.setDescription("scope edit");return r;}
    DaAssetColumnSaveReqVO column(long id){var r=new DaAssetColumnSaveReqVO();r.setId(id);r.setColumnComment("scope comment");return r;}
    @Test void sameUserCanReadBothButEditOnlyOwningWriterProject(){assertDoesNotThrow(()->read.asset(900143L,"da:asset:query"));assertDoesNotThrow(()->mutations.edit(edit(900144)));assertThrows(AccessDeniedException.class,()->mutations.edit(edit(900143)));}
    @Test void sharedAssetRequiresEveryProjectToGrantEdit(){assertDoesNotThrow(()->read.asset(900145L,"da:asset:query"));assertThrows(AccessDeniedException.class,()->mutations.edit(edit(900145)));}
    @Test void readonlyEditIsRejectedBeforeService(){assertThrows(AccessDeniedException.class,()->controller.edit(edit(900143)));verifyNoInteractions(service);}
    @Test void readonlyPublicationIsRejected(){var r=edit(900143);r.setStatus("2");assertThrows(AccessDeniedException.class,()->controller.edit(r));verifyNoInteractions(service);}
    @Test void readonlyRemoveIsRejectedBeforeReferenceOrDataServices(){assertThrows(AccessDeniedException.class,()->controller.remove(900143L));verifyNoInteractions(service);}
    @Test void readonlyRefreshIsRejected(){assertThrows(AccessDeniedException.class,()->controller.startDaDiscoveryTask(edit(900143)));verifyNoInteractions(service);}
    @Test void disabledConnectionCannotRefreshEvenForWriter(){assertThrows(AccessDeniedException.class,()->controller.startDaDiscoveryTask(edit(900144)));verifyNoInteractions(service);}
    @Test void existingAssetCannotChangeProject(){var r=edit(900144);r.setProjectId(143L);assertThrows(AccessDeniedException.class,()->mutations.edit(r));}
    @Test void existingAssetCannotChangeSource(){var r=edit(900144);r.setDatasourceId("17");assertThrows(AccessDeniedException.class,()->mutations.edit(r));}
    @Test void existingAssetCannotChangePhysicalTable(){var r=edit(900144);r.setTableName("other_table");assertThrows(AccessDeniedException.class,()->mutations.edit(r));}
    @Test void existingAssetCannotChangeDatasetIdentity(){var r=edit(900144);r.setDatasetId(4L);assertThrows(AccessDeniedException.class,()->mutations.edit(r));}
    @Test void existingAssetTypeCannotBeChanged(){var r=edit(900144);r.setType("2");assertThrows(IllegalArgumentException.class,()->mutations.edit(r));}
    @Test void creatorAndMetricsAreServerControlled(){var r=edit(900144);r.setCreatorId(1L);r.setCreateBy("forged");r.setDataCount(999L);r.setFieldCount(999L);mutations.edit(r);assertEquals(925L,r.getCreatorId());assertEquals("qdata-test-fixture",r.getCreateBy());assertEquals(0L,r.getDataCount());assertEquals(1L,r.getFieldCount());assertEquals(927L,r.getUpdatorId());}
    @Test void unchangedLegacyPublicationStateCanBePreserved(){jdbc.update("UPDATE da_asset SET status='0' WHERE id=900144");var r=edit(900144);r.setStatus("0");assertDoesNotThrow(()->mutations.edit(r));r.setStatus("999");assertThrows(IllegalArgumentException.class,()->mutations.edit(r));}
    @Test void creatorCannotMutateUnassignedLegacyAsset(){jdbc.update("UPDATE da_asset_project_rel SET valid_flag='0' WHERE asset_id=900144");jdbc.update("UPDATE da_asset SET creator_id=927 WHERE id=900144");assertThrows(AccessDeniedException.class,()->mutations.edit(edit(900144)));}
    @Test void disabledSharedOwnerStillPreventsSingleProjectWrite(){jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=143");assertThrows(AccessDeniedException.class,()->mutations.edit(edit(900145)));}
    @Test void writerCapabilitiesDoNotLeakToReader(){var r=new DaAssetRespVO();r.setId(900143L);assertFalse(mutations.present(r).getWriteCapabilities().get("edit"));r.setId(900144L);assertTrue(mutations.present(r).getWriteCapabilities().get("edit"));}
    @Test void readonlyColumnCannotBeEdited(){assertThrows(AccessDeniedException.class,()->columnController.edit(column(910143)));verifyNoInteractions(columnService);}
    @Test void columnCannotMoveToAnotherAsset(){var r=column(910144);r.setAssetId("900143");assertThrows(AccessDeniedException.class,()->mutations.columnEdit(r));}
    @Test void partialColumnEditKeepsParentNameAndCodeTable(){jdbc.update("UPDATE da_asset_column SET data_elem_code_flag='1',data_elem_code_id=99 WHERE id=910144");var r=column(910144);r.setCreatorId(1L);mutations.columnEdit(r);assertEquals("900144",r.getAssetId());assertEquals("scope_value",r.getColumnName());assertEquals("1",r.getDataElemCodeFlag());assertEquals("99",r.getDataElemCodeId());assertEquals(925L,r.getCreatorId());}
    @Test void mixedColumnDeletesArePreflightedBeforeMutation(){assertThrows(AccessDeniedException.class,()->columnController.remove(new Long[]{910144L,910143L}));verifyNoInteractions(columnService);}
    @Test void readonlyAssetCannotReceiveNewColumns(){var r=new DaAssetColumnSaveReqVO();r.setAssetId("900143");r.setColumnName("new_field");assertThrows(AccessDeniedException.class,()->columnController.add(r));verifyNoInteractions(columnService);}
    @Test void foreignMetadataCannotBeBoundIntoWriterAsset(){jdbc.update("UPDATE da_asset SET create_type='1',datasource_id='-1',table_name='-1',database_name=NULL WHERE id=900144");var r=edit(900144);r.setTableId(1864L);r.setType("1");assertThrows(AccessDeniedException.class,()->mutations.bind(r));}
    @Test void authorizedMetadataBindingUsesRegisteredIdentity(){jdbc.update("UPDATE da_asset SET create_type='1',datasource_id='-1',table_name='-1',database_name=NULL WHERE id=900144");var r=edit(900144);r.setTableId(1865L);r.setType("1");mutations.bind(r);assertEquals("16",r.getDatasourceId());assertEquals("metadata_project_writer_20260915",r.getTableName());assertEquals("2",r.getCreateType());}
    @Test void registeredAssetCannotBeBoundAgain(){var r=edit(900144);r.setTableId(1865L);assertThrows(IllegalArgumentException.class,()->mutations.bind(r));}
    @Test void nestedApiCannotTargetAnotherAsset(){jdbc.update("INSERT INTO da_asset_api(id,asset_id,url,http_method,valid_flag,del_flag,developer_name,app_name) VALUES(930143,900143,'http://127.0.0.1:1','GET','1','0','fixture','fixture')");var r=edit(900144);var api=new DaAssetApiSaveReqVO();api.setId(930143L);api.setUrl("http://127.0.0.1:1/changed");r.setDaAssetApi(api);assertThrows(AccessDeniedException.class,()->mutations.edit(r));}
    @ParameterizedTest @ValueSource(strings={"Gis","Geo","Video","Files"})
    void missingNestedResourceIsRejectedBeforeService(String kind) throws Exception {String segment=kind.toLowerCase(Locale.ROOT);var type=Class.forName("tech.qiantong.qdata.module.da.controller.admin.assetchild."+segment+".vo.DaAsset"+kind+"SaveReqVO");var child=type.getConstructor().newInstance();type.getMethod("setId",Long.class).invoke(child,999999L);var r=edit(900144);DaAssetSaveReqVO.class.getMethod("setDaAsset"+kind,type).invoke(r,child);assertThrows(AccessDeniedException.class,()->mutations.edit(r));}
    @Test void physicalFieldNameCannotBeChangedThroughCatalog(){var r=column(910144);r.setColumnName("renamed");assertThrows(AccessDeniedException.class,()->mutations.columnEdit(r));}
    @Test void physicalFieldTypeCannotBeChangedThroughCatalog(){var r=column(910144);r.setColumnType("INTEGER");assertThrows(AccessDeniedException.class,()->mutations.columnEdit(r));}

    @Test void readonlyPhysicalColumnAttributesArePreserved(){var before=jdbc.queryForMap("SELECT column_length,column_scale,nullable_flag,pk_flag,default_value FROM da_asset_column WHERE id=910144");var r=column(910144);r.setColumnLength(999L);r.setColumnScale(99L);r.setNullableFlag("forged");r.setPkFlag("forged");r.setDefaultValue("forged");mutations.columnEdit(r);assertEquals(before.get("nullable_flag"),r.getNullableFlag());assertEquals(before.get("pk_flag"),r.getPkFlag());assertEquals(before.get("default_value"),r.getDefaultValue());assertNotEquals(999L,r.getColumnLength());assertNotEquals(99L,r.getColumnScale());}
    @Test void nestedEditWithoutIdUsesOwnedRecordAndKeepsCreator(){jdbc.update("INSERT INTO da_asset_api(id,asset_id,url,http_method,valid_flag,del_flag,developer_name,app_name,creator_id,create_by) VALUES(930144,900144,'http://127.0.0.1:1','GET','1','0','fixture','fixture',925,'original-creator')");var r=edit(900144);var api=new DaAssetApiSaveReqVO();api.setCreateBy("forged");r.setDaAssetApi(api);mutations.edit(r);assertEquals(930144L,api.getId());assertEquals(900144L,api.getAssetId());assertEquals(925L,api.getCreatorId());assertEquals("original-creator",api.getCreateBy());}

}
