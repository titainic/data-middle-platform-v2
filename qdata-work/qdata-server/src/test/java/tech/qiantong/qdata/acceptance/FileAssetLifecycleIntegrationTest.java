package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.module.da.service.asset.*;
import tech.qiantong.qdata.module.da.service.assetColumn.AssetColumnStatistics;
import tech.qiantong.qdata.module.da.service.assetchild.files.impl.DaAssetFilesServiceImpl;
import tech.qiantong.qdata.module.da.dal.mapper.assetchild.files.DaAssetFilesMapper;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.files.vo.DaAssetFilesSaveReqVO;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetSaveReqVO;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class FileAssetLifecycleIntegrationTest {
    @TempDir Path upload;
    static final long ASSET=990001;
    SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;
    FileAssetLifecycle lifecycle;DaAssetFilesServiceImpl service;org.apache.ibatis.session.SqlSession session;
    @BeforeEach void setup()throws Exception {
        String url=System.getenv("QDATA_FILE_LIFECYCLE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_file_lifecycle_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=manager.getTransaction(new DefaultTransactionDefinition());
        var user=new SysUser();user.setUserId(927L);user.setUserName("file-lifecycle-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(927L,null,user,Set.of("*:*:*")),null,List.of()));
        var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM da_asset WHERE id=189"));row.putAll(Map.of("id",ASSET,"type","6","datasource_id","-1","field_count",0,"data_count",0));row.put("dataset_id",null);insert("da_asset",row);
        lifecycle=new FileAssetLifecycle(jdbc,new AssetColumnStatistics(jdbc),new AssetReferenceGuard(jdbc),org.mockito.Mockito.mock(tech.qiantong.qdata.common.file.UploadedFileAccess.class));ReflectionTestUtils.setField(lifecycle,"profile",upload.toString());service=new DaAssetFilesServiceImpl();ReflectionTestUtils.setField(service,"lifecycle",lifecycle);
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("file-lifecycle",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),source));config.setMapUnderscoreToCamelCase(true);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());config.addMapper(DaAssetFilesMapper.class);session=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config).openSession();ReflectionTestUtils.setField(service,"daAssetFilesMapper",session.getMapper(DaAssetFilesMapper.class));
        Files.writeString(upload.resolve("initial.csv"),"sensor_id,value\nwater_1,7.10\nair_1,12\n");
    }
    @AfterEach void close(){session.close();if(!tx.isCompleted())manager.rollback(tx);source.destroy();SecurityContextHolder.clearContext();}
    void insert(String table,Map<String,Object> row){jdbc.update("INSERT INTO "+table+"("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    DaAssetFilesSaveReqVO request(String name){var r=new DaAssetFilesSaveReqVO();r.setAssetId(ASSET);r.setUrl("/profile/"+name);r.setName(name);r.setCreatorId(925L);r.setCreateBy("original");return r;}
    Long create(){return service.createDaAssetFiles(request("initial.csv"));}
    List<Map<String,Object>> fields(){return jdbc.queryForList("SELECT * FROM da_asset_column WHERE asset_id=? ORDER BY id",String.valueOf(ASSET));}
    Map<String,Object> counts(){var row=jdbc.queryForMap("SELECT field_count,data_count FROM da_asset WHERE id=?",ASSET);return Map.of("field_count",((Number)row.get("field_count")).longValue(),"data_count",((Number)row.get("data_count")).longValue());}
    void reference(){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM dpp_quality_task WHERE id=36"));row.put("id",990101L);row.put("asset_id",ASSET);row.put("task_name","file-reference-fixture");insert("dpp_quality_task",row);}
    @Test void registrationCreatesMatchingFieldsAndCounts(){Long id=create();assertNotNull(id);assertEquals(2,fields().size());assertEquals(Map.of("field_count",2L,"data_count",2L),counts());assertEquals(".csv",jdbc.queryForObject("SELECT type FROM da_asset_files WHERE id=?",String.class,id));assertEquals(2,jdbc.queryForObject("SELECT start_data FROM da_asset_files WHERE id=?",Integer.class,id));}
    @Test void unchangedHeadersKeepEveryFieldIdentityAndAudit(){Long id=create();var before=fields();var r=request("initial.csv");r.setId(id);service.updateDaAssetFiles(r);assertEquals(before,fields());}
    @Test void addingFieldRetainsExistingRows(){Long id=create();var before=fields();try{Files.writeString(upload.resolve("extended.csv"),"sensor_id,value,site\nwater_1,7.10,north\n");}catch(Exception e){throw new RuntimeException(e);}var r=request("extended.csv");r.setId(id);service.updateDaAssetFiles(r);assertEquals(before,fields().subList(0,2));assertEquals(Map.of("field_count",3L,"data_count",1L),counts());}
    @Test void removedFieldsAreArchivedWithAudit(){Long id=create();long removed=((Number)fields().get(1).get("id")).longValue();try{Files.writeString(upload.resolve("narrow.csv"),"sensor_id\nsoil_1\n");}catch(Exception e){throw new RuntimeException(e);}var r=request("narrow.csv");r.setId(id);service.updateDaAssetFiles(r);assertEquals("1",jdbc.queryForObject("SELECT del_flag FROM da_asset_column WHERE id=?",String.class,removed));assertEquals(927L,jdbc.queryForObject("SELECT updater_id FROM da_asset_column WHERE id=?",Long.class,removed));assertEquals(Map.of("field_count",1L,"data_count",1L),counts());}
    @Test void referencedAssetCannotSwitchFileEvenWithSameColumns()throws Exception{Long id=create();reference();Files.writeString(upload.resolve("different.csv"),"sensor_id,value\nsoil_1,8\n");var r=request("different.csv");r.setId(id);assertThrows(IllegalArgumentException.class,()->service.updateDaAssetFiles(r));assertEquals("/profile/initial.csv",jdbc.queryForObject("SELECT url FROM da_asset_files WHERE id=?",String.class,id));}
    @Test void referencedAssetCanRetainSameFileAndSchema(){Long id=create();reference();var before=fields();var r=request("initial.csv");r.setId(id);service.updateDaAssetFiles(r);assertEquals(before,fields());}
    @Test void duplicateFileBindingIsRejectedBeforeInsert(){create();assertThrows(IllegalArgumentException.class,this::create);assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM da_asset_files WHERE asset_id=? AND del_flag='0'",Long.class,ASSET));}
    @Test void invalidRowsLeaveOriginalMetadataUnchanged()throws Exception{Long id=create();var before=fields();Files.writeString(upload.resolve("invalid.csv"),"sensor_id,value\nsoil_1\n");var r=request("invalid.csv");r.setId(id);assertThrows(IllegalArgumentException.class,()->service.updateDaAssetFiles(r));assertEquals(before,fields());assertEquals("/profile/initial.csv",jdbc.queryForObject("SELECT url FROM da_asset_files WHERE id=?",String.class,id));}
    @Test void unsupportedAttachmentHasNoFabricatedTabularSchema()throws Exception{Files.writeString(upload.resolve("manual.txt"),"document body");service.createDaAssetFiles(request("manual.txt"));assertTrue(fields().isEmpty());assertEquals(Map.of("field_count",0L,"data_count",0L),counts());}
    @Test void parentUpdateReceivesActualDerivedStatistics(){create();var parent=new DaAssetSaveReqVO();parent.setId(ASSET);parent.setFieldCount(0L);parent.setDataCount(0L);lifecycle.presentStatistics(parent);assertEquals(2L,parent.getFieldCount());assertEquals(2L,parent.getDataCount());}
    @Test void fileChildUpdatePreservesOriginalCreator(){Long id=create();var before=jdbc.queryForMap("SELECT creator_id,create_by,create_time FROM da_asset_files WHERE id=?",id);var r=request("initial.csv");r.setId(id);r.setCreatorId(1L);r.setCreateBy("forged");service.updateDaAssetFiles(r);assertEquals(before,jdbc.queryForMap("SELECT creator_id,create_by,create_time FROM da_asset_files WHERE id=?",id));assertEquals(927L,jdbc.queryForObject("SELECT updater_id FROM da_asset_files WHERE id=?",Long.class,id));}
    @Test void unboundFileCannotBeMovedToAnotherParent(){Long id=create();var r=request("initial.csv");r.setId(id);r.setAssetId(189L);assertThrows(IllegalArgumentException.class,()->service.updateDaAssetFiles(r));}
}
