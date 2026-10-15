package tech.qiantong.qdata.module.dpp.service.etl.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.common.enums.CommandType;
import tech.qiantong.qdata.common.enums.WorkflowExecutionStatus;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.uuid.IdUtils;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskInstanceDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskInstanceLogDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskInstanceLogService;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskInstanceService;

import java.util.Date;

/** Only failures before the driver starts belong here; running drivers own their instance reporting. */
@Service
@RequiredArgsConstructor
public class SparkPreflightFailureRecorder {
    private final IDppEtlTaskInstanceService instances;
    private final IDppEtlTaskInstanceLogService logs;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(DppEtlTaskDO task, RuntimeException failure) {
        Date now = new Date();
        String detail = "Spark任务预检失败，未启动计算：" + failure.getMessage();
        var instance = DppEtlTaskInstanceDO.builder()
                .id(IdUtils.generateArtificialId()).catId(task.getCatId()).catCode(task.getCatCode())
                .taskType(task.getType()).name(task.getName()).taskId(task.getId()).taskCode(task.getCode())
                .taskVersion(task.getVersion() == null ? null : task.getVersion().intValue())
                .personCharge(task.getPersonCharge()).contactNumber(task.getContactNumber())
                .projectId(task.getProjectId()).projectCode(task.getProjectCode())
                .scheduleTime(now).startTime(now).endTime(now).runTimes(1)
                .commandType(String.valueOf(CommandType.START_PROCESS.getCode())).subTaskFlag("0")
                .status(String.valueOf(WorkflowExecutionStatus.FAILURE.getCode()))
                .validFlag(Boolean.TRUE).delFlag(Boolean.FALSE)
                .dsId(task.getDsId()).build();
        instance.setRemark(StringUtils.left(detail, 512));
        if (!instances.save(instance)) throw new IllegalStateException("无法保存Spark预检失败实例：" + task.getId());
        boolean logged = logs.saveOrUpdateRealtime(DppEtlTaskInstanceLogDO.builder()
                .taskInstanceId(instance.getId()).taskType(task.getType()).taskId(task.getId())
                .taskCode(task.getCode()).tm(now).logContent(detail)
                .validFlag(Boolean.TRUE).delFlag(Boolean.FALSE).build());
        if (!logged) throw new IllegalStateException("无法保存Spark预检失败日志：" + instance.getId());
    }
}
