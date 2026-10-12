package tech.qiantong.qdata.module.da.service.asset;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

/**
 * Project and asset checks for the future managed-object flow. The bean is absent unless the
 * private-object feature is explicitly enabled; no route or storage adapter is registered here.
 */
@Component
@ConditionalOnProperty(prefix = "qdata.managed-object", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class ManagedObjectProjectAuthorizer implements ManagedObjectFlow.Authorizer {
    static final String ASSET_SQL = "SELECT type,dataset_id FROM da_asset "
            + "WHERE id=? AND valid_flag='1' AND del_flag='0'";
    static final String PROJECTS_SQL = "SELECT DISTINCT r.project_id FROM da_asset_project_rel r "
            + "JOIN att_project p ON p.id=r.project_id AND p.valid_flag='1' AND p.del_flag='0' "
            + "WHERE r.asset_id=? AND r.valid_flag='1' AND r.del_flag='0'";
    // A LoginUser may still carry an old global-admin role after it was revoked in MySQL.
    // Keep this candidate's project visibility tied to current database authorization.
    static final String CURRENT_PROJECT_SQL = "SELECT COUNT(*) FROM att_project p "
            + "WHERE p.id=? AND p.valid_flag='1' AND p.del_flag='0' "
            + "AND (?=1 OR p.manager_id=? OR EXISTS (SELECT 1 FROM att_project_user_rel r "
            + "WHERE r.project_id=p.id AND r.user_id=? AND r.del_flag='0' AND r.valid_flag='1'))";
    static final String CURRENT_USER_SQL = "SELECT COUNT(*) FROM system_user "
            + "WHERE user_id=? AND status='0' AND del_flag='0'";

    private final JdbcTemplate jdbc;
    private final DataResourceAccess resources;
    private final ProjectPermissionService permissions;

    public ManagedObjectProjectAuthorizer(JdbcTemplate jdbc, DataResourceAccess resources,
                                          ProjectPermissionService permissions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.resources = Objects.requireNonNull(resources);
        this.permissions = Objects.requireNonNull(permissions);
    }

    @Override public boolean canUpload(long userId, long projectId,
                                        ManagedObjectFlow.Purpose purpose) {
        String permission = addPermission(purpose);
        return permission != null && projectAllowed(userId, projectId, permission);
    }

    @Override public Long projectOfAsset(long assetId, ManagedObjectFlow.Purpose purpose) {
        String type = assetType(purpose);
        if (assetId <= 0 || type == null) return null;
        List<Map<String, Object>> assets = jdbc.queryForList(ASSET_SQL, assetId);
        if (assets.size() != 1 || assets.get(0).get("dataset_id") != null
                || !type.equals(Objects.toString(assets.get(0).get("type"), ""))) return null;
        List<Long> projects = jdbc.queryForList(PROJECTS_SQL, Long.class, assetId);
        if (projects.size() != 1) return null;
        Long project = projects.get(0);
        return project != null && project > 0 ? project : null;
    }

    @Override public boolean canBind(long userId, long projectId, long assetId,
                                      ManagedObjectFlow.Purpose purpose) {
        String permission = addPermission(purpose);
        return permission != null && currentUser(userId) && projectId > 0
                && projectAllowed(userId, projectId, permission)
                && visibleAsset(assetId)
                && Objects.equals(projectId, projectOfAsset(assetId, purpose));
    }

    @Override public boolean canReadOwn(long userId, long projectId,
                                         ManagedObjectFlow.Purpose purpose,
                                         ManagedObjectFlow.ReadMode mode) {
        String child = childPermissionPrefix(purpose);
        return child != null && mode != null
                && projectAllowed(userId, projectId, "da:asset:query")
                && projectAllowed(userId, projectId, child + "query")
                && (mode != ManagedObjectFlow.ReadMode.ORIGINAL
                    || projectAllowed(userId, projectId, "da:asset:export")
                        && projectAllowed(userId, projectId, child + "export"));
    }

    @Override public boolean canReadAsset(long userId, long projectId, long assetId,
                                           ManagedObjectFlow.Purpose purpose,
                                           ManagedObjectFlow.ReadMode mode) {
        String child = childPermissionPrefix(purpose);
        return child != null && mode != null && currentUser(userId) && projectId > 0
                && projectAllowed(userId, projectId, "da:asset:query")
                && projectAllowed(userId, projectId, child + "query")
                && (mode != ManagedObjectFlow.ReadMode.ORIGINAL
                    || projectAllowed(userId, projectId, "da:asset:export")
                        && projectAllowed(userId, projectId, child + "export"))
                && visibleAsset(assetId)
                && Objects.equals(projectId, projectOfAsset(assetId, purpose));
    }

    private boolean projectAllowed(long userId, long projectId, String permission) {
        if (!currentUser(userId) || projectId <= 0) return false;
        Set<Long> visible = resources.visibleProjectIds();
        return visible != null && visible.contains(projectId)
                && Objects.equals(jdbc.queryForObject(CURRENT_PROJECT_SQL, Long.class,
                        projectId, permissions.isAdministrator() ? 1 : 0, userId, userId), 1L)
                && permissions.has(projectId, permission);
    }

    private boolean visibleAsset(long assetId) {
        Set<Long> visible = resources.visibleAssetIds();
        return assetId > 0 && visible != null && visible.contains(assetId);
    }

    private boolean currentUser(long userId) {
        if (userId <= 0) return false;
        try {
            return Objects.equals(SecurityUtils.getUserId(), userId)
                    && Objects.equals(jdbc.queryForObject(CURRENT_USER_SQL, Long.class, userId), 1L);
        } catch (RuntimeException unauthenticated) {
            return false;
        }
    }

    private static String assetType(ManagedObjectFlow.Purpose purpose) {
        if (purpose == ManagedObjectFlow.Purpose.ASSET_FILE) return "6";
        if (purpose == ManagedObjectFlow.Purpose.ASSET_GEO) return "4";
        return null;
    }

    private static String addPermission(ManagedObjectFlow.Purpose purpose) {
        String prefix = childPermissionPrefix(purpose);
        return prefix == null ? null : prefix + "add";
    }

    private static String childPermissionPrefix(ManagedObjectFlow.Purpose purpose) {
        if (purpose == ManagedObjectFlow.Purpose.ASSET_FILE) return "da:assetFiles:";
        if (purpose == ManagedObjectFlow.Purpose.ASSET_GEO) return "da:assetGeo:";
        return null;
    }
}
