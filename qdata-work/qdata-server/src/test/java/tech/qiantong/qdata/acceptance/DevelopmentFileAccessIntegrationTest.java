package tech.qiantong.qdata.acceptance;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.file.UploadedFileAccess;
import tech.qiantong.qdata.common.file.UploadedFileKey;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.dpp.service.etl.DevelopmentFileAccessService;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class DevelopmentFileAccessIntegrationTest {
 @TempDir Path root;
 SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;
 DevelopmentFileAccessService access;UploadedFileAccess uploads;DataResourceAccess resources;
 String own,other,uri;
 @BeforeEach void setup()throws Exception {
  String url=System.getenv("QDATA_DEV_FILE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_dev_file_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=manager.getTransaction(new DefaultTransactionDefinition());
  uploads=mock(UploadedFileAccess.class);resources=mock(DataResourceAccess.class);when(resources.visibleProjectIds()).thenReturn(Set.of(1L));
  ProjectPermissionService permissions=mock(ProjectPermissionService.class,invocation->{if("any".equals(invocation.getMethod().getName()))return true;return RETURNS_DEFAULTS.answer(invocation);});
  access=new DevelopmentFileAccessService(jdbc,resources,permissions,uploads);ReflectionTestUtils.setField(access,"profile",root.toString());
  Files.writeString(root.resolve("own.csv"),"medium,value\nwater,7.1\n");Files.writeString(root.resolve("other.csv"),"medium,value\nsoil,-0.000\n");own="/profile/own.csv";other="/profile/other.csv";uri="hdfs://test-nn:8020/tmp/etl/"+UUID.randomUUID().toString().replace("-","")+".csv";
  var user=new SysUser();user.setUserId(925L);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(925L,null,user,Set.of()),null,List.of()));
 }
 @AfterEach void close(){if(tx!=null&&!tx.isCompleted())manager.rollback(tx);if(source!=null)source.destroy();SecurityContextHolder.clearContext();}
 @Test void ownedConversionRegistersImmutableProjectAndSource(){access.register(1L,own,uri);access.validate(1L,own,uri);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_development_file_artifact WHERE execution_hash=? AND origin='UPLOAD' AND owner_user_id=925",Integer.class,UploadedFileKey.hash(uri)));verify(uploads,atLeastOnce()).use(own,Set.of(1L),null);}
 @Test void guessingAnotherProjectsExecutionUriCannotBindIt(){access.register(1L,own,uri);assertThrows(AccessDeniedException.class,()->access.validate(144L,own,uri));}
 @Test void swappingTheOriginalUploadIsRejected(){access.register(1L,own,uri);assertThrows(AccessDeniedException.class,()->access.validate(1L,other,uri));}
 @Test void unregisteredArtifactCannotBeClaimedByAProject(){assertThrows(AccessDeniedException.class,()->access.validate(1L,own,uri));}
 @Test void existingArtifactCannotBeReRegisteredToAnotherSourceOrProject(){access.register(1L,own,uri);assertThrows(AccessDeniedException.class,()->access.register(1L,other,uri));assertThrows(AccessDeniedException.class,()->access.register(144L,own,uri));}
 @Test void sourceUploadPolicyRunsBeforeRegistration(){doThrow(new AccessDeniedException("foreign upload")).when(uploads).read(other);assertThrows(AccessDeniedException.class,()->access.register(1L,other,uri));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_development_file_artifact WHERE execution_hash=?",Integer.class,UploadedFileKey.hash(uri)));}
 @Test void projectWriteIsRequiredEvenForPersonalSource(){when(resources.visibleProjectIds()).thenReturn(Set.of(144L));assertThrows(AccessDeniedException.class,()->access.register(1L,own,uri));}
 @Test void registeredLegacySourceMayBeReparsedOnlyInItsProject(){String hash=UploadedFileKey.hash("hdfs://test-nn:8020/tmp/etl/legacy.csv");jdbc.update("INSERT INTO sys_development_file_artifact(execution_hash,execution_path,source_hash,source_resource,project_id,origin) VALUES(?,?,?,?,1,'LEGACY')",hash,"hdfs://test-nn:8020/tmp/etl/legacy.csv",UploadedFileKey.hash(own),own);access.authorizeSource(1L,own);verifyNoInteractions(uploads);assertThrows(AccessDeniedException.class,()->access.authorizeSource(144L,own));}
 @Test void graphChecksTheExactSourceAndArtifactPair(){access.register(1L,own,uri);String good="[{\"code\":\"100\",\"componentType\":\"4\",\"taskParams\":{\"file\":\""+own+"\",\"csvFile\":\""+uri+"\"}}]";access.definitions(1L,good);assertThrows(AccessDeniedException.class,()->access.definitions(144L,good));assertThrows(AccessDeniedException.class,()->access.definitions(1L,good.replace(own,other)));}
 @Test void unsafeOrEncodedExecutionPathsAreRejected(){for(String bad:List.of("hdfs://test-nn:8020/tmp/etl/../private.csv","hdfs://test-nn:8020/tmp/etl/%2e%2e.csv","hdfs://user@test-nn:8020/tmp/etl/a.csv","hdfs://test-nn:8020/tmp/etl/a.csv?secret=1","relative.csv"))assertThrows(IllegalArgumentException.class,()->access.register(1L,own,bad));}
 @Test void schedulerChecksCopiedHistoricalNodeMetadataWithoutAUserSession(){ReflectionTestUtils.setField(access,"profile","/data/qdata/upload");Long task=jdbc.queryForObject("SELECT MIN(r.task_id) FROM dpp_etl_task_node_rel r JOIN dpp_etl_node n ON n.code=r.post_node_code AND n.version=r.post_node_version AND n.project_id=r.project_id WHERE n.component_type='4' AND r.del_flag='0' AND n.del_flag='0'",Long.class);assertNotNull(task);SecurityContextHolder.clearContext();access.task(task);}
 @Test void archivedTaskProjectRevocationStopsBackgroundInputUse(){ReflectionTestUtils.setField(access,"profile","/data/qdata/upload");Long task=jdbc.queryForObject("SELECT MIN(r.task_id) FROM dpp_etl_task_node_rel r JOIN dpp_etl_node n ON n.code=r.post_node_code AND n.version=r.post_node_version AND n.project_id=r.project_id WHERE n.component_type='4' AND r.del_flag='0' AND n.del_flag='0'",Long.class);jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=1");assertThrows(AccessDeniedException.class,()->access.task(task));}
}
