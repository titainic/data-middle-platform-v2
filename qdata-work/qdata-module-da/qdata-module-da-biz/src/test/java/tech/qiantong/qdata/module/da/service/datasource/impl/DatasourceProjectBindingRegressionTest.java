package tech.qiantong.qdata.module.da.service.datasource.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.datasource.vo.*;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.*;
import tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper;
import tech.qiantong.qdata.module.da.service.datasource.*;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 执行真实归属校验、DTO转换及业务服务，数据库和Redis均为替身。 */
class DatasourceProjectBindingRegressionTest {
    private DatasourceProjectAccess access() {
        var jdbc = mock(JdbcTemplate.class);
        var data = mock(DataResourceAccess.class);
        var permissions = mock(ProjectPermissionService.class);
        when(data.visibleProjectIds()).thenReturn(Set.of(5L));
        when(permissions.has(eq(5L), anyString())).thenReturn(true);
        when(jdbc.queryForList(eq("SELECT code FROM att_project WHERE id=? AND valid_flag='1' AND del_flag='0'"), eq(String.class), eq(5L)))
                .thenReturn(List.of("5005"));
        return new DatasourceProjectAccess(jdbc, data, permissions);
    }

    @Test void projectIdResolvesToCodeAndMismatchedOrInaccessibleProjectsAreDenied() {
        var access = access();
        assertEquals("5005", access.resolveProjectCode(5L, null));
        assertEquals("5005", access.resolveProjectCode(5L, "5005"));
        assertThrows(AccessDeniedException.class, () -> access.resolveProjectCode(5L, "6006"));
        assertThrows(AccessDeniedException.class, () -> access.resolveProjectCode(6L, null));
    }

    private DaDatasourceSaveReqVO request() {
        var request = new DaDatasourceSaveReqVO();
        request.setDatasourceName("fix_mysql"); request.setDatasourceType("MYSQL");
        request.setIp("127.0.0.1"); request.setPort(3306L); request.setValidFlag(false);
        request.setDatasourceConfig("{\"username\":\"fix_reader\",\"dbname\":\"fix_database\"}");
        var project = new DaDatasourceProjectRelDO(); project.setProjectId(5L);
        project.setProjectCode("forged"); project.setDppAssigned(true);
        request.setProjectList(List.of(project));
        return request;
    }

    @Test void createWithoutProjectOrWithForeignProjectFailsBeforePersistence() {
        var request = request(); request.setProjectList(null);
        assertThrows(IllegalArgumentException.class, () -> access().validateSave(request, true, "add"));
        request.setProjectList(List.of(new DaDatasourceProjectRelDO()));
        assertThrows(IllegalArgumentException.class, () -> access().validateSave(request, true, "add"));
        request.getProjectList().getFirst().setProjectId(6L);
        assertThrows(AccessDeniedException.class, () -> access().validateSave(request, true, "add"));
    }

    private DaDatasourceServiceImpl service(DaDatasourceMapper mapper, IDaDatasourceProjectRelService relations) {
        var service = spy(new DaDatasourceServiceImpl());
        ReflectionTestUtils.setField(service, "daDatasourceMapper", mapper);
        ReflectionTestUtils.setField(service, "daDatasourceProjectRelService", relations);
        ReflectionTestUtils.setField(service, "redisService", DatasourceCacheRedisMock.create());
        doReturn(0L).when(service).count(any(LambdaQueryWrapper.class));
        doReturn(List.of()).when(service).list(any(LambdaQueryWrapper.class));
        when(mapper.insert(any(DaDatasourceDO.class))).thenAnswer(call -> {
            ((DaDatasourceDO) call.getArgument(0)).setId(64L); return 1;
        });
        return service;
    }

    @Test void validatedProjectsAreWrittenWithNewDatasourceIdAndReturnedInPage() {
        var request = request(); access().validateSave(request, true, "add");
        var mapper = mock(DaDatasourceMapper.class); var relations = mock(IDaDatasourceProjectRelService.class);
        var service = service(mapper, relations);
        when(relations.saveBatch(anyCollection())).thenReturn(true);
        var source = new DaDatasourceDO(); source.setId(64L); source.setValidFlag(false);
        source.setProjectList(request.getProjectList());
        doReturn(source).when(service).getDaDatasourceById(64L);
        assertEquals(64L, service.createDaDatasource(request));
        verify(relations).saveBatch(argThat(values -> values.size() == 1 && values.stream().allMatch(rel ->
                Long.valueOf(64).equals(rel.getDatasourceId()) && Long.valueOf(5).equals(rel.getProjectId())
                        && "5005".equals(rel.getProjectCode()) && Boolean.TRUE.equals(rel.getValidFlag())
                        && Boolean.FALSE.equals(rel.getDppAssigned()))));
        verify(relations, never()).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        when(mapper.selectPage(any(DaDatasourcePageReqVO.class))).thenReturn(new PageResult<>(List.of(source), 1L));
        when(relations.getProjectBindings(List.of(64L))).thenReturn(request.getProjectList());
        assertEquals(5L, service.getDaDatasourcePage(new DaDatasourcePageReqVO()).getRows().getFirst().getProjectList().getFirst().getProjectId());
        verify(relations).getProjectBindings(List.of(64L));
    }

    @Test void failedProjectWriteIsReportedInsteadOfReturningSuccessfulDatasource() {
        var request = request(); access().validateSave(request, true, "add");
        var mapper = mock(DaDatasourceMapper.class); var relations = mock(IDaDatasourceProjectRelService.class);
        var service = service(mapper, relations);
        when(relations.saveBatch(anyCollection())).thenReturn(false);
        assertEquals("数据源项目关联保存失败，请重试", assertThrows(ServiceException.class,
                () -> service.createDaDatasource(request)).getMessage());
        verify(service, never()).getDaDatasourceById(anyLong());
    }
}
