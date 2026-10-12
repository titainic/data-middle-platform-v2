package tech.qiantong.qdata.module.dpp.controller.admin.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.utils.MessageUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QualityLogBoundaryTest {
    @TempDir Path directory;
    DppQualityLogController registered(Path file) {
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Long.class),eq(file.toString()))).thenReturn(1L);
        var controller=new DppQualityLogController();ReflectionTestUtils.setField(controller,"jdbc",jdbc);
        ReflectionTestUtils.setField(controller,"qualityAccess",mock(tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess.class));return controller;
    }
    @Test void authorizedLogReadRedactsCredentialsWithoutRewritingTheStoredEvidence() throws Exception {
        String raw="water rows=5002\npassword=fixture-password\nAuthorization: Bearer fixture-token\nurl=https://user:fixture-url@host/path?api_key=fixture-key&release=visible\n";
        Path file=Files.writeString(directory.resolve("registered.log"),raw);
        try(var messages=mockStatic(MessageUtils.class)) {
            var response=registered(file).logDetailCat(file.toString());assertEquals(200,response.getCode());String visible=response.getContent().getLogContent();
            for(String value:java.util.List.of("fixture-password","fixture-token","fixture-url","fixture-key"))assertFalse(visible.contains(value),value);
            assertTrue(visible.contains("water rows=5002"));assertTrue(visible.contains("release=visible"));assertTrue(visible.contains("[REDACTED]"));assertEquals(raw,Files.readString(file));
        }
    }
    @Test void nestedJsonCredentialsAreMaskedInTheActualLogResponse() throws Exception {
        Path file=Files.writeString(directory.resolve("structured.log"),"{\"task\":39,\"config\":{\"password\":\"fixture-json-password\"},\"headers\":[{\"paramName\":\"Authorization\",\"value\":\"fixture-json-token\"}]}");
        try(var messages=mockStatic(MessageUtils.class)) {
            var response=registered(file).logDetailCat(file.toString());assertEquals(200,response.getCode());String visible=response.getContent().getLogContent();
            assertFalse(visible.contains("fixture-json-password"));assertFalse(visible.contains("fixture-json-token"));assertEquals(39,com.alibaba.fastjson2.JSON.parseObject(visible).getIntValue("task"));
        }
    }
    @Test void aRealButUnregisteredFileCannotBeReadAsAQualityLog() throws Exception {
        Path file=Files.writeString(directory.resolve("private-sentinel.txt"),"private-unit-content");
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Long.class),eq(file.toString()))).thenReturn(0L);
        var controller=new DppQualityLogController();ReflectionTestUtils.setField(controller,"jdbc",jdbc);
        ReflectionTestUtils.setField(controller,"qualityAccess",mock(tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess.class));
        try(var messages=mockStatic(MessageUtils.class)) {
            var response=controller.logDetailCat(file.toString());assertEquals(500,response.getCode());assertNull(response.getContent());
        }
    }
    @Test void aRegisteredFileStillRequiresItsOwningProject() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var access=mock(tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess.class);var controller=new DppQualityLogController();ReflectionTestUtils.setField(controller,"jdbc",jdbc);ReflectionTestUtils.setField(controller,"qualityAccess",access);
        doThrow(new org.springframework.security.access.AccessDeniedException("Denied")).when(access).logPath("registered-file");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.logDetailCat("registered-file"));verifyNoInteractions(jdbc);
    }
}
