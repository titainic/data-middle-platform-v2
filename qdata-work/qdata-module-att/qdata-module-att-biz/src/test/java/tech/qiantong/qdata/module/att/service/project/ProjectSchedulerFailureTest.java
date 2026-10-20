package tech.qiantong.qdata.module.att.service.project;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.hutool.http.HttpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tech.qiantong.qdata.api.ds.api.project.DsProjectRespDTO;
import tech.qiantong.qdata.api.ds.api.service.project.IDsProjectService;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.httpClient.DsRequestUtils;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectSaveReqVO;
import tech.qiantong.qdata.module.att.controller.admin.project.AttProjectController;
import tech.qiantong.qdata.redis.service.IRedisService;
import tech.qiantong.qdata.security.web.exception.GlobalExceptionHandler;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real lifecycle and business exception handler, with database/Redis/scheduler doubles. */
class ProjectSchedulerFailureTest {
    private JdbcTemplate jdbc;
    private IRedisService sequence;
    private IDsProjectService external;
    private ProjectLifecycleService lifecycle;
    private String previousBaseUrl;
    private final DsRequestUtils config = new DsRequestUtils();

    @BeforeEach void setup() {
        previousBaseUrl = (String) ReflectionTestUtils.getField(DsRequestUtils.class, "baseUrl");
        jdbc = mock(JdbcTemplate.class);
        sequence = mock(IRedisService.class);
        external = mock(IDsProjectService.class);
        var permissions = mock(ProjectPermissionService.class);
        when(permissions.has(null, "att:project:add")).thenReturn(true);
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM system_user"), eq(Long.class), eq(925L)))
                .thenReturn(1L);
        lifecycle = new ProjectLifecycleService(jdbc, sequence, external, permissions, mock(ProjectAuthority.class));
    }

    @AfterEach void restore() { config.setBaseUrl(previousBaseUrl); }

    private AttProjectSaveReqVO request(String scheduler) {
        var request = new AttProjectSaveReqVO();
        request.setName("offline project");
        request.setManagerId(925L);
        request.setSchedulerType(scheduler);
        return request;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void emptyDsConfigurationReturnsActionableBusinessErrorWithoutExternalCallOrLocalWrites(String baseUrl) {
        config.setBaseUrl(baseUrl);
        try (var transport = mockStatic(HttpUtil.class)) {
            ServiceException failure = assertThrows(ServiceException.class,
                    () -> lifecycle.create(request("DOLPHINSCHEDULER")));
            var result = new GlobalExceptionHandler().handleServiceException(failure, mock(HttpServletRequest.class));
            assertEquals(400, result.get("code"));
            assertTrue(result.get("msg").toString().contains("未配置"));
            assertTrue(result.get("msg").toString().contains("Quartz"));
            assertFalse(result.get("msg").toString().contains("系统内部错误"));
            verifyNoInteractions(sequence, external);
            transport.verifyNoInteractions();
            verify(jdbc).queryForObject(startsWith("SELECT COUNT(*) FROM system_user"), eq(Long.class), eq(925L));
            verifyNoMoreInteractions(jdbc);
        }
    }

    @Test void configuredDsFailurePreservesCauseAndHandlerDoesNotExposeIt() {
        config.setBaseUrl("http://scheduler.invalid");
        var root = new IllegalArgumentException("protocol = http host = null");
        var failure = new ServiceException("请检查海豚调度服务连接和配置", 400);
        failure.initCause(root);
        when(external.saveProject(any())).thenThrow(failure);
        ServiceException actual = assertThrows(ServiceException.class,
                () -> lifecycle.create(request("DOLPHINSCHEDULER")));
        assertSame(root, actual.getCause());
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var result = new GlobalExceptionHandler().handleServiceException(actual, mock(HttpServletRequest.class));
            assertEquals(400, result.get("code"));
            assertFalse(result.toString().contains("protocol"));
            assertEquals(root.getMessage(), events.list.getFirst().getThrowableProxy().getCause().getMessage());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
        verifyNoInteractions(sequence);
        verify(jdbc).queryForObject(startsWith("SELECT COUNT(*) FROM system_user"), eq(Long.class), eq(925L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void realControllerAndAdviceReturnHttp200WithBusiness400AndNoOutboundCall() throws Exception {
        config.setBaseUrl("");
        var service = mock(IAttProjectService.class);
        when(service.createAttProject(any())).thenAnswer(invocation -> lifecycle.create(invocation.getArgument(0)));
        var controller = new AttProjectController();
        ReflectionTestUtils.setField(controller, "attProjectService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user = new SysUser();
        user.setUserId(925L);
        user.setUserName("offline-user");
        user.setNickName("offline user");
        var login = new LoginUser(925L, null, user, Set.of("att:project:add"));
        try (var actor = mockStatic(SecurityUtils.class); var transport = mockStatic(HttpUtil.class)) {
            actor.when(SecurityUtils::getLoginUser).thenReturn(login);
            mvc.perform(post("/att/project").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"offline project\",\"managerId\":925,\"schedulerType\":\"DOLPHINSCHEDULER\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("Quartz")))
                    .andExpect(jsonPath("$.detailMessage").doesNotExist());
            verifyNoInteractions(sequence, external);
            transport.verifyNoInteractions();
        }
    }

    @Test void unconfirmedDsResponseIsBusinessErrorBeforeLocalWrites() {
        config.setBaseUrl("http://scheduler.invalid");
        for (DsProjectRespDTO response : new DsProjectRespDTO[]{null, new DsProjectRespDTO()}) {
            when(external.saveProject(any())).thenReturn(response);
            ServiceException failure = assertThrows(ServiceException.class,
                    () -> lifecycle.create(request("DOLPHINSCHEDULER")));
            assertEquals(400, failure.getCode());
            assertTrue(failure.getMessage().contains("核对"));
        }
        verifyNoInteractions(sequence);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"QUARTZ"})
    void quartzAndDefaultStillCreateProjectWithNoDsConfiguration(String scheduler) {
        config.setBaseUrl(null);
        when(sequence.nextSequence(anyString(), anyLong())).thenReturn(162000L);
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM att_project"), eq(Long.class), eq("162000")))
                .thenReturn(0L);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
            KeyHolder holder = invocation.getArgument(1);
            holder.getKeyList().add(Map.of("id", 162L));
            return 1;
        });
        try (var actor = mockStatic(SecurityUtils.class)) {
            actor.when(SecurityUtils::getUserId).thenReturn(925L);
            actor.when(SecurityUtils::getUsername).thenReturn("offline-user");
            assertEquals(162L, lifecycle.create(request(scheduler)));
        }
        verify(sequence).nextSequence(eq("qdata:project:code"), anyLong());
        verifyNoInteractions(external);
        verify(jdbc, times(2)).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }
}
