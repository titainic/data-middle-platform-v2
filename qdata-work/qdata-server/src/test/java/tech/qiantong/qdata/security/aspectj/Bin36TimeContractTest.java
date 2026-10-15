package tech.qiantong.qdata.security.aspectj;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import tech.qiantong.qdata.config.ApplicationConfig;
import tech.qiantong.qdata.common.core.domain.BaseEntity;
import tech.qiantong.qdata.module.att.controller.admin.project.vo.AttProjectRespVO;
import tech.qiantong.qdata.module.system.domain.SysOperLog;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class Bin36TimeContractTest {
    @Test void utcHostProducesTheSameShanghaiTimeForProjectEntityAndAudit() throws Exception {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            var builder = new Jackson2ObjectMapperBuilder();
            builder.featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            new ApplicationConfig().jacksonObjectMapperCustomization().customize(builder);
            com.fasterxml.jackson.databind.ObjectMapper mapper = builder.build();
            Date event = Date.from(Instant.parse("2026-10-15T10:08:18Z"));
            var project = new AttProjectRespVO(); project.setCreateTime(event);
            var entity = new BaseEntity(); entity.setCreateTime(event);
            var audit = new SysOperLog(); audit.setOperTime(event);
            for (Object value : new Object[]{project, entity, audit}) {
                assertTrue(mapper.writeValueAsString(value).contains("2026-10-15"));
                assertTrue(mapper.writeValueAsString(value).contains("18:08:18"));
            }
            String auditJson = LogAspect.auditJson(Map.of("createTime", event, "password", "sensitive-test-value"),
                    new LogAspect().excludePropertyPreFilter(new String[0]));
            assertTrue(auditJson.contains("2026-10-15 18:08:18"));
            assertFalse(auditJson.contains("sensitive-test-value"));
        } finally { TimeZone.setDefault(before); }
    }
}
