package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.module.att.controller.admin.client.AttClientController;
import tech.qiantong.qdata.module.att.controller.admin.client.vo.*;
import tech.qiantong.qdata.module.att.dal.dataobject.client.*;
import tech.qiantong.qdata.module.att.service.client.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
class ClientProjectScopeIntegrationTest {
    SingleConnectionDataSource fixture;JdbcTemplate jdbc;TransactionTemplate tx;
    ProjectPermissionService permissions;ClientResourceAccess resources;IAttClientService service;AttClientController controller;
    @BeforeEach void setup(){
        String url=System.getenv("QDATA_CLIENT_SCOPE_URL");assertNotNull(url);assertTrue(url.contains("/qdata_client_scope_"));
        fixture=new SingleConnectionDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"),true);jdbc=new JdbcTemplate(fixture);tx=new TransactionTemplate(new DataSourceTransactionManager(fixture));
        permissions=new ProjectPermissionService(jdbc);resources=new ClientResourceAccess(jdbc,permissions);service=mock(IAttClientService.class);controller=new AttClientController();ReflectionTestUtils.setField(controller,"resources",resources);ReflectionTestUtils.setField(controller,"attClientService",service);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();fixture.destroy();}
    void actor(long id){var user=new SysUser();user.setUserId(id);user.setUserName("client-fixture");user.setRoles(List.of());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id,null,user,Set.of("*:*:*")),null,List.of()));}
    void grantRole(long role,String permission){assertTrue(jdbc.update("INSERT IGNORE INTO system_role_menu(role_id,menu_id) SELECT ?,menu_id FROM system_menu WHERE perms=?",role,permission)>0 || permissions.has(143L,permission));}
    void copy(String table,long old,long id,Map<String,Object> changes){var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",old));row.put("id",id);row.putAll(changes);jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
    AttClientSaveReqVO app(Long id,Long project){var r=new AttClientSaveReqVO();r.setId(id);r.setProjectId(project);r.setName("fixture application");r.setType("1");return r;}
    AttClientApiRelSaveReqVO relation(Long id,Long client,Long api){var r=new AttClientApiRelSaveReqVO();r.setId(id);r.setClientId(client);r.setApiId(api);r.setPvFlag("1");r.setStatus("1");return r;}
    @Test void listUsesMembershipAndTargetProjectPermissions(){actor(925);assertEquals(List.of(10L,11L),resources.clientIds("read"));actor(926);assertTrue(permissions.has(143L,"att:client:query"));assertEquals(List.of(900001L),resources.clientIds("read"));}
    @Test void foreignDetailsAndSecretsFailBeforeDataService(){actor(926);assertThrows(AccessDeniedException.class,()->controller.getInfo(10L));assertThrows(AccessDeniedException.class,()->controller.secret(10L,new org.springframework.mock.web.MockHttpServletResponse()));assertThrows(AccessDeniedException.class,()->controller.resetSecret(10L));verifyNoInteractions(service);}
    @Test void readonlyOwnApplicationCannotRevealOrResetSecrets(){actor(926);assertDoesNotThrow(()->resources.client(900001L,"query"));assertThrows(AccessDeniedException.class,()->controller.secret(900001L,new org.springframework.mock.web.MockHttpServletResponse()));assertThrows(AccessDeniedException.class,()->controller.resetSecret(900001L));verifyNoInteractions(service);}
    @Test void allOrdinaryViewsOmitSecretEvenForEditors(){actor(925);var view=new AttClientRespVO();view.setId(10L);view.setProjectId(1L);view.setSecret("fixture-secret");resources.present(view);assertNull(view.getSecret());assertTrue(view.getCanEdit());assertNotNull(view.getProjectName());}
    @Test void explicitSecretViewRequiresEditorAndDisablesCaching(){actor(925);var value=new AttClientDO();value.setSecret("fixture-secret");when(service.getAttClientById(10L)).thenReturn(value);var response=new org.springframework.mock.web.MockHttpServletResponse();assertTrue("fixture-secret".equals(controller.secret(10L,response).getData()));assertEquals("no-store",response.getHeader("Cache-Control"));}
    @Test void publicFlagDoesNotGrantManagementAccess(){tx.executeWithoutResult(status->{jdbc.update("UPDATE att_client SET public_flag='1' WHERE id=10");actor(926);assertThrows(AccessDeniedException.class,()->resources.client(10L,"read"));status.setRollbackOnly();});}
    @Test void broadCachedPermissionsCannotCreateWithinReadonlyProject(){actor(926);assertThrows(AccessDeniedException.class,()->resources.prepare(app(null,143L),"add",false));}
    @Test void newApplicationUsesTheOnlyEligibleProjectAndIgnoresForgedCreator(){actor(925);var request=app(null,null);request.setCreatorId(926L);resources.prepare(request,"add",false);assertEquals(1L,request.getProjectId());assertNull(request.getCreatorId());assertEquals("0",request.getPublicFlag());}
    @Test void existingApplicationCannotChangeProject(){actor(925);assertThrows(AccessDeniedException.class,()->resources.prepare(app(10L,143L),"edit",true));}
    @Test void referencedApplicationsCannotBeDeleted(){actor(925);assertThrows(IllegalArgumentException.class,()->resources.removal(10L));}
    @Test void duplicateAuthorizationsAreRejected(){actor(925);assertThrows(IllegalArgumentException.class,()->resources.grant(relation(null,10L,14L),false));}
    @Test void relationCannotBeMovedToAnotherApplicationOrApi(){actor(925);assertThrows(AccessDeniedException.class,()->resources.grant(relation(10L,11L,14L),true));assertThrows(AccessDeniedException.class,()->resources.grant(relation(10L,10L,15L),true));}
    @Test void dateBoundariesAreValidatedWithoutChangingExistingGrant(){actor(925);var value=relation(10L,10L,14L);value.setPvFlag("0");value.setStartTime(new Date(2000));value.setEndTime(new Date(1000));assertThrows(IllegalArgumentException.class,()->resources.grant(value,true));}
    @Test void applicationWriterCannotGrantAnotherProjectsApi(){tx.executeWithoutResult(status->{actor(926);grantRole(17,"att:client:edit");assertThrows(AccessDeniedException.class,()->resources.grant(relation(null,900001L,14L),false));assertTrue(resources.grantOptions(900001L).isEmpty());status.setRollbackOnly();});}
    @Test void owningApiIsInsufficientWithoutApplicationAuthority(){actor(925);assertThrows(AccessDeniedException.class,()->resources.grant(relation(null,900001L,14L),false));}
    @Test void explicitCrossProjectGrantWorksForAnOperatorAuthorizedOnBothSides(){tx.executeWithoutResult(status->{
        actor(926);grantRole(17,"att:client:edit");jdbc.update("INSERT INTO att_project_user_rel(project_id,user_id,valid_flag,del_flag) VALUES(143,925,'1','0')");jdbc.update("INSERT INTO system_user_role(user_id,role_id) VALUES(925,17)");actor(925);
        assertDoesNotThrow(()->resources.grant(relation(null,900001L,14L),false));assertEquals(4,resources.grantOptions(900001L).size());status.setRollbackOnly();
    });}
    @Test void applicationWriterCanRevokeWithoutForeignApiWritePermission(){tx.executeWithoutResult(status->{actor(926);grantRole(17,"att:client:edit");copy("att_client_api_rel",10,900002,Map.of("client_id",900001L));assertDoesNotThrow(()->resources.revoke(900002L));var view=new AttClientApiRelRespVO();view.setClientId(900001L);view.setApiId(14L);resources.present(view);assertFalse(view.getCanEdit());assertTrue(view.getCanRevoke());status.setRollbackOnly();});}
    @Test void unrelatedUserCannotRevokeOrRetargetAuthorization(){actor(926);assertThrows(AccessDeniedException.class,()->resources.revoke(10L));assertThrows(AccessDeniedException.class,()->resources.grant(relation(10L,10L,14L),true));}
    @Test void disabledProjectStopsApplicationManagement(){tx.executeWithoutResult(status->{jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=143");actor(926);assertThrows(AccessDeniedException.class,()->resources.client(900001L,"read"));status.setRollbackOnly();});}
    @Test void internalOauthLookupDoesNotDependOnPlatformLoginAndRejectsDisabledProject(){
        var internal=new tech.qiantong.qdata.module.att.api.client.ClientApiImpl();ReflectionTestUtils.setField(internal,"jdbc",jdbc);ReflectionTestUtils.setField(internal,"clientService",service);var client=new AttClientDO();client.setId(10L);client.setProjectId(1L);when(service.getAttClientById(10L)).thenReturn(client);SecurityContextHolder.clearContext();assertNotNull(internal.getClient(10L));tx.executeWithoutResult(status->{jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=1");assertNull(internal.getClient(10L));status.setRollbackOnly();});
    }
    @Test void legacyOwnershipRemainsPrivateWithoutGuessingFromGrants(){tx.executeWithoutResult(status->{jdbc.update("UPDATE att_client SET project_id=NULL,creator_id=2 WHERE id=10");actor(925);assertFalse(resources.clientIds("read").contains(10L));assertThrows(AccessDeniedException.class,()->resources.client(10L,"read"));actor(2);assertDoesNotThrow(()->resources.client(10L,"read"));status.setRollbackOnly();});}
    @Test void realMapperPaginationAndRelationSortingUseServerScope(){
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setEnvironment(new org.apache.ibatis.mapping.Environment("client-scope",new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),fixture));config.setMapUnderscoreToCamelCase(true);com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());var pagination=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));config.addInterceptor(pagination);config.addMapper(tech.qiantong.qdata.module.att.dal.mapper.client.AttClientMapper.class);config.addMapper(tech.qiantong.qdata.module.att.dal.mapper.client.AttClientApiRelMapper.class);
        var sessions=new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(config);try(var session=sessions.openSession()){
            var mapper=session.getMapper(tech.qiantong.qdata.module.att.dal.mapper.client.AttClientMapper.class);var request=new AttClientPageReqVO();request.setPageSize(1);actor(925);request.setAccessibleClientIds(resources.clientIds("list"));assertEquals(2,mapper.selectPage(request).getTotal());assertEquals(1,mapper.selectPage(request).getRows().size());actor(926);request.setAccessibleClientIds(resources.clientIds("list"));assertEquals(1,mapper.selectPage(request).getTotal());
            var relMapper=session.getMapper(tech.qiantong.qdata.module.att.dal.mapper.client.AttClientApiRelMapper.class);var rel=new AttClientApiRelPageReqVO();rel.setAccessibleClientIds(List.of(10L));rel.setOrderByColumn("id,(SELECT secret FROM att_client LIMIT 1)");assertEquals(4,relMapper.selectPage(rel).getTotal());rel.setAccessibleClientIds(List.of(900001L));assertEquals(0,relMapper.selectPage(rel).getTotal());
        }
    }
    public static class Endpoint {@tech.qiantong.qdata.module.ds.annotation.DsCheckClientToken public void invoke(){}}
    private int runtimeStatus() throws Exception {
        var interceptor=new tech.qiantong.qdata.module.ds.annotation.handler.DsCheckClientTokenInterceptor();ReflectionTestUtils.setField(interceptor,"jdbc",jdbc);ReflectionTestUtils.setField(interceptor,"audit",mock(tech.qiantong.qdata.module.ds.handler.ServiceCallAudit.class));var request=new org.springframework.mock.web.MockHttpServletRequest();request.addHeader("X-Client-Token","fixture-only");var response=new org.springframework.mock.web.MockHttpServletResponse();var token=mock(cn.dev33.satoken.oauth2.data.model.ClientTokenModel.class);when(token.getClientId()).thenReturn("10");var api=new tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO();api.setId(14L);
        try(var oauth=mockStatic(cn.dev33.satoken.oauth2.template.SaOAuth2Util.class);var mapping=mockStatic(tech.qiantong.qdata.module.ds.handler.MappingHandlerMapping.class)) {
            oauth.when(()->cn.dev33.satoken.oauth2.template.SaOAuth2Util.checkClientToken("fixture-only")).thenReturn(token);mapping.when(()->tech.qiantong.qdata.module.ds.handler.MappingHandlerMapping.getMappingApiInfo(request)).thenReturn(api);
            interceptor.preHandle(request,response,new org.springframework.web.method.HandlerMethod(new Endpoint(),"invoke"));return response.getStatus();
        }
    }
    @Test void issuedTokenIsRejectedWhenApplicationProjectIsDisabled() throws Exception {
        assertEquals(200,runtimeStatus());tx.executeWithoutResult(status->{jdbc.update("UPDATE att_client SET project_id=143 WHERE id=10");jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=143");assertEquals(403,assertDoesNotThrow(()->runtimeStatus()));status.setRollbackOnly();});
    }
    @Test void issuedTokenIsRejectedWhenApiProjectIsDisabled() throws Exception {
        assertEquals(200,runtimeStatus());tx.executeWithoutResult(status->{jdbc.update("UPDATE ds_api SET project_id=143 WHERE id=14");jdbc.update("UPDATE att_project SET valid_flag='0' WHERE id=143");assertEquals(403,assertDoesNotThrow(()->runtimeStatus()));status.setRollbackOnly();});
    }

}
