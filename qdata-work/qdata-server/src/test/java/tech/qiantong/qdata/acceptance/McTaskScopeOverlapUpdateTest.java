package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskSaveReqVO;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.McTaskScopeSaveReqVO;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskScopeDO;
import tech.qiantong.qdata.module.mc.dal.mapper.task.McTaskMapper;
import tech.qiantong.qdata.module.mc.service.task.IMcTaskScopeService;
import tech.qiantong.qdata.module.mc.service.task.impl.McTaskServiceImpl;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McTaskScopeOverlapUpdateTest {
    private McTaskServiceImpl service;
    private McTaskMapper mapper;
    private IMcTaskScopeService scopes;

    @BeforeEach void setup() {
        service = new McTaskServiceImpl();
        mapper = mock(McTaskMapper.class);
        scopes = mock(IMcTaskScopeService.class);
        ReflectionTestUtils.setField(service, "mcTaskMapper", mapper);
        ReflectionTestUtils.setField(service, "mcTaskScopeService", scopes);
        var own = new McTaskDO();
        own.setId(8L);own.setDatasourceId(9L);own.setCollectionScope("1");
        var other = new McTaskDO();other.setId(9L);
        when(mapper.selectById(8L)).thenReturn(own);
        when(mapper.selectByDatasourceAndScope(9L, "1", 8L)).thenReturn(List.of(other));
        when(mapper.selectByDatasourceAndScope(9L, "1", null)).thenReturn(List.of(other));
        when(scopes.getMcTaskScopeListBytaskId(8L)).thenReturn(List.of(scope("ods_iot_db")));
        when(scopes.getMcTaskScopeListBytaskId(9L)).thenReturn(List.of(scope("ods_iot_db"), scope("new_db")));
    }

    private static McTaskScopeDO scope(String name) {
        return McTaskScopeDO.builder().dbName(name).schemaName("").build();
    }
    private static McTaskScopeSaveReqVO requested(String name) {
        var value = new McTaskScopeSaveReqVO();value.setDbName(name);value.setSchemaName("");return value;
    }
    private static McTaskSaveReqVO request(McTaskScopeSaveReqVO... values) {
        var value = new McTaskSaveReqVO();value.setDatasourceId(9L);value.setCollectionScope("1");value.setScopeSaveReqVOS(List.of(values));return value;
    }
    private void validate(McTaskSaveReqVO request, Long exclude) {
        ReflectionTestUtils.invokeMethod(service,"validateDuplicateTask",request,exclude);
    }

    @Test void editingAnExistingSharedScopeKeepsItsHistoricalOwnership() {
        assertDoesNotThrow(() -> validate(request(requested("ods_iot_db")),8L));
    }

    @Test void addingAConflictingScopeIsStillRejected() {
        try (var messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.messageEn("mc.error.scope.conflict", "new_db")).thenReturn("conflict");
            assertThrows(ServiceException.class, () -> validate(request(requested("ods_iot_db"),requested("new_db")),8L));
        }
    }

    @Test void creatingAnotherTaskWithTheSameScopeIsStillRejected() {
        try (var messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.messageEn("mc.error.scope.conflict", "ods_iot_db")).thenReturn("conflict");
            assertThrows(ServiceException.class, () -> validate(request(requested("ods_iot_db")),null));
        }
    }
}
