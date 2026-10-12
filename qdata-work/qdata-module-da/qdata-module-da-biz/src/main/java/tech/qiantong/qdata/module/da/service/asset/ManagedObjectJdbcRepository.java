package tech.qiantong.qdata.module.da.service.asset;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;

/**
 * Additive metadata only. Independent commits preserve a PENDING audit record if a later object
 * write or enclosing HTTP transaction fails; neither this adapter nor the core deletes objects.
 */
@Component
@ConditionalOnProperty(prefix = "qdata.managed-object", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class ManagedObjectJdbcRepository implements ManagedObjectFlow.Repository {
    static final String INSERT_SQL = "INSERT INTO sys_managed_object "
            + "(file_id,project_id,uploader_user_id,purpose,backend,storage_target_alias,"
            + "object_key,media_type,byte_length,sha256,state,bound_asset_id) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',NULL)";
    static final String FIND_SQL = "SELECT file_id,project_id,uploader_user_id,purpose,object_key,"
            + "media_type,byte_length,sha256,state,bound_asset_id FROM sys_managed_object "
            + "WHERE file_id=? AND backend=? AND storage_target_alias=?";
    static final String BIND_OBJECT_LOCK_SQL = FIND_SQL + " FOR UPDATE NOWAIT";
    static final String TRANSITION_SQL = "UPDATE sys_managed_object SET state=? "
            + "WHERE file_id=? AND backend=? AND storage_target_alias=? "
            + "AND state='PENDING' AND bound_asset_id IS NULL";
    static final String BIND_SQL = "UPDATE sys_managed_object o "
            + "JOIN da_asset a ON a.id=? AND a.valid_flag='1' AND a.del_flag='0' "
            + "AND a.dataset_id IS NULL "
            + "SET o.bound_asset_id=? "
            + "WHERE o.file_id=? AND o.backend=? AND o.storage_target_alias=? "
            + "AND o.state='READY' AND o.bound_asset_id IS NULL "
            + "AND ((o.purpose='ASSET_FILE' AND a.type='6') "
            + "OR (o.purpose='ASSET_GEO' AND a.type='4')) "
            + "AND EXISTS (SELECT 1 FROM da_asset_project_rel r "
            + "JOIN att_project p ON p.id=r.project_id AND p.valid_flag='1' AND p.del_flag='0' "
            + "WHERE r.asset_id=a.id AND r.valid_flag='1' AND r.del_flag='0' "
            + "GROUP BY r.asset_id HAVING COUNT(DISTINCT r.project_id)=1 "
            + "AND MIN(r.project_id)=o.project_id)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final TransactionTemplate bindWrites;
    private final ManagedObjectBindingWitness witness;
    private final String backend;
    private final String targetAlias;

    public ManagedObjectJdbcRepository(JdbcTemplate jdbc,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            ManagedObjectBindingWitness witness,
            @Value("${qdata.managed-object.backend:}") String backend,
            @Value("${qdata.managed-object.target-alias:}") String targetAlias) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.writes = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.bindWrites = new TransactionTemplate(transactionManager);
        this.bindWrites.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.bindWrites.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.bindWrites.setTimeout(5);
        this.witness = Objects.requireNonNull(witness);
        this.backend = target(backend, 24, "backend");
        this.targetAlias = target(targetAlias, 64, "target alias");
    }

    @Override public void insert(ManagedObjectFlow.Record pending) {
        validatePending(pending);
        Integer rows = writes.execute(status -> jdbc.update(INSERT_SQL,
                pending.id().toString(), pending.projectId(), pending.uploaderId(),
                pending.purpose().name(), backend, targetAlias, pending.objectKey(),
                pending.mediaType(), pending.length(), pending.sha256()));
        if (!Objects.equals(rows, 1)) throw new IllegalStateException("受管对象待处理登记失败");
    }

    @Override public Optional<ManagedObjectFlow.Record> find(UUID id) {
        Objects.requireNonNull(id, "fileId");
        List<Map<String, Object>> rows = jdbc.queryForList(FIND_SQL,
                id.toString(), backend, targetAlias);
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) throw new IllegalStateException("受管对象标识不唯一");
        Map<String, Object> row = rows.get(0);
        ManagedObjectFlow.Record record = new ManagedObjectFlow.Record(
                UUID.fromString(String.valueOf(row.get("file_id"))),
                number(row.get("project_id")), number(row.get("uploader_user_id")),
                ManagedObjectFlow.Purpose.valueOf(String.valueOf(row.get("purpose"))),
                String.valueOf(row.get("object_key")), String.valueOf(row.get("media_type")),
                number(row.get("byte_length")), String.valueOf(row.get("sha256")),
                ManagedObjectFlow.Status.valueOf(String.valueOf(row.get("state"))),
                row.get("bound_asset_id") == null ? null : number(row.get("bound_asset_id")));
        if (!id.equals(record.id())) throw new IllegalStateException("受管对象标识不匹配");
        return Optional.of(record);
    }

    @Override public boolean transition(UUID id, ManagedObjectFlow.Status expected,
                                         ManagedObjectFlow.Status next) {
        Objects.requireNonNull(id, "fileId");
        if (expected != ManagedObjectFlow.Status.PENDING
                || next != ManagedObjectFlow.Status.READY
                    && next != ManagedObjectFlow.Status.FAILED) {
            throw new IllegalArgumentException("仅允许 PENDING 转 READY 或 FAILED");
        }
        return Objects.equals(writes.execute(status -> jdbc.update(TRANSITION_SQL,
                next.name(), id.toString(), backend, targetAlias)), 1);
    }

    @Override public boolean bind(UUID id, long userId, long assetId) {
        Objects.requireNonNull(id, "fileId");
        if (userId <= 0 || assetId <= 0) return false;
        try {
            return Boolean.TRUE.equals(bindWrites.execute(status -> {
                try {
                    if (!Objects.equals(SecurityUtils.getUserId(), userId)) return false;
                } catch (RuntimeException unauthenticated) {
                    return false;
                }
                List<Map<String, Object>> rows = jdbc.queryForList(BIND_OBJECT_LOCK_SQL,
                        id.toString(), backend, targetAlias);
                if (rows.size() != 1) return false;
                Map<String, Object> row = rows.get(0);
                long projectId = number(row.get("project_id"));
                if (projectId <= 0 || number(row.get("uploader_user_id")) != userId
                        || !"READY".equals(row.get("state"))
                        || row.get("bound_asset_id") != null
                        || !Objects.equals("managed/" + id, row.get("object_key"))) return false;
                ManagedObjectFlow.Purpose purpose = ManagedObjectFlow.Purpose.valueOf(
                        Objects.toString(row.get("purpose"), ""));
                return witness.authorized(userId, projectId, assetId, purpose)
                        && jdbc.update(BIND_SQL, assetId, assetId, id.toString(),
                                backend, targetAlias) == 1;
            }));
        } catch (TransientDataAccessException lockConflict) {
            // NOWAIT/deadlock/timeout: rollback the independent transaction and deny this bind.
            return false;
        }
    }

    private static void validatePending(ManagedObjectFlow.Record record) {
        Objects.requireNonNull(record, "pending");
        if (record.id() == null || record.projectId() <= 0 || record.uploaderId() <= 0
                || record.purpose() == null || record.status() != ManagedObjectFlow.Status.PENDING
                || record.assetId() != null || record.length() <= 0
                || record.length() > ManagedObjectFlow.MAX_BYTES
                || !Objects.equals("managed/" + record.id(), record.objectKey())
                || record.mediaType() == null || record.mediaType().isBlank()
                || record.mediaType().length() > 64 || record.sha256() == null
                || !record.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("受管对象待处理登记无效");
        }
    }

    private static String target(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("受管对象 " + label + " 配置无效");
        }
        return value;
    }

    private static long number(Object value) {
        if (value == null) throw new IllegalStateException("受管对象元数据字段缺失");
        return Long.parseLong(String.valueOf(value));
    }
}
