package tech.qiantong.qdata.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.core.domain.entity.SysRole;
import tech.qiantong.qdata.common.utils.DateUtils;
import tech.qiantong.qdata.module.system.mapper.SysRoleMapper;
import tech.qiantong.qdata.module.system.mapper.SysRoleMenuMapper;
import tech.qiantong.qdata.module.system.service.impl.SysRoleServiceImpl;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs without a database; the companion MySQL test verifies driver conversion and round trips. */
class RoleCreationTimeRegressionTest {
    static Configuration mapperConfiguration() throws Exception {
        var configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("SysRole", SysRole.class);
        try (var input = RoleCreationTimeRegressionTest.class.getClassLoader()
                .getResourceAsStream("mapper/system/SysRoleMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, "mapper/system/SysRoleMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    static SysRole role() {
        var role = new SysRole();
        role.setRoleName("BIN47 time fixture"); role.setRoleKey("bin47_time");
        role.setRoleSort(47); role.setStatus("0"); role.setCreateBy("fixture-admin");
        role.setMenuIds(new Long[0]);
        return role;
    }

    static SysRoleServiceImpl service(SysRoleMapper mapper, SysRoleMenuMapper menus) {
        var service = new SysRoleServiceImpl();
        ReflectionTestUtils.setField(service, "roleMapper", mapper);
        ReflectionTestUtils.setField(service, "roleMenuMapper", menus);
        return service;
    }

    @Test void serverEventTimeOverridesClientTimeInEitherJvmZone() {
        var previous = TimeZone.getDefault();
        try {
            for (var zone : new String[]{"UTC", "Asia/Shanghai"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var mapper = mock(SysRoleMapper.class); var menus = mock(SysRoleMenuMapper.class);
                var role = role(); role.setCreateTime(Date.from(Instant.parse("2000-01-01T00:00:00Z")));
                var event = Date.from(Instant.parse("2026-10-15T13:40:17Z"));
                try (var clock = mockStatic(DateUtils.class)) {
                    clock.when(DateUtils::getNowDate).thenReturn(event);
                    assertEquals(1, service(mapper, menus).insertRole(role));
                }
                assertEquals(event, role.getCreateTime());
                verify(mapper).insertRole(same(role)); verifyNoMoreInteractions(mapper);
                verifyNoInteractions(menus);
                assertNull(role.getProjectId()); assertEquals("fixture-admin", role.getCreateBy());
            }
        } finally { TimeZone.setDefault(previous); }
    }

    @Test void mapperBindsTheEventInstantInsteadOfTheDatabaseWallClock() throws Exception {
        var role = role(); role.setCreateTime(Date.from(Instant.parse("2026-10-15T16:00:01Z")));
        var statement = mapperConfiguration().getMappedStatement(SysRoleMapper.class.getName() + ".insertRole");
        var sql = statement.getBoundSql(role);
        assertFalse(sql.getSql().toLowerCase().contains("sysdate"));
        var parameters = sql.getParameterMappings();
        int timeIndex = -1;
        for (int i = 0; i < parameters.size(); i++)
            if ("createTime".equals(parameters.get(i).getProperty())) timeIndex = i + 1;
        assertTrue(timeIndex > 0);
        var jdbc = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, role, sql).setParameters(jdbc);
        verify(jdbc).setTimestamp(timeIndex, new Timestamp(role.getCreateTime().getTime()));
    }

    @Test void insertFailurePropagatesBeforeAnyMenuGrant() {
        var mapper = mock(SysRoleMapper.class); var menus = mock(SysRoleMenuMapper.class);
        var failure = new IllegalStateException("fixture insert failed");
        when(mapper.insertRole(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service(mapper, menus).insertRole(role())));
        verifyNoInteractions(menus);
    }

    @Test void listSerializationPreservesAnExistingHistoricalWallClock() throws Exception {
        var mapper = new ObjectMapper().setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        var historical = role();
        historical.setCreateTime(Date.from(Instant.parse("2026-10-15T05:40:17Z")));
        var insert = role();
        service(mock(SysRoleMapper.class), mock(SysRoleMenuMapper.class)).insertRole(insert);
        assertEquals("2026-10-15 13:40:17", mapper.readTree(mapper.writeValueAsString(historical))
                .path("createTime").asText());
    }
}
