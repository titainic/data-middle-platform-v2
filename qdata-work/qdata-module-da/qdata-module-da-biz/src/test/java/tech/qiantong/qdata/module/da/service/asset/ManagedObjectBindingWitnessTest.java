package tech.qiantong.qdata.module.da.service.asset;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow.Purpose;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedObjectBindingWitnessTest {
    private static final long USER = 927L, PROJECT = 144L, ASSET = 301L;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ManagedObjectBindingWitness witness = new ManagedObjectBindingWitness(jdbc);

    @BeforeEach void setUp() {
        when(jdbc.queryForList(ManagedObjectBindingWitness.USER_LOCK, USER))
                .thenReturn(List.of(Map.of("user_id", USER)));
        Map<String, Object> asset = new HashMap<>();
        asset.put("id", ASSET);
        asset.put("type", "6");
        asset.put("dataset_id", null);
        when(jdbc.queryForList(ManagedObjectBindingWitness.ASSET_LOCK, ASSET))
                .thenReturn(List.of(asset));
        when(jdbc.queryForList(ManagedObjectBindingWitness.RELATION_RANGE_LOCK, ASSET))
                .thenReturn(List.of(Map.of("id", 1L, "project_id", PROJECT, "active", 1)));
        when(jdbc.queryForList(ManagedObjectBindingWitness.PROJECT_LOCK, PROJECT))
                .thenReturn(List.of(Map.of("id", PROJECT, "manager_id", USER, "active", 1)));
        when(jdbc.queryForList(ManagedObjectBindingWitness.ADMIN_CANDIDATES,
                Long.class, USER)).thenReturn(List.of());
    }

    @Test void rootNeedsCurrentAssetAndOneActiveProjectButNoRole() {
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.isAdmin(USER)).thenReturn(true);
            assertTrue(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
            verify(jdbc, never()).queryForList(ManagedObjectBindingWitness.GRANT_CANDIDATES,
                    USER, PROJECT);
            when(jdbc.queryForList(ManagedObjectBindingWitness.RELATION_RANGE_LOCK, ASSET))
                    .thenReturn(List.of(
                            Map.of("id", 1L, "project_id", PROJECT, "active", 1),
                            Map.of("id", 2L, "project_id", 145L, "active", 1)));
            when(jdbc.queryForList(ManagedObjectBindingWitness.PROJECT_LOCK, 145L))
                    .thenReturn(List.of(Map.of("id", 145L, "manager_id", 928L, "active", 1)));
            assertFalse(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
        }
    }

    @Test void disabledAccountBlocksEvenRootBeforeAssetOrRoleReads() {
        when(jdbc.queryForList(ManagedObjectBindingWitness.USER_LOCK, USER))
                .thenReturn(List.of());
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.isAdmin(USER)).thenReturn(true);
            assertFalse(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
        }
        verify(jdbc, never()).queryForList(ManagedObjectBindingWitness.ASSET_LOCK, ASSET);
    }

    @Test void startupRequiresExactAuthorizationIndexShapesOnlyWhenEnabled() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(ManagedObjectBindingWitness.class)
                .withBean(JdbcTemplate.class, () -> jdbc);
        context.run(beans -> assertEquals(0,
                beans.getBeansOfType(ManagedObjectBindingWitness.class).size()));
        context.withPropertyValues("qdata.managed-object.enabled=true")
                .run(beans -> assertNotNull(beans.getStartupFailure()));
        when(jdbc.queryForList(ManagedObjectBindingWitness.INDEX_SHAPE_SQL))
                .thenReturn(List.of(
                        index("system_user_role", "idx_mo_user_role", "user_id,role_id"),
                        index("system_role_menu", "idx_mo_role_menu", "role_id,menu_id"),
                        index("att_project_user_rel", "idx_mo_project_user", "project_id,user_id,id"),
                        index("da_asset_project_rel", "idx_da_asset_project_membership",
                                "asset_id,project_id,valid_flag,del_flag")));
        context.withPropertyValues("qdata.managed-object.enabled=true")
                .run(beans -> assertEquals(1,
                        beans.getBeansOfType(ManagedObjectBindingWitness.class).size()));
        when(jdbc.queryForList(ManagedObjectBindingWitness.INDEX_SHAPE_SQL))
                .thenReturn(List.of(
                        index("system_user_role", "idx_mo_user_role", "user_id,role_id"),
                        index("system_role_menu", "idx_mo_role_menu", "role_id,menu_id"),
                        index("att_project_user_rel", "idx_mo_project_user", "project_id,user_id,id"),
                        index("da_asset_project_rel", "idx_da_asset_project_membership",
                                "project_id,asset_id,valid_flag,del_flag")));
        context.withPropertyValues("qdata.managed-object.enabled=true")
                .run(beans -> assertNotNull(beans.getStartupFailure()));
        Map<String, Object> invisible = new HashMap<>(index("system_user_role",
                "idx_mo_user_role", "user_id,role_id"));
        invisible.put("visibilities", "NO");
        when(jdbc.queryForList(ManagedObjectBindingWitness.INDEX_SHAPE_SQL))
                .thenReturn(List.of(invisible,
                        index("system_role_menu", "idx_mo_role_menu", "role_id,menu_id"),
                        index("att_project_user_rel", "idx_mo_project_user", "project_id,user_id,id"),
                        index("da_asset_project_rel", "idx_da_asset_project_membership",
                                "asset_id,project_id,valid_flag,del_flag")));
        context.withPropertyValues("qdata.managed-object.enabled=true")
                .run(beans -> assertNotNull(beans.getStartupFailure()));
    }

    @Test void managerAndCommaWildcardGrantNeedCurrentLockedChain() {
        grant("da:asset:query, da:assetFiles:*");
        assertTrue(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
        when(jdbc.queryForList(ManagedObjectBindingWitness.MENU_LOCK, 8L)).thenReturn(List.of());
        assertFalse(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
    }

    @Test void memberPathLocksOnlyChosenPrimaryKeyAndRechecksIt() {
        when(jdbc.queryForList(ManagedObjectBindingWitness.PROJECT_LOCK, PROJECT))
                .thenReturn(List.of(Map.of("id", PROJECT, "manager_id", 928L, "active", 1)));
        when(jdbc.queryForList(ManagedObjectBindingWitness.MEMBER_CANDIDATES,
                Long.class, PROJECT, USER)).thenReturn(List.of(1L));
        grant("da:assetFiles:add");
        assertFalse(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
        when(jdbc.queryForList(ManagedObjectBindingWitness.MEMBER_LOCK, 1L, PROJECT, USER))
                .thenReturn(List.of(Map.of("id", 1L)));
        assertTrue(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
    }

    @Test void activeGlobalAdminRoleHasNoMenuOrMembershipRequirement() {
        when(jdbc.queryForList(ManagedObjectBindingWitness.PROJECT_LOCK, PROJECT))
                .thenReturn(List.of(Map.of("id", PROJECT, "manager_id", 928L, "active", 1)));
        when(jdbc.queryForList(ManagedObjectBindingWitness.ADMIN_CANDIDATES,
                Long.class, USER)).thenReturn(List.of(7L));
        when(jdbc.queryForList(ManagedObjectBindingWitness.USER_ROLE_LOCK, USER, 7L))
                .thenReturn(List.of(Map.of("role_id", 7L)));
        Map<String, Object> role = new HashMap<>();
        role.put("role_id", 7L);
        role.put("project_id", null);
        role.put("role_key", "admin");
        role.put("status", "0");
        role.put("del_flag", "0");
        when(jdbc.queryForList(ManagedObjectBindingWitness.ROLE_LOCK, 7L))
                .thenReturn(List.of(role));
        assertTrue(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
        role.put("status", "1");
        assertFalse(witness.authorized(USER, PROJECT, ASSET, Purpose.ASSET_FILE));
    }

    private void grant(String permission) {
        when(jdbc.queryForList(ManagedObjectBindingWitness.GRANT_CANDIDATES, USER, PROJECT))
                .thenReturn(List.of(Map.of("role_id", 5L, "menu_id", 8L,
                        "perms", permission)));
        when(jdbc.queryForList(ManagedObjectBindingWitness.USER_ROLE_LOCK, USER, 5L))
                .thenReturn(List.of(Map.of("role_id", 5L)));
        when(jdbc.queryForList(ManagedObjectBindingWitness.ROLE_LOCK, 5L))
                .thenReturn(List.of(Map.of("role_id", 5L, "project_id", PROJECT,
                        "role_key", "editor", "status", "0", "del_flag", "0")));
        when(jdbc.queryForList(ManagedObjectBindingWitness.ROLE_MENU_LOCK, 5L, 8L))
                .thenReturn(List.of(Map.of("menu_id", 8L)));
        when(jdbc.queryForList(ManagedObjectBindingWitness.MENU_LOCK, 8L))
                .thenReturn(List.of(Map.of("perms", permission)));
    }

    private static Map<String, Object> index(String table, String name, String columns) {
        return Map.of("table_name", table, "index_name", name,
                "columns_shape", columns, "non_unique_min", 1, "non_unique_max", 1,
                "index_types", "BTREE", "prefix_columns", 0, "visibilities", "YES");
    }
}
