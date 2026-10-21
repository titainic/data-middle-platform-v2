package tech.qiantong.qdata.module.att.service.project;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tech.qiantong.qdata.api.ds.api.project.DsProjectDeleteRespDTO;
import tech.qiantong.qdata.api.ds.api.service.project.IDsProjectService;
import tech.qiantong.qdata.module.att.controller.admin.project.AttProjectController;
import tech.qiantong.qdata.module.att.dal.mapper.project.AttProjectDeletionMapper;
import tech.qiantong.qdata.module.att.dal.mapper.project.AttProjectMapper;
import tech.qiantong.qdata.module.att.dal.mapper.project.AttProjectUserRelMapper;
import tech.qiantong.qdata.module.att.service.project.impl.IAttProjectServiceImpl;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只使用本次新建的 H2 内存库，真实 Mapper SQL 与 Spring 事务代理，DS 使用替身。 */
class ProjectDeletionTransactionTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private IDsProjectService scheduler;
    private IAttProjectService service;

    @BeforeEach
    void setup() throws Exception {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(database);
        String auditColumns = ", creator_id BIGINT, create_by VARCHAR(64), create_time TIMESTAMP, "
                + "updater_id BIGINT, update_by VARCHAR(64), update_time TIMESTAMP, remark VARCHAR(500)";
        jdbc.execute("CREATE TABLE att_project (id BIGINT PRIMARY KEY, name VARCHAR(64), code VARCHAR(64), "
                + "scheduler_type VARCHAR(32), manager_id BIGINT, description VARCHAR(500), "
                + "valid_flag TINYINT DEFAULT 1, del_flag TINYINT DEFAULT 0" + auditColumns + ")");
        jdbc.execute("CREATE TABLE att_project_user_rel (id BIGINT PRIMARY KEY, project_id BIGINT, user_id BIGINT, "
                + "valid_flag TINYINT DEFAULT 1, del_flag TINYINT DEFAULT 0" + auditColumns + ")");
        jdbc.execute("CREATE TABLE system_role (role_id BIGINT PRIMARY KEY, project_id BIGINT, del_flag VARCHAR(1) DEFAULT '0')");
        jdbc.execute("CREATE TABLE system_user_role (user_id BIGINT, role_id BIGINT, PRIMARY KEY(user_id, role_id))");

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AttProjectMapper.class);
        configuration.addMapper(AttProjectUserRelMapper.class);
        configuration.addMapper(AttProjectDeletionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(database);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        var target = new IAttProjectServiceImpl();
        ReflectionTestUtils.setField(target, "attProjectMapper", session.getMapper(AttProjectMapper.class));
        ReflectionTestUtils.setField(target, "attProjectUserRelMapper", session.getMapper(AttProjectUserRelMapper.class));
        ReflectionTestUtils.setField(target, "attProjectDeletionMapper", session.getMapper(AttProjectDeletionMapper.class));
        scheduler = mock(IDsProjectService.class);
        ReflectionTestUtils.setField(target, "dsProjectService", scheduler);
        // 真实代理使 setRollbackOnly() 在业务方法正常返回 -2 时也实际回滚。
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database),
                new AnnotationTransactionAttributeSource()));
        service = (IAttProjectService) proxy.getProxy();
        seedProject(1L, "QUARTZ", true);
        seedProject(2L, "DOLPHINSCHEDULER", false);
        seedProject(3L, "QUARTZ", true);
        // 历史已删除的项目角色也不能留下仍然有效的用户绑定。
        jdbc.update("INSERT INTO system_role VALUES (12, 1, '2')");
        jdbc.update("INSERT INTO system_user_role VALUES (925, 12)");
        // 系统角色和其他项目使用相同用户，必须保留其授权。
        jdbc.update("INSERT INTO system_role VALUES (99, NULL, '0')");
        jdbc.update("INSERT INTO system_user_role VALUES (925, 99)");
    }

    @AfterEach
    void shutdown() {
        if (database != null) {
            database.shutdown();
        }
    }

    @Test
    void projectWithCreatorAndMembersDeletesOnlyItsOwnRelationsAndRoles() {
        assertEquals(1, service.removeAttProject(List.of(1L)));
        assertDeleted(1L);
        assertEquals(0, count("system_user_role", "role_id = 12"));
        assertEquals(1, count("system_role", "role_id = 12 AND del_flag = '2'"));
        assertActive(2L);
        assertActive(3L);
        assertEquals(1, count("system_user_role", "user_id = 925 AND role_id = 99"));
        verifyNoInteractions(scheduler);
    }

    @Test
    void projectWithoutMembersDeletesAndCallsDolphinScheduler() {
        when(scheduler.deleteProject(1002L)).thenReturn(response(0));
        assertEquals(1, service.removeAttProject(List.of(2L)));
        assertDeleted(2L);
        assertActive(1L);
        verify(scheduler).deleteProject(1002L);
        verifyNoMoreInteractions(scheduler);
    }

    @Test
    void mixedBatchDeletesBothTypesButSkipsQuartzExternalCall() {
        when(scheduler.deleteProject(1002L)).thenReturn(response(0));
        assertEquals(2, service.removeAttProject(List.of(1L, 2L)));
        assertDeleted(1L);
        assertDeleted(2L);
        assertActive(3L);
        verify(scheduler).deleteProject(1002L);
        verifyNoMoreInteractions(scheduler);
    }

    @Test
    void schedulerRejectionRollsBackAllFourTablesForEntireBatch() {
        var before = snapshot();
        when(scheduler.deleteProject(1002L)).thenAnswer(invocation -> {
            assertDeleted(1L);
            assertDeleted(2L);
            return response(1);
        });
        assertEquals(-2, service.removeAttProject(List.of(1L, 2L)));
        assertEquals(before, snapshot());
        verify(scheduler).deleteProject(1002L);
    }

    @Test
    void missingSchedulerResponseOrCodeAlsoRollsBack() {
        var before = snapshot();
        for (DsProjectDeleteRespDTO result : Arrays.asList(null, new DsProjectDeleteRespDTO())) {
            when(scheduler.deleteProject(1002L)).thenReturn(result);
            assertEquals(-2, service.removeAttProject(List.of(1L, 2L)));
            assertEquals(before, snapshot());
        }
    }

    @Test
    void schedulerExceptionPropagatesAndRollsBackAllLocalChanges() {
        var before = snapshot();
        var failure = new IllegalStateException("离线调度替身异常");
        when(scheduler.deleteProject(1002L)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.removeAttProject(List.of(1L, 2L))));
        assertEquals(before, snapshot());
    }

    @Test
    void emptyUnknownAndRepeatedIdsCannotBroadenDeletionScope() {
        var before = snapshot();
        assertEquals(0, service.removeAttProject(null));
        assertEquals(0, service.removeAttProject(List.of()));
        assertEquals(0, service.removeAttProject(List.of(999L)));
        assertEquals(before, snapshot());
        assertEquals(1, service.removeAttProject(List.of(1L, 1L, 999L)));
        var after = snapshot();
        assertEquals(0, service.removeAttProject(List.of(1L)));
        assertEquals(after, snapshot());
        assertActive(2L);
        assertActive(3L);
        verifyNoInteractions(scheduler);
    }

    @Test
    void controllerReportsSchedulerFailureWithRollbackMeaning() {
        var before = snapshot();
        when(scheduler.deleteProject(1002L)).thenReturn(response(1));
        var authority = mock(ProjectAuthority.class);
        var controller = new AttProjectController();
        ReflectionTestUtils.setField(controller, "attProjectService", service);
        ReflectionTestUtils.setField(controller, "authority", authority);
        var proxy = new ProxyFactory(controller);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database),
                new AnnotationTransactionAttributeSource()));
        var transactionalController = (AttProjectController) proxy.getProxy();
        var result = assertDoesNotThrow(() -> transactionalController.remove(new Long[]{1L, 2L}));
        assertEquals(before, snapshot());
        assertEquals(500, result.getCode());
        assertTrue(result.getMsg().contains("DolphinScheduler"));
        assertTrue(result.getMsg().contains("回滚"));
        assertFalse(result.getMsg().contains("项目有人员"));
        verify(authority).manage(1L, "att:project:remove");
        verify(authority).manage(2L, "att:project:remove");
    }

    private void seedProject(long id, String type, boolean members) {
        jdbc.update("INSERT INTO att_project(id, name, code, scheduler_type, manager_id) VALUES (?, ?, ?, ?, 925)",
                id, "离线项目" + id, String.valueOf(1000 + id), type);
        if (members) {
            jdbc.update("INSERT INTO att_project_user_rel(id, project_id, user_id) VALUES (?, ?, 925)", id, id);
        }
        jdbc.update("INSERT INTO system_role(role_id, project_id) VALUES (?, ?)", id * 10, id);
        jdbc.update("INSERT INTO system_user_role VALUES (925, ?)", id * 10);
    }

    private void assertDeleted(long id) {
        assertEquals(0, count("att_project", "id = " + id + " AND del_flag = 0"));
        assertEquals(1, count("att_project", "id = " + id + " AND del_flag = 1"));
        assertEquals(0, count("att_project_user_rel", "project_id = " + id + " AND del_flag = 0"));
        assertEquals(0, count("system_role", "project_id = " + id + " AND del_flag != '2'"));
        assertEquals(0, count("system_user_role", "role_id IN (SELECT role_id FROM system_role WHERE project_id = " + id + ")"));
    }

    private void assertActive(long id) {
        assertEquals(1, count("att_project", "id = " + id + " AND del_flag = 0"));
        assertEquals(1, count("system_role", "role_id = " + id * 10 + " AND del_flag = '0'"));
        assertEquals(1, count("system_user_role", "user_id = 925 AND role_id = " + id * 10));
    }

    private int count(String table, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
    }

    private List<List<Map<String, Object>>> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM att_project ORDER BY id"),
                jdbc.queryForList("SELECT * FROM att_project_user_rel ORDER BY id"),
                jdbc.queryForList("SELECT * FROM system_role ORDER BY role_id"),
                jdbc.queryForList("SELECT * FROM system_user_role ORDER BY user_id, role_id"));
    }

    private DsProjectDeleteRespDTO response(int code) {
        var result = new DsProjectDeleteRespDTO();
        result.setCode(code);
        return result;
    }
}
