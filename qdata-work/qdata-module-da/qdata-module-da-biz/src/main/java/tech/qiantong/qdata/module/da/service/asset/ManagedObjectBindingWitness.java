package tech.qiantong.qdata.module.da.service.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

/**
 * A current MySQL authorization witness for one managed-object bind. Call only inside the same
 * REPEATABLE_READ transaction as the final bind UPDATE. Every row that actually grants access is
 * locked until that UPDATE commits; an absent or changing witness fails closed.
 */
@Component
@ConditionalOnProperty(prefix = "qdata.managed-object", name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class ManagedObjectBindingWitness {
    static final String INDEX_SHAPE_SQL = "SELECT table_name,index_name,"
            + "GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS columns_shape,"
            + "MIN(non_unique) AS non_unique_min,MAX(non_unique) AS non_unique_max,"
            + "GROUP_CONCAT(DISTINCT index_type) AS index_types,"
            + "SUM(sub_part IS NOT NULL) AS prefix_columns,"
            + "GROUP_CONCAT(DISTINCT is_visible) AS visibilities "
            + "FROM information_schema.statistics WHERE table_schema=DATABASE() "
            + "AND ((table_name='system_user_role' AND index_name='idx_mo_user_role') "
            + "OR (table_name='system_role_menu' AND index_name='idx_mo_role_menu') "
            + "OR (table_name='att_project_user_rel' AND index_name='idx_mo_project_user') "
            + "OR (table_name='da_asset_project_rel' "
            + "AND index_name='idx_da_asset_project_membership')) "
            + "GROUP BY table_name,index_name";
    // This is intentionally stricter than ProjectPermissionService.has(), which trusts an
    // existing login even if the account was later disabled. Bind uses current account state.
    static final String USER_LOCK = "SELECT user_id FROM system_user "
            + "WHERE user_id=? AND status='0' AND del_flag='0' FOR SHARE NOWAIT";
    static final String ASSET_LOCK = "SELECT id,type,dataset_id FROM da_asset "
            + "WHERE id=? AND valid_flag='1' AND del_flag='0' FOR UPDATE NOWAIT";
    // A complete asset_id range under InnoDB REPEATABLE READ locks the next-key gap as well.
    static final String RELATION_RANGE_LOCK = "SELECT id,project_id,"
            + "(valid_flag='1' AND del_flag='0') AS active "
            + "FROM da_asset_project_rel FORCE INDEX(idx_da_asset_project_membership) "
            + "WHERE asset_id=? FOR UPDATE NOWAIT";
    static final String PROJECT_LOCK = "SELECT id,manager_id,"
            + "(valid_flag='1' AND del_flag='0') AS active "
            + "FROM att_project WHERE id=? FOR SHARE NOWAIT";
    static final String MEMBER_CANDIDATES = "SELECT id FROM att_project_user_rel "
            + "FORCE INDEX(idx_mo_project_user) "
            + "WHERE project_id=? AND user_id=? AND valid_flag='1' AND del_flag='0' "
            + "ORDER BY id LIMIT 33";
    static final String MEMBER_LOCK = "SELECT id FROM att_project_user_rel "
            + "WHERE id=? AND project_id=? AND user_id=? AND valid_flag='1' AND del_flag='0' "
            + "FOR SHARE NOWAIT";
    static final String ADMIN_CANDIDATES = "SELECT r.role_id FROM system_user_role ur "
            + "FORCE INDEX(idx_mo_user_role) "
            + "JOIN system_role r ON r.role_id=ur.role_id WHERE ur.user_id=? "
            + "AND r.role_key='admin' AND COALESCE(r.project_id,0)=0 "
            + "AND r.status='0' AND r.del_flag='0' ORDER BY r.role_id LIMIT 33";
    // Keep the same predicates as ProjectPermissionService.has(); rm.project_id is intentionally
    // not filtered because the current permission service does not filter it.
    static final String GRANT_CANDIDATES = "SELECT ur.role_id,rm.menu_id,m.perms "
            + "FROM system_user_role ur FORCE INDEX(idx_mo_user_role) "
            + "JOIN system_role r ON r.role_id=ur.role_id "
            + "JOIN system_role_menu rm FORCE INDEX(idx_mo_role_menu) ON rm.role_id=r.role_id "
            + "JOIN system_menu m ON m.menu_id=rm.menu_id WHERE ur.user_id=? "
            + "AND (COALESCE(r.project_id,0)=0 OR r.project_id=?) "
            + "AND r.status='0' AND r.del_flag='0' AND m.status='0' "
            + "AND m.perms IS NOT NULL ORDER BY ur.role_id,rm.menu_id LIMIT 513";
    static final String USER_ROLE_LOCK = "SELECT role_id FROM system_user_role "
            + "FORCE INDEX(idx_mo_user_role) "
            + "WHERE user_id=? AND role_id=? FOR SHARE NOWAIT";
    static final String ROLE_LOCK = "SELECT role_id,project_id,role_key,status,del_flag "
            + "FROM system_role WHERE role_id=? FOR SHARE NOWAIT";
    static final String ROLE_MENU_LOCK = "SELECT menu_id FROM system_role_menu "
            + "FORCE INDEX(idx_mo_role_menu) "
            + "WHERE role_id=? AND menu_id=? FOR SHARE NOWAIT";
    static final String MENU_LOCK = "SELECT perms FROM system_menu "
            + "WHERE menu_id=? AND status='0' AND perms IS NOT NULL FOR SHARE NOWAIT";

    private final JdbcTemplate jdbc;

    ManagedObjectBindingWitness(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @PostConstruct
    void validateIndexShape() {
        List<Map<String, Object>> rows = jdbc.queryForList(INDEX_SHAPE_SQL);
        boolean userRole = rows.stream().anyMatch(row -> exactIndex(row,
                "system_user_role", "idx_mo_user_role", "user_id,role_id"));
        boolean roleMenu = rows.stream().anyMatch(row -> exactIndex(row,
                "system_role_menu", "idx_mo_role_menu", "role_id,menu_id"));
        boolean projectUser = rows.stream().anyMatch(row -> exactIndex(row,
                "att_project_user_rel", "idx_mo_project_user", "project_id,user_id,id"));
        boolean assetProjects = rows.stream().anyMatch(row -> exactIndex(row,
                "da_asset_project_rel", "idx_da_asset_project_membership",
                "asset_id,project_id,valid_flag,del_flag"));
        if (rows.size() != 4 || !userRole || !roleMenu || !projectUser || !assetProjects) {
            throw new IllegalStateException("受管对象绑定授权索引缺失或形态错误，保持功能关闭");
        }
    }

    private static boolean exactIndex(Map<String, Object> row, String table,
                                      String index, String columns) {
        return table.equals(row.get("table_name")) && index.equals(row.get("index_name"))
                && columns.equals(row.get("columns_shape"))
                && number(row.get("non_unique_min")) == 1
                && number(row.get("non_unique_max")) == 1
                && "BTREE".equals(row.get("index_types"))
                && number(row.get("prefix_columns")) == 0
                && "YES".equals(row.get("visibilities"));
    }

    boolean authorized(long userId, long projectId, long assetId,
                       ManagedObjectFlow.Purpose purpose) {
        if (userId <= 0 || projectId <= 0 || assetId <= 0 || purpose == null) return false;
        if (jdbc.queryForList(USER_LOCK, userId).size() != 1) return false;
        List<Map<String, Object>> assets = jdbc.queryForList(ASSET_LOCK, assetId);
        String requiredType = purpose == ManagedObjectFlow.Purpose.ASSET_FILE ? "6" : "4";
        if (assets.size() != 1 || assets.get(0).get("dataset_id") != null
                || !requiredType.equals(Objects.toString(assets.get(0).get("type"), ""))) return false;

        List<Map<String, Object>> relations = jdbc.queryForList(RELATION_RANGE_LOCK, assetId);
        if (relations.size() > 64) return false;
        Set<Long> candidates = new TreeSet<>();
        for (Map<String, Object> relation : relations) {
            if (active(relation.get("active"))) candidates.add(number(relation.get("project_id")));
        }
        Set<Long> activeProjects = new TreeSet<>();
        Map<String, Object> target = null;
        for (Long candidate : candidates) {
            List<Map<String, Object>> projects = jdbc.queryForList(PROJECT_LOCK, candidate);
            if (projects.size() > 1) return false;
            if (!projects.isEmpty() && active(projects.get(0).get("active"))) {
                activeProjects.add(candidate);
                if (candidate == projectId) target = projects.get(0);
            }
        }
        if (target == null || activeProjects.size() != 1
                || !activeProjects.contains(projectId)) return false;

        if (SecurityUtils.isAdmin(userId)) return true;
        if (globalAdminWitness(userId)) return true;
        if (number(target.get("manager_id")) != userId && !memberWitness(userId, projectId)) return false;
        String permission = purpose == ManagedObjectFlow.Purpose.ASSET_FILE
                ? "da:assetFiles:add" : "da:assetGeo:add";
        return functionalWitness(userId, projectId, permission);
    }

    private boolean memberWitness(long userId, long projectId) {
        List<Long> ids = jdbc.queryForList(MEMBER_CANDIDATES, Long.class, projectId, userId);
        if (ids.size() > 32) return false;
        for (Long id : ids) {
            if (id != null && !jdbc.queryForList(MEMBER_LOCK, id, projectId, userId).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean globalAdminWitness(long userId) {
        List<Long> roleIds = jdbc.queryForList(ADMIN_CANDIDATES, Long.class, userId);
        if (roleIds.size() > 32) return false;
        for (Long roleId : roleIds) {
            if (roleId == null || jdbc.queryForList(USER_ROLE_LOCK, userId, roleId).isEmpty()) continue;
            List<Map<String, Object>> roles = jdbc.queryForList(ROLE_LOCK, roleId);
            if (roles.size() != 1) continue;
            Map<String, Object> role = roles.get(0);
            if ("admin".equals(role.get("role_key")) && enabled(role)
                    && global(role.get("project_id"))) return true;
        }
        return false;
    }

    private boolean functionalWitness(long userId, long projectId, String permission) {
        List<Map<String, Object>> rows = jdbc.queryForList(GRANT_CANDIDATES, userId, projectId);
        if (rows.size() > 512) return false;
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (ProjectPermissionService.matchesGrant(
                    Objects.toString(row.get("perms"), null), permission)) matches.add(row);
        }
        for (Map<String, Object> match : matches) {
            long roleId = number(match.get("role_id"));
            long menuId = number(match.get("menu_id"));
            if (jdbc.queryForList(USER_ROLE_LOCK, userId, roleId).isEmpty()) continue;
            List<Map<String, Object>> roles = jdbc.queryForList(ROLE_LOCK, roleId);
            if (roles.size() != 1 || !enabled(roles.get(0))
                    || !inScope(roles.get(0).get("project_id"), projectId)) continue;
            if (jdbc.queryForList(ROLE_MENU_LOCK, roleId, menuId).isEmpty()) continue;
            List<Map<String, Object>> menus = jdbc.queryForList(MENU_LOCK, menuId);
            if (menus.size() == 1 && ProjectPermissionService.matchesGrant(
                    Objects.toString(menus.get(0).get("perms"), null), permission)) return true;
        }
        return false;
    }

    private static boolean enabled(Map<String, Object> role) {
        return "0".equals(Objects.toString(role.get("status"), ""))
                && "0".equals(Objects.toString(role.get("del_flag"), ""));
    }

    private static boolean global(Object project) {
        return project == null || number(project) == 0;
    }

    private static boolean inScope(Object project, long expected) {
        return global(project) || number(project) == expected;
    }

    private static boolean active(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.longValue() == 1
                || "1".equals(value);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue()
                : Long.parseLong(Objects.toString(value, "-1"));
    }
}
