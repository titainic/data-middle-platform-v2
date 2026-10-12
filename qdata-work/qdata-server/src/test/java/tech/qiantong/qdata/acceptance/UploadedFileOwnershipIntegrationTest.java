package tech.qiantong.qdata.acceptance;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
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
import tech.qiantong.qdata.common.file.UploadedFileKey;
import tech.qiantong.qdata.module.da.service.asset.*;
import tech.qiantong.qdata.module.da.service.datasource.HiveNamespaceBrowser;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class UploadedFileOwnershipIntegrationTest {
 @TempDir Path upload;
 SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;
 UploadedFileAccessService access;ProjectPermissionService permissions;boolean exports=true,queries=true,admin=false;long actor;
 static final long A=990501,B=990502,A2=990503,FILE=990511;
 @BeforeEach void setup()throws Exception {
  String url=System.getenv("QDATA_UPLOAD_SCOPE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_upload_scope_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=manager.getTransaction(new DefaultTransactionDefinition());
  permissions=mock(ProjectPermissionService.class);when(permissions.isAdministrator()).thenAnswer(x->admin);
  when(permissions.has(anyLong(),anyString())).thenAnswer(x->{long project=x.getArgument(0);String capability=x.getArgument(1);boolean member=actor==927?(project==143||project==144):actor==926?project==143:actor==925?(project==1||project==144):false;return member && (capability.endsWith(":export")?exports:queries);});
  var data=new ProjectDataResourceAccess(jdbc,mock(ObjectProvider.class));var read=new AssetProjectReadAccess(jdbc,data,permissions);access=new UploadedFileAccessService(jdbc,read,permissions);ReflectionTestUtils.setField(access,"profile",upload.toString());
  asset(A,927,144);asset(B,926,143);asset(A2,927,144);login(927);
 }
 @AfterEach void close(){if(!tx.isCompleted())manager.rollback(tx);source.destroy();SecurityContextHolder.clearContext();}
 void login(long userId){actor=userId;var user=new SysUser();user.setUserId(userId);user.setUserName("upload-fixture-"+userId);user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(userId,null,user,Set.of("*:*:*")),null,List.of()));}
 void insert(String table,Map<String,Object> row){jdbc.update("INSERT INTO "+table+"("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
 void asset(long id,long owner,long project){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM da_asset WHERE id=258"));row.put("id",id);row.put("creator_id",owner);row.put("dataset_id",null);row.put("type","6");insert("da_asset",row);jdbc.update("INSERT INTO da_asset_project_rel(asset_id,project_id,project_code,valid_flag,del_flag) SELECT ?,id,code,'1','0' FROM att_project WHERE id=?",id,project);}
 String upload(String name,String purpose)throws Exception{Path file=upload.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,"medium,value\nwater,7.1\n");String key="/profile/"+name;access.register(key,purpose);return key;}
 void bind(String key,long asset,long child){access.bind(key,asset);var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM da_asset_files WHERE id=14"));row.put("id",child);row.put("asset_id",asset);row.put("url",key);insert("da_asset_files",row);access.indexAsset("FILES",child,asset,key);}
 @Test void unboundUploadIsPrivateToUploader()throws Exception{String key=upload("private.csv","UPLOAD");access.read(key);login(926);assertThrows(AccessDeniedException.class,()->access.read(key));login(927);assertDoesNotThrow(()->access.use(key,Set.of(144L),null));}
 @Test void boundDownloadChecksActualProjectAndCapabilities()throws Exception{String key=upload("bound.csv","UPLOAD");bind(key,A,FILE);access.read(key);login(925);access.read(key);login(926);assertThrows(AccessDeniedException.class,()->access.read(key));}
 @Test void anotherProjectCannotReuseTheSameUploadedUrl()throws Exception{String key=upload("bound.csv","UPLOAD");bind(key,A,FILE);assertThrows(AccessDeniedException.class,()->access.use(key,Set.of(143L),B));assertDoesNotThrow(()->access.use(key,Set.of(144L),A2));}
 @Test void cannotPromoteSomeonesUnboundFileIntoOwnAsset()throws Exception{String key=upload("private.csv","UPLOAD");login(926);assertThrows(AccessDeniedException.class,()->access.bind(key,B));assertEquals(0,jdbc.queryForObject("SELECT bound_flag FROM sys_uploaded_file WHERE resource_hash=?",Integer.class,UploadedFileKey.hash(key)));}
 @Test void archivedReferencesDoNotRestorePersonalAccess()throws Exception{String key=upload("bound.csv","UPLOAD");bind(key,A,FILE);jdbc.update("UPDATE da_asset SET del_flag='1' WHERE id=?",A);assertThrows(AccessDeniedException.class,()->access.read(key));}
 @Test void revokedMembershipBlocksOriginalUploader()throws Exception{String key=upload("bound.csv","UPLOAD");bind(key,A,FILE);jdbc.update("UPDATE att_project_user_rel SET valid_flag='0' WHERE project_id=144 AND user_id=927");jdbc.update("UPDATE att_project SET manager_id=925 WHERE id=144 AND manager_id=927");assertThrows(AccessDeniedException.class,()->access.read(key));}
 @Test void rawTabularDownloadNeedsBothReadAndExport()throws Exception{String key=upload("bound.csv","UPLOAD");bind(key,A,FILE);exports=false;assertThrows(AccessDeniedException.class,()->access.read(key));access.use(key,Set.of(144L),A);exports=true;queries=false;assertThrows(AccessDeniedException.class,()->access.read(key));}
 @Test void safeImagePreviewDoesNotNeedMetadataExport()throws Exception{String key=upload("image.png","UPLOAD");bind(key,A,FILE);exports=false;assertDoesNotThrow(()->access.read(key));}
 @Test void boundFileMayHaveExplicitSameProjectReferences()throws Exception{String key=upload("shared.csv","UPLOAD");bind(key,A,FILE);bind(key,A2,FILE+1);jdbc.update("UPDATE da_asset SET del_flag='1' WHERE id=?",A);access.read(key);jdbc.update("UPDATE da_asset SET del_flag='1' WHERE id=?",A2);assertThrows(AccessDeniedException.class,()->access.read(key));}
 @Test void currentAvatarCanBeViewedButNotBoundToDataAsset()throws Exception{String key=upload("avatar/photo.png","AVATAR");jdbc.update("UPDATE system_user SET avatar=? WHERE user_id=927",key);login(926);access.read(key);login(927);assertThrows(AccessDeniedException.class,()->access.bind(key,A));}
 @Test void certificatePurposeCannotBeReusedOrReadByForeignUser()throws Exception{String key=upload("ca/fixture.key","CA");access.read(key);assertThrows(AccessDeniedException.class,()->access.bind(key,A));login(926);assertThrows(AccessDeniedException.class,()->access.read(key));admin=true;access.read(key);assertThrows(AccessDeniedException.class,()->access.bind(key,A));}
 @Test void internalSymlinkCannotCreateAnotherOwnerOrProject()throws Exception{String key=upload("real.csv","UPLOAD");bind(key,A,FILE);Files.createSymbolicLink(upload.resolve("alias.csv"),upload.resolve("real.csv"));login(926);assertThrows(AccessDeniedException.class,()->access.read("/profile/alias.csv"));assertThrows(AccessDeniedException.class,()->access.bind("/profile/alias.csv",B));assertThrows(IllegalStateException.class,()->access.register("/profile/alias.csv","UPLOAD"));}
 @Test void unsafePathsAndEscapingSymlinksAreRejected()throws Exception{Path outside=Files.createTempFile(upload.getParent(),"outside-",".csv");Files.writeString(outside,"outside");Files.createSymbolicLink(upload.resolve("escape.csv"),outside);for(String key:List.of("/profile/../outside.csv","/profile/escape.csv","https://example.test/profile/a.csv"))assertThrows(IllegalArgumentException.class,()->access.read(key));}
 @Test void literalPercentFilenameHasOneIdentity()throws Exception{String key=upload("literal%2e%2e%2fdata.csv","UPLOAD");access.read(key);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sys_uploaded_file WHERE resource_hash=?",Integer.class,UploadedFileKey.hash(key)));login(926);assertThrows(AccessDeniedException.class,()->access.read(key));}
 @Test void unknownLegacyFileIsNotImplicitlyClaimable()throws Exception{Files.writeString(upload.resolve("unregistered.csv"),"legacy");assertThrows(AccessDeniedException.class,()->access.read("/profile/unregistered.csv"));assertThrows(AccessDeniedException.class,()->access.bind("/profile/unregistered.csv",A));admin=true;access.bind("/profile/unregistered.csv",A);assertEquals(1,jdbc.queryForObject("SELECT bound_flag FROM sys_uploaded_file WHERE resource_hash=?",Integer.class,UploadedFileKey.hash("/profile/unregistered.csv")));}
 @Test void staleReferenceIndexDoesNotAuthorizeAnotherFile()throws Exception{String key=upload("original.csv","UPLOAD");bind(key,A,FILE);String next=upload("next.csv","UPLOAD");jdbc.update("UPDATE da_asset_files SET url=? WHERE id=?",next,FILE);assertThrows(AccessDeniedException.class,()->access.read(key));}
}
