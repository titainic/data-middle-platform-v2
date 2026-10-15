package tech.qiantong.qdata.module.dpp.service.etl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.file.UploadedFileAccess;
import tech.qiantong.qdata.common.file.UploadedFileKey;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.DppEtlTaskController;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline policy regression: no application database, Hive, HDFS or scheduler is contacted. */
class DevelopmentFileAccessRegressionTest {
    private static final String SOURCE = "/profile/own.csv";
    private static final String ARTIFACT = "hdfs://test-nn:8020/tmp/etl/own.csv";
    @TempDir Path root;
    private JdbcTemplate jdbc;
    private DevelopmentFileAccessService files;

    @BeforeEach void setup() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        files = new DevelopmentFileAccessService(jdbc, mock(DataResourceAccess.class),
                mock(ProjectPermissionService.class), mock(UploadedFileAccess.class));
        ReflectionTestUtils.setField(files, "profile", root.toString());
        Files.writeString(root.resolve("own.csv"), "id,name\n1,example\n");
        Files.writeString(root.resolve("other.csv"), "id,name\n2,other\n");
    }

    private String graph(String component, Map<String, Object> params) {
        return JSON.toJSONString(List.of(Map.of("code", "1792065203131000", "componentType", component,
                "taskParams", params)));
    }

    private String databaseGraph() {
        return graph("1", Map.of("csvFile", "", "readerDatasource", Map.of("datasourceId", 2),
                "table_name", "t_acc_src", "columns", List.of("id", "name", "amt", "created")));
    }

    private void registerArtifactRow() {
        when(jdbc.queryForObject(contains("FROM att_project"), eq(Long.class), anyLong())).thenReturn(1L);
        when(jdbc.queryForList(contains("FROM sys_development_file_artifact"), eq(UploadedFileKey.hash(ARTIFACT))))
                .thenReturn(List.of(Map.of("execution_path", ARTIFACT, "source_resource", SOURCE,
                        "source_hash", UploadedFileKey.hash(SOURCE), "project_id", 1L)));
    }

    private void storedNode(String component, Map<String, Object> params) {
        when(jdbc.queryForList(contains("SELECT t.project_id"), eq(10L)))
                .thenReturn(List.of(Map.of("project_id", 1L)));
        when(jdbc.queryForList(contains("SELECT n.component_type"), eq(10L), eq(1L)))
                .thenReturn(List.of(Map.of("component_type", component, "parameters", JSON.toJSONString(params))));
    }

    @Test void databaseGraphAcceptsLegacyEmptyFilePlaceholder() {
        assertDoesNotThrow(() -> files.definitions(1L, databaseGraph()));
        verifyNoInteractions(jdbc);
    }

    @Test void nonFileReadersAcceptMissingNullAndBlankPlaceholders() {
        for (String component : List.of("1", "3", "6", "7", "91")) {
            for (String params : List.of("{}", "{\"csvFile\":null}", "{\"csvFile\":\"\"}", "{\"csvFile\":\"  \"}")) {
                assertDoesNotThrow(() -> files.definitions(1L, graph(component, JSON.parseObject(params))));
            }
        }
        verifyNoInteractions(jdbc);
    }

    @Test void storedDatabaseNodeAcceptsLegacyPlaceholderWithoutUserSession() {
        storedNode("1", Map.of("csvFile", "", "table_name", "t_acc_src"));
        assertDoesNotThrow(() -> files.task(10L));
        verify(jdbc, never()).queryForList(contains("FROM sys_development_file_artifact"), any(Object[].class));
    }

    @Test void actualFileInputsCannotOmitTheirSourceOrArtifact() {
        for (String component : List.of("2", "4")) {
            String sourceKey = "2".equals(component) ? "excelFile" : "file";
            for (Map<String, Object> params : List.<Map<String, Object>>of(Map.of(),
                    Map.of("csvFile", ""), Map.of("csvFile", ARTIFACT), Map.of(sourceKey, SOURCE),
                    Map.of(sourceKey, SOURCE, "csvFile", "  "))) {
                assertThrows(AccessDeniedException.class, () -> files.definitions(1L, graph(component, params)));
            }
            String missingParams = "[{\"componentType\":\"" + component + "\"}]";
            assertThrows(AccessDeniedException.class, () -> files.definitions(1L, missingParams));
        }
    }

    @Test void registeredExcelAndCsvInputsStillPass() {
        registerArtifactRow();
        assertDoesNotThrow(() -> files.definitions(1L, graph("2", Map.of("excelFile", SOURCE, "csvFile", ARTIFACT))));
        assertDoesNotThrow(() -> files.definitions(1L, graph("4", Map.of("file", SOURCE, "csvFile", ARTIFACT))));
    }

    @Test void actualFileInputsStillRejectForeignProjectsAndSwappedSources() {
        registerArtifactRow();
        for (String component : List.of("2", "4")) {
            String sourceKey = "2".equals(component) ? "excelFile" : "file";
            String definition = graph(component, Map.of(sourceKey, SOURCE, "csvFile", ARTIFACT));
            assertThrows(AccessDeniedException.class, () -> files.definitions(144L, definition));
            assertThrows(AccessDeniedException.class, () -> files.definitions(1L,
                    graph(component, Map.of(sourceKey, "/profile/other.csv", "csvFile", ARTIFACT))));
        }
    }

    @Test void unregisteredArtifactIsRejected() {
        registerArtifactRow();
        assertThrows(AccessDeniedException.class, () -> files.definitions(1L,
                graph("4", Map.of("file", SOURCE, "csvFile", ARTIFACT.replace("own.csv", "unknown.csv")))));
    }

    @Test void changingComponentTypeCannotBypassNonEmptyArtifactChecks() {
        registerArtifactRow();
        assertThrows(AccessDeniedException.class, () -> files.definitions(144L,
                graph("1", Map.of("file", SOURCE, "csvFile", ARTIFACT))));
        assertThrows(AccessDeniedException.class, () -> files.definitions(1L,
                graph("1", Map.of("csvFile", ARTIFACT))));
    }

    @Test void storedFileNodesStillValidateTheirProjectAndSource() {
        registerArtifactRow();
        storedNode("4", Map.of("file", SOURCE, "csvFile", ARTIFACT));
        assertDoesNotThrow(() -> files.task(10L));
        storedNode("4", Map.of("file", "/profile/other.csv", "csvFile", ARTIFACT));
        assertThrows(AccessDeniedException.class, () -> files.task(10L));
    }

    private DevelopmentResourceAccess canvasAccess(DataResourceAccess resources) {
        when(resources.visibleProjectIds()).thenReturn(Set.of(1L));
        when(resources.isAdministrator()).thenReturn(true);
        when(jdbc.queryForList(contains("SELECT id,project_id,project_code,code,type"), eq(10L)))
                .thenReturn(List.of(Map.of("id", 10L, "project_id", 1L, "project_code", "9007199254740993",
                        "code", "task10", "type", "1")));
        when(jdbc.queryForList(contains("SELECT code FROM att_project"), eq(String.class), eq(1L)))
                .thenReturn(List.of("9007199254740993"));
        when(jdbc.queryForList(contains("FROM att_task_cat"), eq(Long.class), eq("bin11cat")))
                .thenReturn(List.of(1L));
        var access = new DevelopmentResourceAccess(jdbc, resources, mock(ProjectPermissionService.class));
        ReflectionTestUtils.setField(access, "developmentFiles", files);
        return access;
    }

    private DppEtlNewNodeSaveReqVO canvasRequest() {
        var request = new DppEtlNewNodeSaveReqVO();
        request.setId("10"); request.setProjectId(1L); request.setProjectCode(9007199254740993L);
        request.setType("1"); request.setCatCode("bin11cat"); request.setScheduler("QUARTZ");
        var nodes = JSON.parseArray(databaseGraph());
        nodes.add(JSONObject.of("code", "1792065263272000", "componentType", "91", "taskParams",
                Map.of("writerDatasource", Map.of("datasourceId", 3), "writeModeType", 2,
                        "target_table_name", "t_bin39_531196d9", "preSql", "", "postSql", "")));
        request.setTaskDefinitionList(nodes.toJSONString());
        return request;
    }

    @Test void adminCanvasSavePassesPolicyAndReachesTheSaveService() {
        var resources = mock(DataResourceAccess.class);
        var controller = new DppEtlTaskController();
        var saveService = mock(IDppEtlTaskService.class);
        ReflectionTestUtils.setField(controller, "resourceAccess", canvasAccess(resources));
        ReflectionTestUtils.setField(controller, "dppEtlTaskService", saveService);
        var request = canvasRequest();
        assertDoesNotThrow(() -> controller.createEtlTaskFrontPostposition(request));
        verify(saveService).createEtlTaskFrontPostposition(request);
        assertEquals(9007199254740993L, request.getProjectCode());
    }

    @Test void canvasStillRejectsForeignNodeOwnership() {
        var access = canvasAccess(mock(DataResourceAccess.class));
        when(jdbc.queryForList(contains("FROM dpp_etl_node WHERE code=? UNION"), eq(Long.class),
                eq("1792065203131000"), eq("1792065203131000"))).thenReturn(List.of(144L));
        assertThrows(AccessDeniedException.class, () -> access.request(canvasRequest(), "edit"));
    }

    @Test void canvasStillRequiresProjectEditPermission() {
        var resources = mock(DataResourceAccess.class);
        var access = canvasAccess(resources);
        when(resources.isAdministrator()).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> access.request(canvasRequest(), "edit"));
    }
}
