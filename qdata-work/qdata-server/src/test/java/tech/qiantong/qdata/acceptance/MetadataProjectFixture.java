package tech.qiantong.qdata.acceptance;

import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.mc.service.metadata.MetadataProjectAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import static org.mockito.Mockito.*;

/** Legacy suites isolate membership and namespace scope; dedicated suites use real role grants. */
final class MetadataProjectFixture {
    static MetadataProjectAccess access(JdbcTemplate jdbc,DataResourceAccess resources){
        var permissions=mock(ProjectPermissionService.class);
        when(permissions.has(any(),anyString())).thenReturn(true);
        when(permissions.any(any(),any(String[].class))).thenReturn(true);
        return new MetadataProjectAccess(jdbc,resources,permissions);
    }
}
