package tech.qiantong.qdata.module.da.service.asset;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tech.qiantong.qdata.common.utils.SecurityUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/**
 * Manual-only test. Its fixed loopback port and synthetic database must point through an SSH
 * tunnel to the one disposable MySQL8 container; it never accepts a production JDBC URL.
 */
@EnabledIfEnvironmentVariable(named = "QDATA_MANAGED_BIND_JDBC", matches = "isolated")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedObjectBindingWitnessMysqlIntegrationTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:33573/"
            + "qdata_managed_bind_synthetic_20260929?useSSL=false"
            + "&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final long USER = 927L, ASSET = 301L;
    private final AtomicInteger sequence = new AtomicInteger();
    private PausingJdbcTemplate jdbc;
    private ManagedObjectJdbcRepository repository;

    @BeforeAll void fixture() throws SQLException {
        var source = new DriverManagerDataSource(URL, "root", "");
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        try (var connection = source.getConnection()) {
            assertEquals("qdata_managed_bind_synthetic_20260929",
                    connection.getCatalog(), "refuse any non-synthetic database");
        }
        jdbc = new PausingJdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_managed_object ("
                + "file_id CHAR(36) PRIMARY KEY,project_id BIGINT,uploader_user_id BIGINT,"
                + "purpose VARCHAR(32),backend VARCHAR(24),storage_target_alias VARCHAR(64),"
                + "object_key VARCHAR(100),media_type VARCHAR(64),byte_length BIGINT,"
                + "sha256 CHAR(64),state VARCHAR(16),bound_asset_id BIGINT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_user (user_id BIGINT PRIMARY KEY,"
                + "status CHAR(1),del_flag CHAR(1)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS da_asset (id BIGINT PRIMARY KEY,"
                + "valid_flag CHAR(1),del_flag CHAR(1),dataset_id BIGINT NULL,type CHAR(1)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS att_project (id BIGINT PRIMARY KEY,"
                + "manager_id BIGINT,valid_flag CHAR(1),del_flag CHAR(1)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS da_asset_project_rel (id BIGINT PRIMARY KEY,"
                + "asset_id BIGINT,project_id BIGINT,valid_flag CHAR(1),del_flag CHAR(1),"
                + "INDEX idx_da_asset_project_membership(asset_id,project_id,valid_flag,del_flag)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS att_project_user_rel (id BIGINT PRIMARY KEY,"
                + "project_id BIGINT,user_id BIGINT,valid_flag CHAR(1),del_flag CHAR(1),"
                + "INDEX idx_mo_project_user(project_id,user_id,id)) ENGINE=InnoDB");
        // A previous synthetic run may have created this table before the index was required.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='att_project_user_rel' "
                + "AND index_name='idx_mo_project_user'", Integer.class) == 0) {
            jdbc.execute("ALTER TABLE att_project_user_rel "
                    + "ADD INDEX idx_mo_project_user(project_id,user_id,id)");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_user_role (user_id BIGINT,role_id BIGINT,"
                + "INDEX idx_mo_user_role(user_id,role_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_role (role_id BIGINT PRIMARY KEY,"
                + "project_id BIGINT,role_key VARCHAR(32),status CHAR(1),del_flag CHAR(1)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_role_menu (role_id BIGINT,menu_id BIGINT,"
                + "project_id BIGINT,INDEX idx_mo_role_menu(role_id,menu_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_menu (menu_id BIGINT PRIMARY KEY,"
                + "status CHAR(1),perms VARCHAR(128)) ENGINE=InnoDB");
        var witness = new ManagedObjectBindingWitness(jdbc);
        witness.validateIndexShape();
        repository = new ManagedObjectJdbcRepository(jdbc,
                new DataSourceTransactionManager(source), witness, "OSS", "private-test");
    }

    @Test void actualJdbcBindOrdersEightRevocationPaths() throws Exception {
        List<Case> cases = List.of(
                new Case("user_disabled", "UPDATE system_user SET status='1' WHERE user_id=927"),
                new Case("manager_changed", "UPDATE att_project SET manager_id=928 WHERE id=144"),
                new Case("membership_revoked", "UPDATE att_project_user_rel SET valid_flag='0' WHERE id=1"),
                new Case("user_role_deleted", "DELETE FROM system_user_role WHERE user_id=927 AND role_id=5"),
                new Case("role_disabled", "UPDATE system_role SET status='1' WHERE role_id=5"),
                new Case("role_menu_deleted", "DELETE FROM system_role_menu WHERE role_id=5 AND menu_id=8"),
                new Case("menu_permission_changed", "UPDATE system_menu SET perms='da:assetFiles:query' WHERE menu_id=8"),
                new Case("global_admin_role_disabled", "UPDATE system_role SET status='1' WHERE role_id=7"),
                new Case("global_admin_assignment_deleted", "DELETE FROM system_user_role WHERE user_id=927 AND role_id=7"),
                new Case("second_project_added", "INSERT INTO da_asset_project_rel VALUES(2,301,145,'1','0')"));
        for (Case item : cases) {
            UUID before = reset(item.name());
            var pause = new Pause();
            jdbc.pause = pause;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                CompletableFuture<Boolean> bind = CompletableFuture.supplyAsync(() -> asUser(before), pool);
                assertTrue(pause.reached.await(5, TimeUnit.SECONDS), item.name() + " reached bind UPDATE");
                CompletableFuture<Integer> revoke = CompletableFuture.supplyAsync(
                        () -> jdbc.update(item.revokeSql()), pool);
                Thread.sleep(150);
                assertFalse(revoke.isDone(), item.name() + " revocation waits for locked witness");
                pause.release.countDown();
                assertTrue(bind.get(5, TimeUnit.SECONDS), item.name() + " bind first");
                assertEquals(1, revoke.get(5, TimeUnit.SECONDS), item.name() + " revoke after bind");
            } finally {
                jdbc.pause = null;
                pause.release.countDown();
            }
            assertEquals(ASSET, jdbc.queryForObject(
                    "SELECT bound_asset_id FROM sys_managed_object WHERE file_id=?",
                    Long.class, before.toString()));

            UUID after = reset(item.name());
            assertEquals(1, jdbc.update(item.revokeSql()));
            assertFalse(asUser(after), item.name() + " revoked first denies bind");
            assertNull(jdbc.queryForObject(
                    "SELECT bound_asset_id FROM sys_managed_object WHERE file_id=?",
                    Long.class, after.toString()));
            System.out.println("MANAGED_BIND_JDBC " + item.name() + " lock-first=bound-then-revoked revoked-first=denied");
        }
    }

    private boolean asUser(UUID id) {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(USER);
            return repository.bind(id, USER, ASSET);
        }
    }

    private UUID reset(String name) {
        int id = sequence.incrementAndGet();
        UUID file = UUID.fromString(String.format("00000000-0000-4000-8000-%012d", id));
        jdbc.update("DELETE FROM sys_managed_object");
        jdbc.update("DELETE FROM da_asset_project_rel");
        jdbc.update("DELETE FROM att_project_user_rel");
        jdbc.update("DELETE FROM system_user_role");
        jdbc.update("DELETE FROM system_role_menu");
        jdbc.update("DELETE FROM system_menu");
        jdbc.update("DELETE FROM system_role");
        jdbc.update("DELETE FROM att_project");
        jdbc.update("DELETE FROM da_asset");
        jdbc.update("DELETE FROM system_user");
        jdbc.update("INSERT INTO system_user VALUES(927,'0','0')");
        jdbc.update("INSERT INTO da_asset VALUES(301,'1','0',NULL,'6')");
        jdbc.update("INSERT INTO att_project VALUES(144,?,'1','0'),(145,928,'1','0')",
                "manager_changed".equals(name) ? USER : 928L);
        jdbc.update("INSERT INTO da_asset_project_rel VALUES(1,301,144,'1','0')");
        jdbc.update("INSERT INTO att_project_user_rel VALUES(1,144,927,?,'0')",
                "manager_changed".equals(name) || name.startsWith("global_admin_") ? "0" : "1");
        jdbc.update("INSERT INTO system_user_role VALUES(927,5)");
        jdbc.update("INSERT INTO system_role VALUES(5,144,'editor','0','0')");
        if (name.startsWith("global_admin_")) {
            jdbc.update("INSERT INTO system_user_role VALUES(927,7)");
            jdbc.update("INSERT INTO system_role VALUES(7,0,'admin','0','0')");
        }
        jdbc.update("INSERT INTO system_role_menu VALUES(5,8,144)");
        jdbc.update("INSERT INTO system_menu VALUES(8,'0','da:assetFiles:add')");
        jdbc.update("INSERT INTO sys_managed_object VALUES(?,144,927,'ASSET_FILE','OSS',"
                + "'private-test',?,'text/csv',2,?,'READY',NULL)",
                file.toString(), "managed/" + file, "a".repeat(64));
        return file;
    }

    private record Case(String name, String revokeSql) { }

    private static final class Pause {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static final class PausingJdbcTemplate extends JdbcTemplate {
        volatile Pause pause;
        PausingJdbcTemplate(DriverManagerDataSource source) { super(source); }
        @Override public int update(String sql, Object... args) {
            Pause current = pause;
            if (current != null && ManagedObjectJdbcRepository.BIND_SQL.equals(sql)) {
                current.reached.countDown();
                try {
                    if (!current.release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("synthetic bind pause timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("synthetic bind interrupted", interrupted);
                }
            }
            return super.update(sql, args);
        }
    }
}
