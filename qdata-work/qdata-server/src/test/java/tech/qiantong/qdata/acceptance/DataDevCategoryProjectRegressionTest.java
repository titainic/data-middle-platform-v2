package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import tech.qiantong.qdata.module.att.controller.admin.cat.AttDataDevCatController;
import tech.qiantong.qdata.module.att.service.project.ProjectAuthority;
import tech.qiantong.qdata.module.att.service.project.ProjectCatalogAccess;
import tech.qiantong.qdata.security.web.exception.GlobalExceptionHandler;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real controller, project grants and error advice; only JDBC reads use an offline fixture.
 * Standalone MVC does not start the authentication filter chain or method-security proxy.
 */
class DataDevCategoryProjectRegressionTest {
    private final CatalogFixture jdbc = new CatalogFixture();
    private MockMvc mvc;
    private Object previousBeanFactory;

    @BeforeEach
    void setup() {
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        var messages = new ResourceBundleMessageSource();
        messages.setBasenames("i18n/messages", "i18n/log-messages");
        messages.setDefaultEncoding("UTF-8");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("messageSource", messages);
        new SpringUtils().postProcessBeanFactory(beans);
        var permissions = new ProjectPermissionService(jdbc);
        var catalogs = new ProjectCatalogAccess(jdbc, new ProjectAuthority(jdbc, permissions), permissions);
        var controller = new AttDataDevCatController();
        ReflectionTestUtils.setField(controller, "catalogs", catalogs);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        login(1L);
    }

    private void login(long userId) {
        var user = new SysUser();
        user.setUserId(userId); user.setUserName("offline-category-" + userId); user.setRoles(List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, null, user, Set.of("*:*:*")), null, List.of()));
    }

    @AfterEach
    void finish() {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeanFactory);
    }

    @Test
    void administratorCanCheckAnExplicitRootProjectDespiteMultipleVisibleProjects() throws Exception {
        mvc.perform(get("/att/dataDevCat/nameUsed").param("parentId", "0").param("name", "new-category")
                        .param("projectId", "1").param("projectCode", "project-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));
        assertEquals(List.of(1L), jdbc.nameCheckProjects);
    }

    @Test
    void unscopedRootRequestStillRejectsAnAmbiguousAdministratorProject() throws Exception {
        mvc.perform(get("/att/dataDevCat/nameUsed").param("parentId", "0").param("name", "new-category"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        assertEquals(List.of(), jdbc.nameCheckProjects);
    }

    @Test
    void duplicateNameIsCheckedOnlyInTheRequestedProject() throws Exception {
        mvc.perform(get("/att/dataDevCat/nameUsed").param("parentId", "0").param("name", "existing")
                        .param("projectId", "1").param("projectCode", "project-1"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").value(true));
        mvc.perform(get("/att/dataDevCat/nameUsed").param("parentId", "0").param("name", "existing")
                        .param("projectId", "2").param("projectCode", "project-2"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data").value(false));
        assertEquals(List.of(1L, 2L), jdbc.nameCheckProjects);
    }

    @Test
    void readonlyIdentityCannotBorrowBroadCachedMenuPermissions() throws Exception {
        login(926L);
        mvc.perform(get("/att/dataDevCat/nameUsed").param("parentId", "0").param("name", "new-category")
                        .param("projectId", "1").param("projectCode", "project-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        assertEquals(List.of(), jdbc.nameCheckProjects);
    }

    @Test
    void writerCanCheckOwnProjectButCannotBorrowItsGrantsForAnotherProject() throws Exception {
        login(927L);
        mvc.perform(get("/att/dataDevCat/nameUsed").param("parentId", "0").param("name", "new-category")
                        .param("projectId", "1").param("projectCode", "project-1"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/att/dataDevCat/nameUsed").param("parentId", "0").param("name", "new-category")
                        .param("projectId", "2").param("projectCode", "project-2"))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(List.of(1L), jdbc.nameCheckProjects);
    }

    @Test
    void forgedProjectCodeAndForeignParentAreRejectedBeforeNameLookup() throws Exception {
        for (var request : List.of(
                get("/att/dataDevCat/nameUsed").param("parentId", "0").param("projectCode", "project-2"),
                get("/att/dataDevCat/nameUsed").param("parentId", "22").param("projectCode", "project-1"))) {
            mvc.perform(request.param("projectId", "1").param("name", "new-category"))
                    .andExpect(jsonPath("$.code").value(403));
        }
        assertEquals(List.of(), jdbc.nameCheckProjects);
    }

    private static class CatalogFixture extends JdbcTemplate {
        final List<Long> nameCheckProjects = new ArrayList<>();

        @Override
        public <T> List<T> queryForList(String sql, Class<T> type) {
            return queryForList(sql, type, new Object[0]);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            if (sql.startsWith("SELECT id FROM att_project") || sql.startsWith("SELECT p.id FROM att_project")) {
                return (List<T>) List.of(1L, 2L);
            }
            if (sql.startsWith("SELECT m.perms FROM system_user_role")) {
                boolean writer = Long.valueOf(927L).equals(args[0]) && Long.valueOf(1L).equals(args[1]);
                return (List<T>) (writer ? List.of("att:dataDevCat:add", "att:dataDevCat:edit")
                        : List.of("att:dataDevCat:list", "att:dataDevCat:query"));
            }
            throw new AssertionError("Unexpected fixture query: " + sql);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            Object result;
            if (sql.startsWith("SELECT COUNT(*) FROM system_user_role")) result = 0L;
            else if (sql.startsWith("SELECT code FROM att_project")) result = "project-" + args[0];
            else if (sql.startsWith("SELECT COUNT(*) FROM att_data_dev_cat")) {
                nameCheckProjects.add((Long) args[0]);
                result = Long.valueOf(1L).equals(args[0]) && "existing".equals(args[2]) ? 1L : 0L;
            } else throw new AssertionError("Unexpected fixture query: " + sql);
            return type.cast(result);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FROM att_data_dev_cat") && Long.valueOf(22L).equals(args[0])) {
                return List.of(Map.of("id", 22L, "project_id", 2L, "project_code", "project-2", "parent_id", 0L, "code", "cat-22"));
            }
            throw new AssertionError("Unexpected fixture query: " + sql);
        }
    }
}
