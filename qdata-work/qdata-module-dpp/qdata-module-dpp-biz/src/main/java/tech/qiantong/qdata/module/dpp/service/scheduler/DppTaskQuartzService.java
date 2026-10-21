package tech.qiantong.qdata.module.dpp.service.scheduler;

import lombok.extern.slf4j.Slf4j;

import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.quartz.domain.ScheduleCommand;
import tech.qiantong.qdata.quartz.domain.ScheduleRespDTO;
import tech.qiantong.qdata.quartz.scheduler.ISchedulerAdapter;

import jakarta.annotation.Resource;

/**
 * DolphinScheduler service.
 * Manages scheduling and execution for data collection tasks.
 *
 * @author qdata
 * @date 2025-12-16
 */
@Slf4j
@Service
public class DppTaskQuartzService {

    @Resource
    private ISchedulerAdapter schedulerAdapter;

    /**
     * Creates a scheduler.
     */
    public Long create(DppEtlTaskDO dppEtlTaskDO, String invokeTarget) {
        ScheduleCommand build = ScheduleCommand.builder().jobName(dppEtlTaskDO.getName())
                .jobGroup(dppEtlTaskDO.getName()).remark(dppEtlTaskDO.getRemark()).invokeTarget(String.format(invokeTarget, dppEtlTaskDO.getId()))
                .cronExpression(dppEtlTaskDO.getCronExpression()).executionType(dppEtlTaskDO.getExecutionType())
                .status("1").build();
        try {
            Long schedule = schedulerAdapter.createSchedule(build);
            if (schedule == null || schedule < 1) throw new ServiceException("创建Quartz调度失败，请检查调度配置");
            return schedule;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建Quartz调度失败，taskId={}", dppEtlTaskDO.getId(), e);
            throw new ServiceException("dpp.error.scheduler.create", "Failed to create scheduler!");
        }
    }

    /**
     * Updates a scheduler.
     */
    public void update(Long id,String cronExpression){
        try {
            ScheduleRespDTO scheduleRespDTO = schedulerAdapter.selectScheduleById(ScheduleCommand.builder().id(id).build());
            schedulerAdapter.updateSchedule(ScheduleCommand.builder().id(scheduleRespDTO.getJobId()).remark(scheduleRespDTO.getRemark()).jobId(scheduleRespDTO.getJobId())
                            .jobName(scheduleRespDTO.getJobName()).jobGroup(scheduleRespDTO.getJobGroup()).invokeTarget(scheduleRespDTO.getInvokeTarget()).misfirePolicy(scheduleRespDTO.getMisfirePolicy())
                    .concurrent(scheduleRespDTO.getConcurrent()).executionType(scheduleRespDTO.getExecutionType()).status(scheduleRespDTO.getStatus()).cronExpression(cronExpression).build());
        } catch (Exception e) {
            log.error("Failed to update the scheduler!",e);
            throw new ServiceException("dpp.error.scheduler.update", "Failed to update scheduler!");
        }
    }

    /**
     * Deactivates the scheduler.
     *
     * @param quartzId
     */
    public void offline(Long quartzId) {
        try {
            schedulerAdapter.offline(ScheduleCommand.builder().id(quartzId).build());
        } catch (SchedulerException e) {
            throw new ServiceException("dpp.error.scheduler.offline", "Failed to offline scheduler!");
        }
    }

    /**
     * Activates the scheduler.
     *
     * @param quartzId
     */
    public void online(Long quartzId) {
        try {
            schedulerAdapter.online(ScheduleCommand.builder().id(quartzId).build());
        } catch (SchedulerException e) {
            throw new ServiceException("dpp.error.scheduler.online", "Failed to online scheduler!");
        }
    }

    /**
     * Generates a task number.
     *
     * @param projectCode
     * @return
     */
    public Long genCode(Long projectCode) {
        return schedulerAdapter.generateTaskCode(projectCode);
    }
}
