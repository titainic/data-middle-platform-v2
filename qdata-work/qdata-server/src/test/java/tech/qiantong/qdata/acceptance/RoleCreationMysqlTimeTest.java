package tech.qiantong.qdata.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tech.qiantong.qdata.common.core.domain.entity.SysRole;
import tech.qiantong.qdata.common.utils.DateUtils;
import tech.qiantong.qdata.module.system.mapper.SysRoleMapper;
import tech.qiantong.qdata.module.system.mapper.SysRoleMenuMapper;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in: only connection-local TEMPORARY fixture tables are written. No cleanup SQL. */
@EnabledIfEnvironmentVariable(named = "QDATA_ROLE_TIME_TEST_URL", matches = "jdbc:mysql:.*")
class RoleCreationMysqlTimeTest {
    @Test void utcMysqlSessionAndShanghaiJdbcRoundTripNewRolesWithoutRewritingHistory() throws Exception {
        var previous = TimeZone.getDefault();
        var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
        try {
            for (var zone : new String[]{"UTC", "Asia/Shanghai"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                try (var connection = DriverManager.getConnection(System.getenv("QDATA_ROLE_TIME_TEST_URL"),
                        System.getenv("QDATA_ROLE_TIME_TEST_USER"), System.getenv("QDATA_ROLE_TIME_TEST_PASSWORD"))) {
                    var fixture = connection.createStatement();
                    fixture.execute("SET time_zone = '+00:00'");
                    fixture.execute("CREATE TEMPORARY TABLE system_role (role_id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                            + "project_id BIGINT, role_name VARCHAR(100), role_key VARCHAR(100), role_sort INT, "
                            + "data_scope CHAR(1) DEFAULT '1', menu_check_strictly BOOLEAN, dept_check_strictly BOOLEAN, "
                            + "status CHAR(1), del_flag CHAR(1) DEFAULT '0', remark VARCHAR(500), create_by VARCHAR(64), "
                            + "create_time DATETIME, update_by VARCHAR(64), update_time DATETIME)");
                    fixture.execute("CREATE TEMPORARY TABLE system_user_role (role_id BIGINT, user_id BIGINT)");
                    fixture.execute("CREATE TEMPORARY TABLE system_user (user_id BIGINT, dept_id BIGINT)");
                    fixture.execute("CREATE TEMPORARY TABLE system_dept (dept_id BIGINT)");
                    fixture.execute("CREATE TEMPORARY TABLE system_oper_log (oper_time DATETIME)");
                    fixture.execute("INSERT INTO system_role (role_name, create_time) VALUES "
                            + "('historical-role', '2026-10-15 13:40:17')");
                    var configuration = RoleCreationTimeRegressionTest.mapperConfiguration();
                    configuration.setEnvironment(new Environment("isolated-role-time", new JdbcTransactionFactory(),
                            new SingleConnectionDataSource(connection, true)));
                    var factory = new SqlSessionFactoryBuilder().build(configuration);
                    try (var session = factory.openSession(true)) {
                        var mapper = session.getMapper(SysRoleMapper.class);
                        var menus = mock(SysRoleMenuMapper.class);
                        var service = RoleCreationTimeRegressionTest.service(mapper, menus);
                        for (var instant : new String[]{"2026-10-15T13:40:17Z", "2026-10-15T16:00:01Z"}) {
                            var event = Date.from(Instant.parse(instant));
                            var role = RoleCreationTimeRegressionTest.role();
                            role.setCreateTime(Date.from(Instant.EPOCH));
                            try (var clock = mockStatic(DateUtils.class)) {
                                clock.when(DateUtils::getNowDate).thenReturn(event);
                                assertEquals(1, service.insertRole(role));
                            }
                            assertNotNull(role.getRoleId());
                            // Same parameter binding as the production audit mapper.
                            try (var audit = connection.prepareStatement("INSERT INTO system_oper_log VALUES (?)")) {
                                audit.setTimestamp(1, new java.sql.Timestamp(event.getTime())); audit.executeUpdate();
                            }
                            String raw;
                            try (var query = connection.prepareStatement("SELECT CAST(create_time AS CHAR) FROM system_role WHERE role_id=?")) {
                                query.setLong(1, role.getRoleId());
                                try (var rs = query.executeQuery()) { assertTrue(rs.next()); raw = rs.getString(1); }
                            }
                            assertEquals(format.format(event.toInstant()), raw);
                            var filter = new SysRole(); filter.setRoleId(role.getRoleId());
                            var row = mapper.selectRoleList(filter).get(0);
                            assertEquals(event.toInstant(), row.getCreateTime().toInstant());
                            var json = new ObjectMapper().setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
                            assertEquals(raw, json.readTree(json.writeValueAsString(row)).path("createTime").asText());
                            try (var rs = fixture.executeQuery("SELECT CAST(oper_time AS CHAR) FROM system_oper_log ORDER BY oper_time DESC LIMIT 1")) {
                                assertTrue(rs.next()); assertEquals(raw, rs.getString(1));
                            }
                            System.out.println("ROLE_TIME_PROBE jvm=" + zone + " session=UTC event=" + instant + " mysql=" + raw);
                        }
                        verifyNoInteractions(menus);
                        try (var rs = fixture.executeQuery("SELECT CAST(create_time AS CHAR) FROM system_role WHERE role_name='historical-role'")) {
                            assertTrue(rs.next()); assertEquals("2026-10-15 13:40:17", rs.getString(1));
                        }
                    }
                    fixture.close();
                }
            }
        } finally { TimeZone.setDefault(previous); }
    }
}
