package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import tech.qiantong.qdata.api.ds.api.etl.DsTaskSaveReqDTO;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlNodeService;
import tech.qiantong.qdata.api.ds.api.service.etl.IDsEtlTaskService;
import tech.qiantong.qdata.module.dp.api.warehouse.WarehouseBatchExecution;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskInstanceDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService;
import tech.qiantong.qdata.module.dpp.utils.TaskConverter;
import tech.qiantong.qdata.redis.service.IRedisService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** DolphinScheduler schedules the real model DAG; every HTTP node executes one guarded qData step. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseNativeDolphinService {
    private static final int LEASE_SECONDS = 120;
    private final ObjectProvider<IDppEtlTaskService> taskServices;
    private final ObjectProvider<DataDevelopmentJdbcTaskRunner> runners;
    private final ObjectProvider<WarehouseBatchExecution> warehouseExecutions;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IDsEtlTaskService dolphin;
    private final IDsEtlNodeService dolphinNodes;
    private final IRedisService redis;
    private final SecureRandom random = new SecureRandom();
    @Value("${warehouse.ds.health-url:}") private String healthUrl;

    private IDppEtlTaskService tasks() { return taskServices.getObject(); }
    private static String lockKey(long taskId) { return "dpp:data-development:run:" + taskId; }

    public void start(long taskId, String batchId, String projectCode) {
        UUID.fromString(batchId);
        if (healthUrl == null || !healthUrl.matches("https?://[^/]+/actuator/health"))
            throw new IllegalStateException("DolphinScheduler Worker 内网地址未配置");
        var task = tasks().getById(taskId);
        if (task == null || !batchId.equals(task.getWarehouseBatchId()))
            throw new IllegalArgumentException("固定批次开发任务不匹配");
        var batch = jdbc.queryForMap("SELECT task_id,status FROM dp_warehouse_batch WHERE id=?", batchId);
        if (!"WAITING_DS".equals(batch.get("status")) || ((Number) batch.get("task_id")).longValue() != taskId)
            throw new IllegalStateException("数仓批次不在 DS 提交状态");
        var steps = jdbc.queryForList("SELECT ordinal,node_code,definition_json,dependencies_json FROM dp_warehouse_batch_step WHERE batch_id=? ORDER BY ordinal", batchId);
        if (steps.isEmpty() || steps.size() > 100) throw new IllegalArgumentException("固定五层步骤数量无效");
        var codes = new LinkedHashMap<Integer,Long>();
        var tokens = new LinkedHashMap<Integer,String>();
        for (int i = 0; i < steps.size(); i++) {
            int ordinal = ((Number) steps.get(i).get("ordinal")).intValue();
            if (ordinal != i || steps.get(i).get("node_code") == null) throw new IllegalStateException("固定模型节点序号不连续");
            var result = dolphinNodes.genCode(Long.valueOf(projectCode));
            if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null || result.getData().size() != 1)
                throw new IllegalStateException("DS 节点编码不可用");
            codes.put(ordinal, result.getData().get(0));
            byte[] secret = new byte[32]; random.nextBytes(secret);
            tokens.put(ordinal, HexFormat.of().formatHex(secret));
        }
        if (new HashSet<>(codes.values()).size() != codes.size()) throw new IllegalStateException("DS 节点编码重复");
        String base = healthUrl.substring(0, healthUrl.length() - "/actuator/health".length());
        var definitions = new ArrayList<Map<String,Object>>();
        var relations = new ArrayList<Map<String,Object>>();
        var locations = new ArrayList<Map<String,Object>>();
        for (var step : steps) {
            int ordinal = ((Number) step.get("ordinal")).intValue();
            var definition = JSON.parseObject(String.valueOf(step.get("definition_json")));
            String name = String.format("%02d %s %s", ordinal + 1, definition.getString("layer"), definition.getString("name"));
            if (name.length() > 100) name = name.substring(0, 100);
            var params = new LinkedHashMap<String,Object>();
            params.put("localParams", List.of()); params.put("resourceList", List.of());
            params.put("httpMethod", "POST"); params.put("httpBody", JSON.toJSONString(Map.of("token", tokens.get(ordinal))));
            params.put("httpCheckCondition", "STATUS_CODE_DEFAULT"); params.put("httpParams", List.of());
            params.put("url", base + "/dp/warehouse/ds-internal/" + batchId + "/" + ordinal);
            params.put("condition", ""); params.put("connectTimeout", 1800000); params.put("socketTimeout", 1800000);
            var node = new LinkedHashMap<String,Object>();
            node.put("name", name); node.put("code", codes.get(ordinal)); node.put("version", 1);
            node.put("workerGroup", "default"); node.put("environmentCode", -1); node.put("flag", "YES");
            node.put("isCache", "NO"); node.put("taskPriority", "MEDIUM"); node.put("taskType", "HTTP");
            node.put("taskExecuteType", "BATCH"); node.put("taskParams", params);
            definitions.add(node);
            var frozenParents = JSON.parseArray(String.valueOf(step.get("dependencies_json")), Integer.class);
            var parents = new LinkedHashSet<Integer>();
            if (frozenParents != null) parents.addAll(frozenParents);
            // The managed batch ledger is shared by all steps; keep DS nodes ordered until it supports parallel writes.
            if (ordinal > 0) parents.add(ordinal - 1);
            if (parents.isEmpty()) {
                relations.add(relation(0L, codes.get(ordinal)));
            } else for (int parent : parents) {
                if (parent >= ordinal || !codes.containsKey(parent)) throw new IllegalStateException("DS 模型依赖不是已登记的上游节点");
                relations.add(relation(codes.get(parent), codes.get(ordinal)));
            }
            locations.add(Map.of("taskCode", codes.get(ordinal), "x", 80 + ordinal % 5 * 260, "y", 100 + ordinal / 5 * 160));
        }
        var spec = DsTaskSaveReqDTO.builder().name("warehouse_" + batchId)
                .description("DS 原生五层模型 DAG；每个 HTTP 节点调用 qData 固定版本单步执行器")
                .executionType("SERIAL_WAIT").taskDefinitionJson(JSON.toJSONString(definitions))
                .taskRelationJson(JSON.toJSONString(relations)).locations(JSON.toJSONString(locations)).build();
        var created = dolphin.createTask(spec, Long.valueOf(projectCode));
        if (created == null || !Boolean.TRUE.equals(created.getSuccess()) || created.getData() == null || created.getData().getCode() == null)
            throw new IllegalStateException("DS 五层工作流创建失败");
        String workflowCode = created.getData().getCode();
        var release = new DppEtlNewNodeSaveReqVO(); release.setId(String.valueOf(taskId));
        release.setProjectId(task.getProjectId()); release.setProjectCode(Long.valueOf(task.getProjectCode()));
        release.setReleaseState("1"); tasks().updateReleaseJobTask(release);
        String owner = UUID.randomUUID().toString();
        if (!redis.tryAcquireLock(lockKey(taskId), owner, LEASE_SECONDS)) throw new IllegalStateException("固定开发任务已有执行者");
        long localInstanceId = -1;
        boolean ledgerSaved = false;
        try {
            localInstanceId = runners.getObject().createDataDevelopmentTaskInstance(task).getId();
            long instance = localInstanceId;
            transaction.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO dp_warehouse_ds_native_run(batch_id,task_id,task_instance_id,project_code,workflow_code,lease_owner,state) VALUES (?,?,?,?,?,?,'PREPARED')",
                        batchId, taskId, instance, projectCode, workflowCode, owner);
                for (var entry : codes.entrySet()) jdbc.update("INSERT INTO dp_warehouse_ds_native_node(batch_id,ordinal,ds_node_code,token_sha256) VALUES (?,?,?,?)",
                        batchId, entry.getKey(), String.valueOf(entry.getValue()), sha256(tokens.get(entry.getKey())));
            });
            ledgerSaved = true;
            var online = dolphin.releaseTask("ONLINE", projectCode, workflowCode);
            if (online == null || !Boolean.TRUE.equals(online.getSuccess())) throw new IllegalStateException("DS 五层工作流上线失败");
            var started = dolphin.startTask(TaskConverter.createDsStartTaskReqDTO(workflowCode), projectCode);
            if (started == null || !Boolean.TRUE.equals(started.getSuccess()) || !(started.getData() instanceof List<?> instances)
                    || instances.size() != 1 || !(instances.get(0) instanceof Number dsInstance))
                throw new IllegalStateException("DS 五层实例提交结果不明确，禁止原地重试");
            if (jdbc.update("UPDATE dp_warehouse_ds_native_run SET instance_id=?,state='RUNNING' WHERE batch_id=? AND state='PREPARED'",
                    dsInstance.longValue(), batchId) != 1) throw new IllegalStateException("DS 实例已提交但对账记录未更新");
        } catch (Exception error) {
            if (ledgerSaved) jdbc.update("UPDATE dp_warehouse_ds_native_run SET state='UNKNOWN',message='提交结果不明，禁止原地重试' WHERE batch_id=? AND state='PREPARED'", batchId);
            if (localInstanceId > 0) jdbc.update("UPDATE dpp_etl_task_instance SET status='6',end_time=NOW(),remark=? WHERE id=? AND status='1'",
                    "DS 五层提交失败或结果不明", localInstanceId);
            redis.releaseLock(lockKey(taskId), owner);
            throw error instanceof RuntimeException ? (RuntimeException) error : new IllegalStateException(error);
        }
    }

    private static Map<String,Object> relation(long parent, long child) {
        return Map.of("preTaskCode", parent, "preTaskVersion", parent == 0 ? 0 : 1, "postTaskCode", child,
                "postTaskVersion", 1, "conditionType", "NONE", "conditionParams", Map.of());
    }

    /** Called only by a DS Worker possessing the one-step random capability in the HTTP body. */
    public void executeNode(String batchId, int ordinal, String token) {
        UUID.fromString(batchId);
        if (ordinal < 0 || ordinal >= 100 || token == null || !token.matches("[0-9a-f]{64}")) throw new SecurityException("DS 节点凭证无效");
        var runRows = jdbc.queryForList("SELECT * FROM dp_warehouse_ds_native_run WHERE batch_id=?", batchId);
        var nodeRows = jdbc.queryForList("SELECT ds_node_code,token_sha256 FROM dp_warehouse_ds_native_node WHERE batch_id=? AND ordinal=?", batchId, ordinal);
        if (runRows.size() != 1 || nodeRows.size() != 1 || !constantTimeEqual(sha256(token), String.valueOf(nodeRows.get(0).get("token_sha256"))))
            throw new SecurityException("DS 节点凭证无效");
        var run = runRows.get(0);
        if (!Set.of("PREPARED", "RUNNING").contains(String.valueOf(run.get("state")))) throw new IllegalStateException("DS 工作流不在执行状态");
        long taskId = ((Number) run.get("task_id")).longValue();
        long localInstanceId = ((Number) run.get("task_instance_id")).longValue();
        String owner = String.valueOf(run.get("lease_owner"));
        if (!redis.renewLock(lockKey(taskId), owner, LEASE_SECONDS)) throw new IllegalStateException("DS 五层执行租约已失效");
        var batch = jdbc.queryForMap("SELECT status,task_id FROM dp_warehouse_batch WHERE id=?", batchId);
        if (((Number) batch.get("task_id")).longValue() != taskId || !Set.of("WAITING_DS", "RUNNING").contains(String.valueOf(batch.get("status"))))
            throw new IllegalStateException("固定批次状态已变化，禁止执行 DS 节点");
        var step = jdbc.queryForMap("SELECT status,node_code FROM dp_warehouse_batch_step WHERE batch_id=? AND ordinal=?", batchId, ordinal);
        String stepStatus = String.valueOf(step.get("status"));
        if ("SUCCESS".equals(stepStatus)) return; // DS retry after a lost HTTP acknowledgement never writes twice.
        if (!"QUEUED".equals(stepStatus)) throw new IllegalStateException("DS 节点已有执行尝试，禁止重复写入");
        jdbc.update("UPDATE dp_warehouse_batch SET status='RUNNING',active_instance_id=?,lease_owner=?,message='DS 原生五层节点执行中' WHERE id=? AND status='WAITING_DS'",
                localInstanceId, owner, batchId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_batch WHERE id=? AND status='RUNNING' AND active_instance_id=? AND lease_owner=?", Long.class,
                batchId, localInstanceId, owner) != 1) throw new IllegalStateException("固定批次持有者不匹配");
        var task = tasks().getById(taskId);
        if (task == null || !batchId.equals(task.getWarehouseBatchId())) throw new IllegalStateException("DS 开发任务已变化");
        var instance = DppEtlTaskInstanceDO.builder().id(localInstanceId).name(task.getName())
                .taskVersion(((Number) jdbc.queryForObject("SELECT task_version FROM dpp_etl_task_instance WHERE id=? AND task_id=? AND status='1'",
                        Integer.class, localInstanceId, taskId)).intValue()).build();
        runners.getObject().runDolphinWarehouseNode(task, instance, batchId, ordinal, String.valueOf(step.get("node_code")), owner);
        if (!"SUCCESS".equals(jdbc.queryForObject("SELECT status FROM dp_warehouse_batch_step WHERE batch_id=? AND ordinal=?", String.class, batchId, ordinal)))
            throw new IllegalStateException("DS 节点结果未可靠登记");
    }

    @Scheduled(fixedDelay = 5000)
    public void reconcile() {
        List<Map<String,Object>> runs;
        try { runs = jdbc.queryForList("SELECT batch_id,task_id,task_instance_id,project_code,instance_id,lease_owner FROM dp_warehouse_ds_native_run WHERE state='RUNNING' AND instance_id IS NOT NULL ORDER BY created_at LIMIT 20"); }
        catch (Exception unavailable) { return; } // Old installations have no native ledger yet.
        for (var run : runs) {
            String batchId = String.valueOf(run.get("batch_id"));
            long taskId = ((Number) run.get("task_id")).longValue();
            long localInstanceId = ((Number) run.get("task_instance_id")).longValue();
            String owner = String.valueOf(run.get("lease_owner"));
            try {
                if (!redis.renewLock(lockKey(taskId), owner, LEASE_SECONDS)) {
                    unknown(run, "DS 五层执行租约已失效；输出保留，禁止原地重试");
                    continue;
                }
                String state = dolphin.instanceState(String.valueOf(run.get("project_code")), ((Number) run.get("instance_id")).longValue());
                if (!Set.of("SUCCESS", "FAILURE", "STOP", "KILL").contains(state)) continue;
                if (jdbc.update("UPDATE dp_warehouse_ds_native_run SET state='FINALIZING' WHERE batch_id=? AND state='RUNNING'", batchId) != 1) continue;
                if ("SUCCESS".equals(state)) {
                    if (jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_batch_step WHERE batch_id=? AND status<>'SUCCESS'", Long.class, batchId) != 0)
                        throw new IllegalStateException("DS 实例成功但固定模型节点未全部成功");
                    if (jdbc.update("UPDATE dpp_etl_task_instance SET status='7',end_time=NOW() WHERE id=? AND task_id=? AND status='1'",
                            localInstanceId, taskId) != 1) throw new IllegalStateException("开发任务成功状态未保存");
                    warehouseExecutions.getObject().complete(batchId, taskId, localInstanceId, owner);
                    jdbc.update("UPDATE dp_warehouse_ds_native_run SET state='SUCCESS' WHERE batch_id=? AND state='FINALIZING'", batchId);
                } else {
                    jdbc.update("UPDATE dpp_etl_task_instance SET status='6',end_time=NOW(),remark=? WHERE id=? AND task_id=? AND status='1'",
                            "DolphinScheduler 五层工作流 " + state, localInstanceId, taskId);
                    warehouseExecutions.getObject().fail(batchId, taskId, localInstanceId, "DolphinScheduler 五层工作流 " + state);
                    jdbc.update("UPDATE dp_warehouse_ds_native_run SET state='FAILED',message=? WHERE batch_id=? AND state='FINALIZING'", state, batchId);
                }
                redis.releaseLock(lockKey(taskId), owner);
            } catch (Exception error) {
                log.error("Could not reconcile DS warehouse batch {}", batchId, error);
                unknown(run, "DS 五层结果对账失败；输出保留，禁止原地重试");
            }
        }
    }

    private void unknown(Map<String,Object> run, String message) {
        String batchId = String.valueOf(run.get("batch_id"));
        long taskId = ((Number) run.get("task_id")).longValue();
        long localInstanceId = ((Number) run.get("task_instance_id")).longValue();
        jdbc.update("UPDATE dp_warehouse_ds_native_run SET state='UNKNOWN',message=? WHERE batch_id=? AND state IN ('RUNNING','FINALIZING')", message, batchId);
        jdbc.update("UPDATE dp_warehouse_batch SET status='UNKNOWN',finished_at=NOW(6),message=? WHERE id=? AND status IN ('WAITING_DS','RUNNING')", message, batchId);
        jdbc.update("UPDATE dp_warehouse_batch_step SET status='UNKNOWN',finished_at=NOW(6),message=? WHERE batch_id=? AND status='RUNNING'", message, batchId);
        jdbc.update("UPDATE dp_warehouse_batch_step SET status='BLOCKED',finished_at=NOW(6),message='DS 上游状态未知，未执行节点' WHERE batch_id=? AND status='QUEUED'", batchId);
        jdbc.update("UPDATE dpp_etl_task_instance SET status='6',end_time=NOW(),remark=? WHERE id=? AND task_id=? AND status='1'", message, localInstanceId, taskId);
        redis.releaseLock(lockKey(taskId), String.valueOf(run.get("lease_owner")));
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean constantTimeEqual(String left, String right) {
        if (right == null || !right.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(left), HexFormat.of().parseHex(right));
    }
}
