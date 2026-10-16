package tech.qiantong.qdata.module.att.cat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.ResourceBundleMessageSource;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.security.web.service.PermissionService;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.att.controller.admin.cat.AttTagCatController;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttTagCatMapper;
import tech.qiantong.qdata.module.att.dal.mapper.Tag.AttTagMapper;
import tech.qiantong.qdata.module.att.service.Tag.impl.AttTagServiceImpl;
import tech.qiantong.qdata.module.att.service.cat.IAttTagCatService;
import tech.qiantong.qdata.module.att.service.cat.impl.AttTagCatServiceImpl;
import tech.qiantong.qdata.security.web.exception.GlobalExceptionHandler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MyBatis-Plus mapper, H2 synthetic rows, transaction proxy, MVC advice and method security.
 * No application startup, authentication filter, remote connection or business data is used.
 */
class TagCategoryDeletionRegressionTest {
    private JdbcTemplate jdbc;
    private Object previousBeans;
    private IAttTagCatService service;
    private MockMvc mvc;
    private AttTagCatController controller;
    private SqlSessionTemplate session;

    @BeforeEach
    void setup() throws Exception {
        previousBeans = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/att-messages"); messages.setDefaultEncoding("UTF-8");
        messages.setUseCodeAsDefaultMessage(true);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("messageSource", messages);
        new SpringUtils().postProcessBeanFactory(beans);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tag-cat-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ATT_TAG_CAT (id BIGINT PRIMARY KEY, name VARCHAR(100), parent_id BIGINT, "
                + "sort_order BIGINT, description VARCHAR(200), code VARCHAR(50), valid_flag BOOLEAN, "
                + "del_flag BOOLEAN DEFAULT FALSE, creator_id BIGINT, create_by VARCHAR(100), create_time TIMESTAMP, "
                + "updater_id BIGINT, update_by VARCHAR(100), update_time TIMESTAMP, remark VARCHAR(200))");
        jdbc.execute("CREATE TABLE ATT_TAG (id BIGINT PRIMARY KEY, cat_code VARCHAR(50), del_flag BOOLEAN DEFAULT FALSE)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AttTagCatMapper.class);
        configuration.addMapper(AttTagMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(configuration);
        session = new SqlSessionTemplate(factory.getObject());
        var tags = new AttTagServiceImpl();
        ReflectionTestUtils.setField(tags, "attTagMapper", session.getMapper(AttTagMapper.class));
        var impl = new AttTagCatServiceImpl();
        ReflectionTestUtils.setField(impl, "attTagCatMapper", session.getMapper(AttTagCatMapper.class));
        ReflectionTestUtils.setField(impl, "attTagService", tags);
        service = transactional(impl, ds);
        controller = new AttTagCatController();
        ReflectionTestUtils.setField(controller, "attTagCatService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private IAttTagCatService transactional(AttTagCatServiceImpl target, JdbcDataSource ds) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        return (IAttTagCatService) proxy.getProxy();
    }

    private void cat(long id, String code, boolean enabled) {
        jdbc.update("INSERT INTO ATT_TAG_CAT(id,name,parent_id,code,valid_flag) VALUES (?,?,0,?,?)",
                id, "offline-" + id, code, enabled);
    }

    private int deleted() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ATT_TAG_CAT WHERE del_flag=TRUE", Integer.class);
    }

    @AfterEach
    void finish() {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeans);
        // Keep the in-memory fixture for this JVM; no remote cleanup or Hive operations.
    }

    @Test
    void singleIdUsesExistingRouteAndReturnsBusinessSuccess() throws Exception {
        cat(1, "A", false);
        mvc.perform(delete("/att/tagCat/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").doesNotExist());
        assertEquals(1, deleted());
    }

    @Test
    void twoCommaSeparatedIdsDeleteTogether() throws Exception {
        cat(1, "A", false); cat(2, "B", false);
        mvc.perform(delete("/att/tagCat/1,2")).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertEquals(2, deleted());
    }

    @Test
    void manyIdsPreserveUnselectedRowsAndLongPrecision() throws Exception {
        cat(1, "A", false); cat(2, "B", false); cat(9007199254740993L, "C", false); cat(4, "D", false);
        mvc.perform(delete("/att/tagCat/1,2,9007199254740993")).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertEquals(3, deleted());
        assertFalse(jdbc.queryForObject("SELECT del_flag FROM ATT_TAG_CAT WHERE id=4", Boolean.class));
    }

    @Test
    void duplicatesAreIdempotentlyDeduplicated() throws Exception {
        cat(1, "A", false); cat(2, "B", false);
        mvc.perform(delete("/att/tagCat/1,1,2")).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertEquals(2, deleted());
    }

    @Test
    void invalidAndOverflowIdsUseExistingBusinessErrorEnvelopeWithoutWriting() throws Exception {
        cat(1, "A", false);
        for (String ids : List.of("1,x", "1,", ",1", "0", "-1", "1,,2", "9223372036854775808", "1,2.5")) {
            mvc.perform(delete("/att/tagCat/" + ids)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.msg").isNotEmpty());
            assertEquals(0, deleted(), ids);
        }
        assertThrows(ServiceException.class, () -> service.removeAttTagCats(List.of()));
        assertThrows(ServiceException.class, () -> service.removeAttTagCats(null));
        assertThrows(ServiceException.class, () -> service.removeAttTagCat(null));
    }

    @Test
    void missingAndAlreadyDeletedRowsRejectWholeBatchWithoutNpe() throws Exception {
        cat(1, "A", false); cat(2, "B", false);
        jdbc.update("UPDATE ATT_TAG_CAT SET del_flag=TRUE WHERE id=2");
        for (String ids : List.of("1,999", "1,2")) {
            mvc.perform(delete("/att/tagCat/" + ids)).andExpect(jsonPath("$.code").value(500));
            assertEquals(1, deleted());
        }
    }

    @Test
    void enabledRowsRejectSingleAndMixedBatch() throws Exception {
        cat(1, "A", false); cat(2, "B", true);
        for (String ids : List.of("2", "1,2", "2,1")) {
            mvc.perform(delete("/att/tagCat/" + ids)).andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.msg").isNotEmpty());
            assertEquals(0, deleted());
        }
    }

    @Test
    void activeReferenceRejectsWholeBatch() throws Exception {
        cat(1, "A", false); cat(2, "B", false);
        jdbc.update("INSERT INTO ATT_TAG(id,cat_code) VALUES (1,'B')");
        mvc.perform(delete("/att/tagCat/1,2")).andExpect(jsonPath("$.code").value(500));
        assertEquals(0, deleted());
    }

    @Test
    void deletedReferenceDoesNotBlockExistingReferenceSemantics() {
        cat(1, "A", false);
        jdbc.update("INSERT INTO ATT_TAG(id,cat_code,del_flag) VALUES (1,'A',TRUE)");
        assertEquals(1, service.removeAttTagCat(1L));
        assertEquals(1, deleted());
    }

    @Test
    void overlappingSubtreesDeleteOnceAndChildReferenceStillBlocks() {
        cat(1, "A", false); cat(2, "A01", false); cat(3, "B", false);
        jdbc.update("INSERT INTO ATT_TAG(id,cat_code) VALUES (1,'A01')");
        assertThrows(ServiceException.class, () -> service.removeAttTagCats(List.of(3L, 1L)));
        assertEquals(0, deleted());
        // A separate disconnected prefix has no reference and can be removed.
        assertEquals(1, service.removeAttTagCat(3L));
        assertEquals(1, deleted());
    }

    @Test
    void overlappingParentAndChildIdsProduceUniqueAffectedCount() {
        cat(1, "A", false); cat(2, "A01", false); cat(3, "B", false);
        assertEquals(2, service.removeAttTagCats(List.of(1L, 2L)));
        assertEquals(2, deleted());
    }

    @Test
    void enabledChildAndBlankCodeFailClosed() {
        cat(1, "A", false); cat(2, "A01", true); cat(3, "", false);
        assertThrows(ServiceException.class, () -> service.removeAttTagCat(1L));
        assertThrows(ServiceException.class, () -> service.removeAttTagCat(3L));
        assertEquals(0, deleted());
    }

    @Test
    void partialMapperWriteFollowedByExceptionActuallyRollsBack() throws Exception {
        verifyRollback(false);
    }

    @Test
    void shortAffectedCountActuallyRollsBack() throws Exception {
        verifyRollback(true);
    }

    private void verifyRollback(boolean returnShortCount) throws Exception {
        cat(1, "A", false); cat(2, "B", false);
        AttTagCatMapper real = session.getMapper(AttTagCatMapper.class);
        AttTagCatMapper faulty = (AttTagCatMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{AttTagCatMapper.class}, (proxy, method, args) -> {
                    if (method.getName().equals("deleteBatchIds")) {
                        real.deleteById(1L); // real SQL inside the service transaction
                        if (returnShortCount) return 1;
                        throw new IllegalStateException("offline injected write failure");
                    }
                    try { return method.invoke(real, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        var target = (AttTagCatServiceImpl) ((org.springframework.aop.framework.Advised) service).getTargetSource().getTarget();
        ReflectionTestUtils.setField(target, "attTagCatMapper", faulty);
        assertThrows(RuntimeException.class, () -> service.removeAttTagCats(List.of(1L, 2L)));
        assertEquals(0, deleted());
    }

    @Configuration
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean(name = "ss") PermissionService permissions() { return new PermissionService(); }
        @Bean ProjectPermissionService projectPermissions(JdbcTemplate jdbc) { return new ProjectPermissionService(jdbc); }
        @Bean AttTagCatController securedController() { return new AttTagCatController(); }
    }

    @Test
    void actualMethodSecurityBlocksPermissionBypassAndPreservesBusinessEnvelope() throws Exception {
        cat(1, "A", false);
        jdbc.execute("CREATE TABLE system_role(role_id BIGINT, role_key VARCHAR(40), project_id BIGINT, status VARCHAR(1), del_flag VARCHAR(1))");
        jdbc.execute("CREATE TABLE system_user_role(user_id BIGINT, role_id BIGINT)");
        jdbc.execute("CREATE TABLE system_role_menu(role_id BIGINT, menu_id BIGINT)");
        jdbc.execute("CREATE TABLE system_menu(menu_id BIGINT, perms VARCHAR(100), status VARCHAR(1))");
        jdbc.update("INSERT INTO system_role VALUES (7,'project-writer',9,'0','0')");
        jdbc.update("INSERT INTO system_user_role VALUES (88,7)");
        jdbc.update("INSERT INTO system_role_menu VALUES (7,1)");
        jdbc.update("INSERT INTO system_menu VALUES (1,'att:tagCat:remove','0')");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("attTagCatService", service);
            context.getBeanFactory().registerSingleton("jdbc", jdbc);
            context.register(SecurityConfig.class); context.refresh();
            var secured = context.getBean(AttTagCatController.class);
            var user = new SysUser(); user.setUserId(88L); user.setUserName("offline-user"); user.setRoles(List.of());
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new LoginUser(88L, null, user, Set.of("att:tagCat:remove")), null, List.of()));
            var securedMvc = MockMvcBuilders.standaloneSetup(secured)
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            securedMvc.perform(delete("/att/tagCat/1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
            assertEquals(0, deleted());
            jdbc.update("UPDATE system_role SET project_id=0 WHERE role_id=7");
            securedMvc.perform(delete("/att/tagCat/1")).andExpect(jsonPath("$.code").value(200));
            assertEquals(1, deleted());
        }
    }
}
