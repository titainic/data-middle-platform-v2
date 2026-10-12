package tech.qiantong.qdata.acceptance;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.security.web.service.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class SharedCatalogPermissionIntegrationTest {
 SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;ProjectPermissionService grants;PermissionService permissions;
 static final long USER=990801,ROLE=990802;long menu;static final String WRITE="dm:dataLayer:add";
 @BeforeEach void setup(){
  org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
  String url=System.getenv("QDATA_UPLOAD_SCOPE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_upload_scope_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=manager.getTransaction(new DefaultTransactionDefinition());
  var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM system_role WHERE role_id=19"));row.put("role_id",ROLE);row.put("role_key","shared_fixture");row.put("role_name","shared fixture");row.put("project_id",144L);row.put("status","0");row.put("del_flag","0");insert("system_role",row);
  jdbc.update("INSERT INTO system_user_role(user_id,role_id) VALUES(?,?)",USER,ROLE);menu=jdbc.queryForObject("SELECT MIN(menu_id) FROM system_menu WHERE perms=?",Long.class,WRITE);jdbc.update("INSERT INTO system_role_menu(role_id,menu_id,project_id) VALUES(?,?,144)",ROLE,menu);
  grants=new ProjectPermissionService(jdbc);permissions=new PermissionService();ReflectionTestUtils.setField(permissions,"projectPermissions",grants);login(USER,Set.of(WRITE,"dpp:developTask:add","*:*:*"));
 }
 @AfterEach void close(){if(tx!=null&&!tx.isCompleted())manager.rollback(tx);if(source!=null)source.destroy();SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
 void login(long id,Set<String> values){var user=new SysUser();user.setUserId(id);user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,values),null,List.of()));}
 void insert(String table,Map<String,Object> row){jdbc.update("INSERT INTO "+table+"("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
 void global(){jdbc.update("UPDATE system_role SET project_id=0 WHERE role_id=?",ROLE);}
 @Test void projectGrantCannotWriteSharedLayerEvenWithAggregatedWildcard(){assertTrue(grants.has(144L,WRITE));assertFalse(permissions.hasPermi(WRITE));assertFalse(permissions.hasGlobalPermi(WRITE));}
 @Test void explicitGlobalCapabilityWorksWithoutAdministrator(){global();assertFalse(grants.isAdministrator());assertTrue(permissions.hasPermi(WRITE));}
 @Test void revocationTakesEffectWithoutWaitingForSessionExpiry(){global();assertTrue(permissions.hasPermi(WRITE));jdbc.update("UPDATE system_role SET status='1' WHERE role_id=?",ROLE);assertFalse(permissions.hasPermi(WRITE));}
 @Test void logicallyRemovedRoleIsDenied(){global();jdbc.update("UPDATE system_role SET del_flag='1' WHERE role_id=?",ROLE);assertFalse(permissions.hasPermi(WRITE));}
 @Test void inactiveMenuCannotAuthorizeMutation(){global();jdbc.update("UPDATE system_menu SET status='1' WHERE menu_id=?",menu);assertFalse(permissions.hasPermi(WRITE));}
 @Test void projectAdministratorNameIsNotGlobalAuthority(){jdbc.update("UPDATE system_role SET role_key='admin' WHERE role_id=?",ROLE);assertFalse(permissions.hasPermi(WRITE));}
 @Test void roleTemplateDoesNotAuthorizeSharedMutation(){jdbc.update("UPDATE system_role SET project_id=-1 WHERE role_id=?",ROLE);assertFalse(permissions.hasPermi(WRITE));}
 @Test void legacyNullProjectMeansGlobalRole(){jdbc.update("UPDATE system_role SET project_id=NULL WHERE role_id=?",ROLE);assertTrue(permissions.hasPermi(WRITE));}
 @Test void anyPermissionUsesTheSameGlobalBoundary(){assertFalse(permissions.hasAnyPermi(WRITE+",dm:dataLayer:edit"));assertTrue(permissions.hasAnyPermi(WRITE+",dpp:developTask:add"));}
 @Test void loginPermissionsHideSharedWritesButKeepProjectOperations(){var result=grants.filterSharedCatalogMutations(USER,Set.of(WRITE,"dm:dataLayer:list","att:taskCat:add","att:dataDevCat:edit"));assertFalse(result.contains(WRITE));assertTrue(result.containsAll(Set.of("dm:dataLayer:list","att:taskCat:add","att:dataDevCat:edit")));}
 @Test void projectWildcardDoesNotKeepAnUnrestrictedClientWildcard(){var result=grants.filterSharedCatalogMutations(USER,Set.of("*:*:*"));assertFalse(result.contains("*:*:*"));assertFalse(result.contains(WRITE));assertTrue(result.contains("dm:dataLayer:list"));}
 @Test void globalGrantKeepsItsClientAction(){global();assertTrue(grants.filterSharedCatalogMutations(USER,Set.of(WRITE)).contains(WRITE));}
 @Test void globalWildcardSupportsSharedWrites(){global();jdbc.update("UPDATE system_menu SET perms='*:*:*' WHERE menu_id=?",menu);assertTrue(permissions.hasPermi(WRITE));assertTrue(grants.filterSharedCatalogMutations(USER,Set.of("*:*:*")).contains("*:*:*"));}
 @Test void rootStillHasGlobalAuthority(){login(1,Set.of("*:*:*"));assertTrue(permissions.hasPermi(WRITE));assertTrue(permissions.hasGlobalPermi("mc:domain:add"));}
 @Test void unrelatedProjectPermissionKeepsExistingAuthorization(){assertTrue(permissions.hasPermi("dpp:developTask:add"));assertTrue(SharedCatalogPermissions.mutation("att:theme:theme:edit"));assertFalse(SharedCatalogPermissions.mutation("att:taskCat:add"));}
 @Test void legacyGlobalThemeMutationRetainsExistingMeaning(){global();jdbc.update("UPDATE system_menu SET perms='att:theme:theme:edit' WHERE menu_id=?",menu);assertTrue(permissions.hasPermi("att:theme:edit"));assertTrue(permissions.hasPermi("att:theme:theme:edit"));}
 @Test void legacyProjectThemeMutationCannotGainGlobalScope(){jdbc.update("UPDATE system_menu SET perms='att:theme:theme:edit' WHERE menu_id=?",menu);var result=grants.filterSharedCatalogMutations(USER,Set.of("att:theme:theme:edit"));assertFalse(result.contains("att:theme:edit"));assertFalse(result.contains("att:theme:theme:edit"));assertFalse(permissions.hasPermi("att:theme:edit"));}
 @Test void loginReturnsLegacyAndCanonicalThemeNamesForGlobalRoles(){global();jdbc.update("UPDATE system_menu SET perms='att:theme:theme:edit' WHERE menu_id=?",menu);var result=grants.filterSharedCatalogMutations(USER,Set.of("att:theme:theme:edit"));assertTrue(result.containsAll(Set.of("att:theme:edit","att:theme:theme:edit")));}
 @Test void queryAuthorityAllowsLegacyThemeListWithoutGrantingWrite(){login(USER,Set.of("att:theme:query"));assertTrue(permissions.hasAnyPermi("att:theme:list,att:theme:query"));assertFalse(permissions.hasPermi("att:theme:edit"));}
}
