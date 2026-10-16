package tech.qiantong.qdata.module.system.controller.admin.system;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.domain.entity.SysDept;
import tech.qiantong.qdata.common.core.domain.entity.SysRole;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import tech.qiantong.qdata.module.system.mapper.SysUserMapper;
import tech.qiantong.qdata.module.system.service.ISysPostService;
import tech.qiantong.qdata.module.system.service.ISysRoleService;
import tech.qiantong.qdata.module.system.service.impl.SysUserServiceImpl;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real mapper and service with an isolated H2/MySQL-mode fixture. No external database is used.
 * Data-scope tests use a scoped-list service substitute; authentication and method-security filters are not started.
 */
class SysUserDetailProjectRegressionTest {
    private SqlSession session;
    private SysUserMapper mapper;
    private SysUserServiceImpl users;
    private ISysRoleService roles;
    private ISysPostService posts;
    private SysUserController controller;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:user_detail_" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=SYSTEM_USER", "sa", "");
        Configuration configuration = new Configuration(new Environment(
                "offline-user-detail", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("SysUser", SysUser.class);
        configuration.getTypeAliasRegistry().registerAlias("SysRole", SysRole.class);
        configuration.getTypeAliasRegistry().registerAlias("SysDept", SysDept.class);
        String resource = "mapper/system/SysUserMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        Connection connection = session.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE system_user (user_id BIGINT PRIMARY KEY, dept_id BIGINT, "
                    + "user_name VARCHAR(64), nick_name VARCHAR(64), email VARCHAR(64), avatar VARCHAR(64), "
                    + "phonenumber VARCHAR(32), password VARCHAR(128), sex VARCHAR(1), status VARCHAR(1), "
                    + "del_flag VARCHAR(1), login_ip VARCHAR(64), login_date TIMESTAMP, "
                    + "create_by VARCHAR(64), create_time TIMESTAMP, remark VARCHAR(128))");
            statement.execute("CREATE TABLE system_dept (dept_id BIGINT PRIMARY KEY, parent_id BIGINT, "
                    + "ancestors VARCHAR(128), dept_name VARCHAR(64), order_num INT, leader VARCHAR(64), status VARCHAR(1))");
            statement.execute("CREATE TABLE system_role (role_id BIGINT PRIMARY KEY, role_name VARCHAR(64), "
                    + "role_key VARCHAR(64), role_sort INT, data_scope VARCHAR(1), status VARCHAR(1), project_id BIGINT)");
            statement.execute("CREATE TABLE system_user_role (user_id BIGINT, role_id BIGINT)");
            statement.execute("INSERT INTO system_dept VALUES (100,0,'0','平台管理',0,'admin','0')");
            statement.execute("INSERT INTO system_role VALUES "
                    + "(4,'legacy','legacy',1,'1','0',NULL),"
                    + "(5,'platform','platform',2,'1','0',0),"
                    + "(6,'project','project',3,'1','0',7)");
            statement.execute("INSERT INTO system_user (user_id,dept_id,user_name,nick_name,status,del_flag) VALUES "
                    + "(2,100,'sentinel_bin39_ro','BIN39只读验证','0','0'),"
                    + "(3,100,'sentinel_bin39_ui','BIN39交互验证','0','0'),"
                    + "(4,100,'no_roles','无角色','0','0'),"
                    + "(5,100,'project_only','项目角色','0','0'),"
                    + "(6,100,'mixed_roles','混合角色','0','0'),"
                    + "(7,100,'deleted','已删除','0','2'),"
                    + "(8,100,'disabled','停用','1','0')");
            statement.execute("INSERT INTO system_user_role VALUES "
                    + "(2,4),(3,5),(5,6),(6,4),(6,5),(6,6),(7,5)");
        }
        mapper = spy(session.getMapper(SysUserMapper.class));
        users = spy(new SysUserServiceImpl());
        ReflectionTestUtils.setField(users, "userMapper", mapper);
        doNothing().when(users).checkUserDataScope(any());
        roles = mock(ISysRoleService.class);
        posts = mock(ISysPostService.class);
        SysRole platformRole = new SysRole(5L);
        platformRole.setProjectId(0L);
        when(roles.selectRoleList(any(SysRole.class))).thenReturn(List.of(platformRole));
        when(posts.selectPostAll()).thenReturn(List.of());
        when(posts.selectPostListByUserId(any())).thenReturn(List.of(9L));
        controller = new SysUserController();
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "roleService", roles);
        ReflectionTestUtils.setField(controller, "postService", posts);
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
    }

    @Test
    void nullProjectRoleDoesNotHideUserOrBecomeAPlatformGrant() {
        AjaxResult result = controller.getInfo(2L);
        SysUser user = (SysUser) result.get(AjaxResult.DATA_TAG);
        assertEquals(200, result.get("code"));
        assertEquals(2L, user.getUserId());
        assertEquals(100L, user.getDeptId());
        assertEquals("平台管理", user.getDept().getDeptName());
        assertTrue(user.getRoles().isEmpty());
        assertEquals(List.of(), result.get("roleIds"));
        assertEquals(List.of(9L), result.get("postIds"));
        var order = inOrder(users, mapper);
        order.verify(users).checkUserDataScope(2L);
        order.verify(users).getByUserIdAndProjectId(2L, 0L);
        order.verify(mapper).selectUserByUserIdAndProjectId(2L, 0L);
    }

    @Test
    void platformRoleUserKeepsItsRoleAndDepartment() {
        AjaxResult result = controller.getInfo(3L);
        SysUser user = (SysUser) result.get(AjaxResult.DATA_TAG);
        assertEquals(200, result.get("code"));
        assertEquals(3L, user.getUserId());
        assertEquals(100L, user.getDeptId());
        assertEquals(List.of(5L), result.get("roleIds"));
        assertEquals(0L, user.getRoles().getFirst().getProjectId());
    }

    @Test
    void userWithoutRolesStillHasEditableDetails() {
        AjaxResult result = controller.getInfo(4L);
        assertEquals(200, result.get("code"));
        assertEquals(4L, ((SysUser) result.get(AjaxResult.DATA_TAG)).getUserId());
        assertEquals(List.of(), result.get("roleIds"));
    }

    @Test
    void projectOnlyUserDoesNotExposeAnotherProjectsRoles() {
        AjaxResult result = controller.getInfo(5L);
        assertEquals(200, result.get("code"));
        assertTrue(((SysUser) result.get(AjaxResult.DATA_TAG)).getRoles().isEmpty());
        assertEquals(List.of(), result.get("roleIds"));
    }

    @Test
    void mixedRoleRowsMapToOneUserWithOnlyTheRequestedProjectRoles() {
        SysUser platform = users.getByUserIdAndProjectId(6L, 0L);
        assertEquals(List.of(5L), platform.getRoles().stream().map(SysRole::getRoleId).toList());
        SysUser project = users.getByUserIdAndProjectId(6L, 7L);
        assertEquals(List.of(6L), project.getRoles().stream().map(SysRole::getRoleId).toList());
        assertEquals(7L, project.getRoles().getFirst().getProjectId());
    }

    @Test
    void anotherProjectFilterNeverReturnsTheNullProjectRole() {
        SysUser user = users.getByUserIdAndProjectId(2L, 7L);
        assertEquals(2L, user.getUserId());
        assertTrue(user.getRoles().isEmpty());
    }

    @Test
    void deletedUserIsUnavailableEvenWhenTheDataScopeCheckPasses() {
        assertUnavailable(7L);
    }

    @Test
    void nonexistentUserReturnsControlledBusinessErrorWithoutInternalDetails() {
        assertUnavailable(999L);
    }

    private void assertUnavailable(long userId) {
        try (var messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.message("user.not.exists")).thenReturn("用户不存在");
            AjaxResult result = controller.getInfo(userId);
            assertEquals(500, result.get("code"));
            assertEquals("用户不存在", result.get("msg"));
            assertFalse(result.containsKey(AjaxResult.DATA_TAG));
            assertFalse(result.containsKey("roleIds"));
            verify(posts, never()).selectPostListByUserId(any());
        }
    }

    @Test
    void deniedDataScopeStopsBeforeReadingDetailsOrReferenceData() {
        var denied = new ServiceException("No permission to access user data!");
        doThrow(denied).when(users).checkUserDataScope(2L);
        assertSame(denied, assertThrows(ServiceException.class, () -> controller.getInfo(2L)));
        verifyNoInteractions(mapper, roles, posts);
    }

    @Test
    void realDataScopeCheckRejectsAUserMissingFromTheScopedList() {
        var scoped = mock(SysUserServiceImpl.class);
        doCallRealMethod().when(users).checkUserDataScope(2L);
        try (var security = mockStatic(SecurityUtils.class); var spring = mockStatic(SpringUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(42L);
            spring.when(() -> SpringUtils.getAopProxy(users)).thenReturn(scoped);
            when(scoped.selectUserList(any(SysUser.class))).thenReturn(List.of());
            assertThrows(ServiceException.class, () -> controller.getInfo(2L));
            verify(scoped).selectUserList(argThat(user -> Long.valueOf(2L).equals(user.getUserId())));
            verifyNoInteractions(mapper, roles, posts);
        }
    }

    @Test
    void realDataScopeCheckAllowsAnAlreadyVisibleUserWithoutChangingItsRoleScope() {
        var scoped = mock(SysUserServiceImpl.class);
        doCallRealMethod().when(users).checkUserDataScope(2L);
        try (var security = mockStatic(SecurityUtils.class); var spring = mockStatic(SpringUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(42L);
            spring.when(() -> SpringUtils.getAopProxy(users)).thenReturn(scoped);
            spring.when(() -> SpringUtils.getBean(org.springframework.context.MessageSource.class))
                    .thenReturn(new StaticMessageSource());
            when(scoped.selectUserList(any(SysUser.class))).thenReturn(List.of(new SysUser(2L)));
            AjaxResult result = controller.getInfo(2L);
            assertEquals(200, result.get("code"));
            assertEquals(List.of(), result.get("roleIds"));
            verify(scoped).selectUserList(argThat(user -> Long.valueOf(2L).equals(user.getUserId())));
        }
    }

    @Test
    void disabledUserCanStillBeManaged() {
        AjaxResult result = controller.getInfo(8L);
        assertEquals(200, result.get("code"));
        assertEquals("1", ((SysUser) result.get(AjaxResult.DATA_TAG)).getStatus());
    }

    @Test
    void creationFormStillLoadsPlatformRolesAndPostsWithoutAUserLookup() {
        AjaxResult result = controller.getInfo(null);
        assertEquals(200, result.get("code"));
        assertFalse(result.containsKey(AjaxResult.DATA_TAG));
        assertNotNull(result.get("roles"));
        assertNotNull(result.get("posts"));
        verifyNoInteractions(mapper);
    }
}
