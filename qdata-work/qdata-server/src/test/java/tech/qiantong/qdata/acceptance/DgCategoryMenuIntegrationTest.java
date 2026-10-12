package tech.qiantong.qdata.acceptance;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class DgCategoryMenuIntegrationTest {
 SingleConnectionDataSource source;JdbcTemplate jdbc;DataSourceTransactionManager manager;TransactionStatus tx;ProjectPermissionService permissions;long menu;
 @BeforeEach void setup(){String url=System.getenv("QDATA_DG_CATALOG_URL");assertNotNull(url);assertTrue(url.contains("/qdata_dg_catalog_"));source=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);tx=manager.getTransaction(new DefaultTransactionDefinition());permissions=new ProjectPermissionService(jdbc);menu=jdbc.queryForObject("SELECT menu_id FROM system_menu WHERE parent_id=2350 AND path='dataCategory'",Long.class);login(925);}
 @AfterEach void close(){if(!tx.isCompleted())manager.rollback(tx);source.destroy();SecurityContextHolder.clearContext();}
 void login(long id){var user=new SysUser();user.setUserId(id);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of()),null,List.of()));}
 @Test void disabledMenuIsInvisibleUntilTheGuardedReleaseIsReady(){assertFalse(permissions.hasGlobal("dg:dataCategory:list"));jdbc.update("UPDATE system_menu SET status='0' WHERE menu_id=?",menu);assertTrue(permissions.hasGlobal("dg:dataCategory:list"));}
 @Test void globalGuestCanReadButCannotChangeClassification(){jdbc.update("UPDATE system_menu SET status='0' WHERE menu_id=?",menu);assertTrue(permissions.hasGlobal("dg:dataCategory:list"));for(String verb:List.of("add","edit","remove"))assertFalse(permissions.hasGlobal("dg:dataCategory:"+verb));assertFalse(permissions.hasGlobal("dg:datalevel:add"));}
 @Test void ProjectOnlyUsersDoNotInheritTheGlobalMenu(){jdbc.update("UPDATE system_menu SET status='0' WHERE menu_id=?",menu);for(long user:List.of(926L,927L)){login(user);assertFalse(permissions.hasGlobal("dg:dataCategory:list"));assertFalse(permissions.hasGlobal("dg:dataCategory:add"));}}
 @Test void RevokingTheRoleMenuImmediatelyRemovesCatalogueRead(){jdbc.update("UPDATE system_menu SET status='0' WHERE menu_id=?",menu);assertTrue(permissions.hasGlobal("dg:dataCategory:list"));jdbc.update("DELETE FROM system_role_menu WHERE role_id=7 AND menu_id=?",menu);assertFalse(permissions.hasGlobal("dg:dataCategory:list"));}
}
