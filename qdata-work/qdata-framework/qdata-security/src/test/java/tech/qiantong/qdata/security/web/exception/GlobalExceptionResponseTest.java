package tech.qiantong.qdata.security.web.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.exception.ServiceException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Offline Spring MVC/advice dispatch, without a running server or security/login filters. */
class GlobalExceptionResponseTest {
    private MockMvc mvc;
    private static final String RAW = "java.sql.SQLException: Field 'person_charge' doesn't have a default value; "
            + "tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlTaskMapper.insert-Inline; "
            + "INSERT INTO dpp_etl_task (name) VALUES ('fixture')";

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest @ValueSource(strings={"runtime","checked","database","wrapped-business","caused-business","argument"})
    void unexpectedAndWrappedDatabaseFailuresNeverLeakDetails(String kind) throws Exception {
        String body = mvc.perform(get("/failure/" + kind)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500)).andReturn().getResponse().getContentAsString();
        safe(body);
        assertFalse(body.contains("private-database-detail"));
    }

    @ParameterizedTest @ValueSource(strings={"INSERT INTO private_table VALUES (1)",
            "select secret from private_table", "UPDATE private_table SET secret=1", "DELETE FROM private_table",
            "DROP TABLE private_table", "tech.qiantong.example.HiddenMapper.insert-Inline",
            "Caused by: hidden failure", "at hidden.Private.work(Private.java:42)"})
    void businessExceptionsCannotEchoUpstreamSqlOrStacks(String detail) throws Exception {
        mvc.perform(get("/failure/business-detail").param("detail", detail))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains(detail)));
    }

    @Test void normalBusinessRejectionRetainsReadableMessageAndDefaultCode() throws Exception {
        mvc.perform(get("/failure/business")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("同一父级下已存在同名类目"));
    }

    @Test void explicitBusinessCodeRemainsUnchanged() throws Exception {
        mvc.perform(get("/failure/business-detail").param("detail", "任务状态不允许此操作"))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("任务状态不允许此操作"));
    }

    @Test void queryTypeFailureDoesNotEchoRejectedTextOrJavaType() throws Exception {
        String body = mvc.perform(get("/failure/typed").param("id", RAW))
                .andExpect(jsonPath("$.code").value(500)).andReturn().getResponse().getContentAsString();
        safe(body); assertFalse(body.contains("java.lang.Long"));
    }

    @Test void missingQueryFieldHasReadableError() throws Exception {
        mvc.perform(get("/failure/typed")).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("参数[id]不能为空"));
    }

    @Test void explicitInputRejectionRemainsReadable() throws Exception {
        mvc.perform(get("/failure/input")).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("新建模型请使用 Hive 五层建模，绑定数仓空间和物理数据库"));
    }

    @Test void accessRejectionKeepsExistingForbiddenBusinessCode() throws Exception {
        mvc.perform(get("/failure/forbidden")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test void dependencyFailureKeepsServiceUnavailableStatusAndCode() throws Exception {
        mvc.perform(get("/failure/unavailable")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value("依赖暂不可用"));
    }

    private void safe(String body) {
        for (String detail : new String[]{"SQLException", "Mapper", "INSERT INTO", "person_charge", "java.sql", "Caused by:"})
            assertFalse(body.contains(detail), body);
    }

    @RestController @RequestMapping("/failure")
    static class FailingController {
        @GetMapping("/forbidden") Object forbidden() { throw new org.springframework.security.access.AccessDeniedException(RAW); }
        @GetMapping("/unavailable") Object unavailable() { throw new tech.qiantong.qdata.common.exception.DependencyUnavailableException("依赖暂不可用"); }
        @GetMapping("/input") Object input() { throw new IllegalArgumentException("新建模型请使用 Hive 五层建模，绑定数仓空间和物理数据库"); }
        @GetMapping("/argument") Object argument() { throw new IllegalArgumentException(RAW); }
        @GetMapping("/runtime") Object runtime() { throw new RuntimeException(RAW); }
        @GetMapping("/checked") Object checked() throws Exception { throw new Exception(RAW); }
        @GetMapping("/database") Object database() { throw new DataIntegrityViolationException(RAW, new SQLException(RAW)); }
        @GetMapping("/wrapped-business") Object wrapped() { throw new ServiceException("保存失败：" + RAW); }
        @GetMapping("/caused-business") Object caused() {
            var failure = new ServiceException("private-database-detail");
            failure.initCause(new SQLException("private-database-detail")); throw failure;
        }
        @GetMapping("/business") Object business() { throw new ServiceException("同一父级下已存在同名类目"); }
        @GetMapping("/business-detail") Object businessDetail(@RequestParam String detail) { throw new ServiceException(detail,409); }
        @GetMapping("/typed") Object typed(@RequestParam Long id) { return id; }
    }
}
