package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.core.domain.entity.*;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import tech.qiantong.qdata.module.att.service.project.*;
import tech.qiantong.qdata.module.att.controller.admin.project.*;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated real MySQL; an intentionally broad cached permission set must not bypass target-project rules. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class ProjectAuthorityIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;TransactionTemplate tx;ProjectPermissionService permissions;ProjectAuthority authority;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_PROJECT_AUTH_URL");assertNotNull(url);assertTrue(url.contains("/qdata_project_authority_"));
        fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);tx=new TransactionTemplate(new DataSourceTransactionManager(fixture));permissions=new ProjectPermissionService(jdbc);authority=new ProjectAuthority(jdbc,permissions);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();fixture.destroy();}
    void actor(long id){
        var user=new SysUser();user.setUserId(id);user.setUserName("authority-fixture");user.setNickName("authority-fixture");user.setRoles(List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));
    }
    void grant(long role,String permission){
        Long menu=jdbc.queryForObject("SELECT MIN(menu_id) FROM system_menu WHERE perms=?",Long.class,permission);assertNotNull(menu,permission);
        jdbc.update("INSERT IGNORE INTO system_role_menu(role_id,menu_id) VALUES(?,?)",role,menu);
    }
    @Test void projectListsContainOnlyMembershipsAndOwnedProjects(){actor(925);assertEquals(List.of(1L),authority.projects());actor(926);assertEquals(List.of(143L),authority.projects());}
    @Test void queryPermissionCannotChangeEvenItsOwnProjectStatus(){
        actor(926);assertTrue(permissions.has(143L,"att:project:query"));assertFalse(permissions.has(143L,"att:project:edit"));
        var service=mock(IAttProjectService.class);var controller=new AttProjectController();ReflectionTestUtils.setField(controller,"authority",authority);ReflectionTestUtils.setField(controller,"attProjectService",service);
        assertThrows(AccessDeniedException.class,()->controller.editProjectStatus(143L,0L));assertThrows(AccessDeniedException.class,()->controller.editProjectStatus(1L,0L));verifyNoInteractions(service);assertFalse(authority.canEdit(143L));
    }
    @Test void aGrantedEditorStillCannotChangeAnotherProject(){
        tx.executeWithoutResult(status->{grant(17L,"att:project:edit");actor(926);assertTrue(permissions.has(143L,"att:project:edit"));assertTrue(authority.canEdit(143L));assertThrows(AccessDeniedException.class,()->authority.manage(1L,"att:project:edit"));status.setRollbackOnly();});
    }
    @Test void aProjectAdministratorCanUseTheOwnedStatusEndpoint(){
        actor(925);assertTrue(authority.canEdit(1L));var service=mock(IAttProjectService.class);when(service.editProjectStatus(1L,1L)).thenReturn(true);var controller=new AttProjectController();ReflectionTestUtils.setField(controller,"authority",authority);ReflectionTestUtils.setField(controller,"attProjectService",service);assertEquals(200,controller.editProjectStatus(1L,1L).get("code"));verify(service).editProjectStatus(1L,1L);
    }
    @Test void projectCodeCannotRedirectAnOwnedEditToAnotherSchedulerProject(){actor(925);var request=new AttProjectSaveReqVO();request.setId(1L);request.setCode(jdbc.queryForObject("SELECT code FROM att_project WHERE id=143",String.class));assertThrows(AccessDeniedException.class,()->authority.edit(request));}
    @Test void inactiveOwnedProjectsCanBeManagedWithoutEnablingDataAccess(){
        tx.executeWithoutResult(status->{jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=1");actor(925);assertTrue(authority.projects().contains(1L));assertDoesNotThrow(()->authority.manage(1L,"att:project:edit"));actor(926);assertFalse(authority.projects().contains(1L));status.setRollbackOnly();});
    }
    @Test void projectMembershipCannotBeReparentedUsingRequestFields(){
        actor(925);Long member=jdbc.queryForObject("SELECT MIN(id) FROM att_project_user_rel WHERE project_id=1 AND user_id=925 AND del_flag='0'",Long.class);assertNotNull(member);
        var request=new AttProjectUserRelSaveReqVO();request.setId(member);request.setProjectId(143L);request.setUserId(925L);assertThrows(AccessDeniedException.class,()->authority.memberRequest(request,true,false));request.setProjectId(1L);request.setUserId(926L);assertThrows(AccessDeniedException.class,()->authority.memberRequest(request,true,false));
    }
    @Test void foreignRolesAndGlobalRolesCannotBeGrantedThroughProjectMembership(){
        actor(925);var request=new AttProjectUserRelSaveReqVO();request.setProjectId(1L);request.setUserIdList(List.of(926L));
        request.setRoleIdList(List.of(17L));assertThrows(AccessDeniedException.class,()->authority.memberRequest(request,false,true));request.setRoleIdList(List.of(1L));assertThrows(AccessDeniedException.class,()->authority.memberRequest(request,false,true));
        request.setRoleIdList(List.of(10L));assertDoesNotThrow(()->authority.memberRequest(request,false,true));
    }
    @Test void rolesFromAnotherProjectDoNotGrantTargetProjectCapabilities(){
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO att_project_user_rel(project_id,user_id,valid_flag,del_flag) VALUES(143,925,'1','0')");jdbc.update("INSERT IGNORE INTO system_user_role(user_id,role_id) VALUES(925,17)");jdbc.update("DELETE FROM system_user_role WHERE user_id=925 AND role_id=7");grant(10L,"att:project:edit");
            actor(925);assertTrue(authority.projects().containsAll(List.of(1L,143L)));assertTrue(permissions.has(1L,"att:project:edit"));assertFalse(permissions.has(143L,"att:project:edit"));assertFalse(authority.canEdit(143L));status.setRollbackOnly();
        });
    }
    @Test void reservedAdminNamesAndRoleWildcardsAreRejected(){
        actor(925);assertTrue(permissions.has(1L,"att:project:role:add"));
        for(String key:List.of("admin","ADMIN","*","adm*")){var role=new SysRole();role.setProjectId(1L);role.setRoleKey(key);role.setMenuIds(new Long[0]);assertThrows(AccessDeniedException.class,()->authority.roleSave(role,true));}
    }
    @Test void projectRolesCannotDelegateSystemPrivileges(){
        actor(925);Long menu=jdbc.queryForObject("SELECT MIN(menu_id) FROM system_menu WHERE perms='system:user:add'",Long.class);assertNotNull(menu);var role=new SysRole();role.setProjectId(1L);role.setRoleKey("fixture_reader");role.setMenuIds(new Long[]{menu});assertThrows(AccessDeniedException.class,()->authority.roleSave(role,true));
        role.setMenuIds(new Long[]{jdbc.queryForObject("SELECT MIN(menu_id) FROM system_menu WHERE perms='dpp:developTask:list'",Long.class)});assertDoesNotThrow(()->authority.roleSave(role,true));
    }
    @Test void projectAliasesCannotReadOrModifyGlobalRoles(){actor(925);assertThrows(AccessDeniedException.class,()->authority.role(1L,false,"att:project:role:query"));assertThrows(AccessDeniedException.class,()->authority.role(17L,true,"att:project:role:edit"));assertDoesNotThrow(()->authority.role(10L,true,"att:project:role:edit"));}
    @Test void roleAssignmentRequiresTheUserToAlreadyBelongToThatProject(){actor(925);assertThrows(AccessDeniedException.class,()->authority.roleUsers(10L,new Long[]{926L},false));assertDoesNotThrow(()->authority.roleUsers(10L,new Long[]{925L},false));}
    @Test void revokingTheLastProjectAdministratorIsRejected(){
        actor(925);var admins=jdbc.queryForList("SELECT DISTINCT ur.user_id FROM system_user_role ur JOIN system_role r ON r.role_id=ur.role_id JOIN att_project_user_rel m ON m.user_id=ur.user_id AND m.project_id=r.project_id AND m.del_flag='0' WHERE r.project_id=1 AND r.role_key='gly'",Long.class);assertFalse(admins.isEmpty());assertThrows(AccessDeniedException.class,()->authority.roleUsers(10L,admins.toArray(Long[]::new),true));
    }
    @Test void projectListIsFilteredBeforeRealMysqlPagination() throws Exception {
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("project-authority",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),fixture));config.setMapUnderscoreToCamelCase(true);
        config.getTypeAliasRegistry().registerAlias("AttProjectDO",tech.qiantong.qdata.module.att.dal.dataobject.project.AttProjectDO.class);
        var pagination=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));config.addInterceptor(pagination);
        try(var xml=getClass().getResourceAsStream("/mapper/att/project/AttProjectMapper.xml")){assertNotNull(xml);new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml,config,"mapper/att/project/AttProjectMapper.xml",config.getSqlFragments()).parse();}
        var sessions=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);
        try(var session=sessions.openSession()){
            var mapper=session.getMapper(tech.qiantong.qdata.module.att.dal.mapper.project.AttProjectMapper.class);var request=new AttProjectPageReqVO();actor(926);request.setAccessibleProjectIds(authority.projects());var page=mapper.selectAttProjectListByPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1,1),request);assertEquals(1,page.getTotal());assertEquals(143L,page.getRecords().get(0).getId());request.setAccessibleProjectIds(List.of());assertEquals(0,mapper.selectAttProjectListByPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1,1),request).getTotal());
        }
    }
}
