package tech.qiantong.qdata.module.dpp.service.qa;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Prevent editing fixed assessment task definitions through ordinary native CRUD paths. */
@Service
@RequiredArgsConstructor
public class ManagedQualityDefinitions {
    private final JdbcTemplate jdbc;
    public String assessmentId(Long taskId) {
        var rows=jdbc.queryForList("SELECT assessment_id FROM dp_warehouse_quality_assessment_task WHERE task_id=?",String.class,taskId);
        return rows.isEmpty()?null:rows.get(0);
    }
    public void requireEditable(Long taskId) {
        if(taskId!=null && jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_quality_assessment_task WHERE task_id=?",Long.class,taskId)>0)
            throw new IllegalArgumentException("固定质量评估不能原地修改；请创建新的策略版本或评估尝试");
    }
    public void requireExecutionAllowed(Long taskId) {
        var rows=jdbc.queryForList("SELECT a.status FROM dp_warehouse_quality_assessment_task t JOIN dp_warehouse_quality_assessment a ON a.id=t.assessment_id WHERE t.task_id=?",taskId);
        if(!rows.isEmpty() && !"RUNNING".equals(rows.get(0).get("status")))
            throw new IllegalArgumentException("请从数仓批次的质量评估入口执行固定任务");
    }
}
