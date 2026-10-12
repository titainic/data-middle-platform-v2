package tech.qiantong.qdata.module.mc.service.task.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tech.qiantong.qdata.common.constant.HttpStatus;
import tech.qiantong.qdata.common.constant.ScheduleConstants;
import tech.qiantong.qdata.common.core.domain.BatchDeleteCheck;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.constants.DbType;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.database.core.DbName;
import tech.qiantong.qdata.common.database.core.DbTable;
import tech.qiantong.qdata.common.database.exception.DataQueryException;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.att.api.sourceSystem.dto.AttSourceSystemRespDTO;
import tech.qiantong.qdata.module.att.api.sourceSystem.service.IAttSourceSystemApiService;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.mc.api.service.task.IMcTaskApiService;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.*;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.*;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McColumnDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McDbDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McTableDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskInstanceDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskSchedulerDO;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskScopeDO;
import tech.qiantong.qdata.module.mc.dal.mapper.metadata.McColumnMapper;
import tech.qiantong.qdata.module.mc.dal.mapper.metadata.McDbMapper;
import tech.qiantong.qdata.module.mc.dal.mapper.metadata.McTableMapper;
import tech.qiantong.qdata.module.mc.dal.mapper.task.McTaskMapper;
import tech.qiantong.qdata.module.mc.enums.CollectionScopeEnum;
import tech.qiantong.qdata.module.mc.enums.SchedulerStatusEnum;
import tech.qiantong.qdata.module.mc.service.columnLog.IMcColumnLogService;
import tech.qiantong.qdata.module.mc.service.metadata.IMcColumnService;
import tech.qiantong.qdata.module.mc.service.metadata.IMcDbService;
import tech.qiantong.qdata.module.mc.service.metadata.IMcTableService;
import tech.qiantong.qdata.module.mc.service.metadata.dialect.DatabaseDialect;
import tech.qiantong.qdata.module.mc.service.metadata.dialect.DatabaseDialectFactory;
import tech.qiantong.qdata.module.mc.service.scheduler.McTaskDolphinSchedulerService;
import tech.qiantong.qdata.module.mc.service.scheduler.McTaskQuartzService;
import tech.qiantong.qdata.module.mc.service.tableColumnRelLog.IMcTableColumnRelLogService;
import tech.qiantong.qdata.module.mc.service.tableLog.IMcTableLogService;
import tech.qiantong.qdata.module.mc.service.task.*;
import tech.qiantong.qdata.module.system.service.ISysUserService;
import tech.qiantong.qdata.redis.service.IRedisService;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collection task Service business layer processing
 *
 * @author qdata
 * @date 2025-12-16
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class McTaskServiceImpl extends ServiceImpl<McTaskMapper, McTaskDO> implements IMcTaskService, IMcTaskApiService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate executionJdbc;

    @Resource
    private McTaskMapper mcTaskMapper;

    @Resource
    private IMcTaskSchedulerService mcTaskSchedulerService;
    @Resource
    private IMcTaskScopeService mcTaskScopeService;
    @Resource
    private IDaDatasourceApiService daDatasourceApiService;
    @Resource
    private IMcTaskInstanceService mcTaskInstanceService;
    @Resource
    private IMcTaskInstanceLogService mcTaskInstanceLogService;

    @Autowired
    @Lazy
    private DataSourceFactory dataSourceFactory;

    @Resource
    @Lazy
    private IRedisService redisService;

    //External api
    @Resource
    @Lazy
    private IMcDbService mcDbService;
    @Resource
    private IMcTableService mcTableService;
    @Resource
    private IMcColumnService mcColumnService;

    @Resource
    private McDbTxService mcDbTxService;
    @Resource
    private McTableTxService mcTableTxService;
    @Resource
    private McTaskDolphinSchedulerService mcTaskDolphinSchedulerService;
    @Resource
    private McTaskQuartzService mcTaskQuartzService;

    @Resource
    private IMcTableLogService mcTableLogService;
    @Resource
    private IMcColumnLogService mcColumnLogService;
    @Resource
    private IMcTableColumnRelLogService mcTableColumnRelLogService;
    @Resource
    private McDbMapper mcDbMapper;
    @Resource
    private McTableMapper mcTableMapper;
    @Resource
    private McColumnMapper mcColumnMapper;
    @Resource
    private ISysUserService sysUserService;
    @Resource
    private IAttSourceSystemApiService attSourceSystemApiService;


    @Resource private tech.qiantong.qdata.module.mc.service.task.CollectionResourceAccess collectionAccess;
    @Resource private tech.qiantong.qdata.module.mc.service.task.CollectionExecutionProjectGuard executionProjectGuard;

    @Override
    public boolean existsBySourceSystemName(String sourceSystemName) {
        return mcTaskMapper.existsBySourceSystemName(sourceSystemName);
    }


    @Override
    public PageResult<McTaskDO> getMcTaskPage(McTaskPageReqVO pageReqVO) {
        PageResult<McTaskDO> mcTaskDOPageResult = mcTaskMapper.selectPage(pageReqVO);

        List<McTaskDO> rows = mcTaskDOPageResult.getRows();

        if (CollectionUtils.isEmpty(rows)) {
            return mcTaskDOPageResult;
        }

        // FIXME (user query to avoid circular query, temporary solution) uses Map to cache user information to avoid repeated queries
        Map<Long, SysUser> userCache = Maps.newHashMap();
        for (McTaskDO row : rows) {
            // Get the creator’s mobile phone number
            Long creatorId = row.getCreatorId();
            if (creatorId != null && !userCache.containsKey(creatorId)) {
                SysUser sysUser = sysUserService.selectUserById(creatorId);
                if (sysUser != null) {
                    userCache.put(creatorId, sysUser);
                }
            }
            SysUser creatorUser = userCache.get(creatorId);
            if (creatorUser != null) {
                row.setCreatePhoneNumber(creatorUser.getPhonenumber());
            }

            McTaskInstanceDO mcTaskInstanceByTaskId = mcTaskInstanceService.getMcTaskInstanceByTaskId(row.getId());
            McTaskSchedulerDO scheduler = mcTaskSchedulerService.getMcTaskSchedulerBytaskId(row.getId());
            if (scheduler != null) {
                row.setScheduler(scheduler.getScheduler());
                row.setSchedulerStatus(scheduler.getStatus());
            }
            if (mcTaskInstanceByTaskId != null) {
                row.setLastExecuteTime(DateUtil.format(mcTaskInstanceByTaskId.getCreateTime(), "yyyy-MM-dd HH:mm:ss"));
            }

        }
        mcTaskDOPageResult.setRows(rows);

        return mcTaskDOPageResult;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createMcTask(McTaskSaveReqVO createReqVO) {
        collectionAccess.save(createReqVO,false);
        // Verify whether tasks are repeated
        validateTableSelection(createReqVO);
        validateDuplicateTask(createReqVO, null);

        McTaskDO dictType = BeanUtils.toBean(createReqVO, McTaskDO.class);
        if (StringUtils.isEmpty(dictType.getStatus())) {
            dictType.setStatus(SchedulerStatusEnum.DISABLED.getValue());
        }
        mcTaskMapper.insert(dictType);
        Long id = dictType.getId();

        String taskCode;
        Long schedulerId;
        // Create the Quartz scheduler.
        if (ScheduleConstants.QUARTZ.equals(createReqVO.getScheduler())) {
            schedulerId = mcTaskQuartzService.createSchedulerQuartz(dictType);
            taskCode = String.valueOf(schedulerId);
        } else {
            // Create a DolphinScheduler task definition
            taskCode = mcTaskDolphinSchedulerService.createTaskDefinition(dictType.getName(), id);
            // Online tasks first (DolphinScheduler requirements: only online tasks can create a scheduler)
            mcTaskDolphinSchedulerService.onlineTask(taskCode);
            // Create scheduler
            schedulerId = mcTaskDolphinSchedulerService.createScheduler(taskCode, dictType.getCronExpression());
        }

        //Store scheduling information
        McTaskSchedulerSaveReqVO schedulerSaveReqVO = new McTaskSchedulerSaveReqVO(dictType);
        schedulerSaveReqVO.setJobId(String.valueOf(schedulerId));
        schedulerSaveReqVO.setTaskCode(taskCode);
        schedulerSaveReqVO.setScheduler(StringUtils.isEmpty(createReqVO.getScheduler()) ? ScheduleConstants.DOLPHINSCHEDULER : createReqVO.getScheduler());
        schedulerSaveReqVO.setStatus(SchedulerStatusEnum.DISABLED.getValue());
        mcTaskSchedulerService.createMcTaskScheduler(schedulerSaveReqVO);

        if (StringUtils.equals("1", createReqVO.getCollectionScope())) {
            List<McTaskScopeSaveReqVO> scopeSaveReqVOS = createReqVO.getScopeSaveReqVOS();
            for (McTaskScopeSaveReqVO scopeSaveReqVO : scopeSaveReqVOS) {
                scopeSaveReqVO.setTaskId(id);
                mcTaskScopeService.createMcTaskScope(scopeSaveReqVO);
            }
        }

        return dictType.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateMcTask(McTaskSaveReqVO updateReqVO) {
        collectionAccess.save(updateReqVO,true);
        if(redisService.hasKey(buildRunLockKey(updateReqVO.getId())+":lease"))throw new IllegalArgumentException("采集正在执行，不能修改当前任务配置");
        // Verify whether the task is repeated (excluding the current task itself)
        validateTableSelection(updateReqVO);
        validateDuplicateTask(updateReqVO, updateReqVO.getId());

        // 1. Update collection tasks
        McTaskDO updateObj = BeanUtils.toBean(updateReqVO, McTaskDO.class);
        int rows = mcTaskMapper.updateById(updateObj);

        // 2. Query scheduling information
        McTaskSchedulerDO scheduler = mcTaskSchedulerService.getMcTaskSchedulerBytaskId(updateObj.getId());
        if (scheduler != null) {
            // 3. Only update when there are changes
            McTaskSchedulerSaveReqVO schedulerSaveReqVO = new McTaskSchedulerSaveReqVO();
            schedulerSaveReqVO.setId(scheduler.getId());

            boolean needUpdate = false;

            // cron expression
            String cronExpression = updateReqVO.getCronExpression();
            if (StringUtils.isNotEmpty(cronExpression) && !StringUtils.equals(cronExpression, scheduler.getCronExpression())) {
                schedulerSaveReqVO.setCronExpression(cronExpression);

                // Get task encoding (from schedule)
                String taskCode = scheduler.getTaskCode();

                if (ScheduleConstants.QUARTZ.equals(updateReqVO.getScheduler())) {
                    mcTaskQuartzService.updateScheduleQuartz(updateObj, scheduler, cronExpression);
                } else if (StringUtils.isNotEmpty(taskCode)) {
                    // Update the DolphinScheduler scheduler
                    Long newSchedulerId = mcTaskDolphinSchedulerService.updateScheduler(
                            Long.parseLong(scheduler.getJobId()), taskCode, cronExpression);
                    schedulerSaveReqVO.setJobId(String.valueOf(newSchedulerId));
                }
                needUpdate = true;
            }

            // Scheduling status
            String schedulerStatus = updateReqVO.getSchedulerStatus();
            if (StringUtils.isNotEmpty(schedulerStatus) && !StringUtils.equals(schedulerStatus, scheduler.getStatus())) {
                schedulerSaveReqVO.setStatus(schedulerStatus);
                needUpdate = true;
            }

            // 4. Store only when there are changes
            if (needUpdate) {
                // 老数据或老前端没有传 scheduler 时，默认还是走原来的 DS 逻辑。
                schedulerSaveReqVO.setScheduler(StringUtils.isEmpty(updateReqVO.getScheduler()) ? ScheduleConstants.DOLPHINSCHEDULER : updateReqVO.getScheduler());
                mcTaskSchedulerService.updateMcTaskScheduler(schedulerSaveReqVO);
            }

        }

        if (StringUtils.equals("1", updateReqVO.getCollectionScope())) {
            //Delete
            mcTaskScopeService.removeMcTaskScopeBytaskId(updateObj.getId());
            //New
            List<McTaskScopeSaveReqVO> scopeSaveReqVOS = updateReqVO.getScopeSaveReqVOS();
            for (McTaskScopeSaveReqVO scopeSaveReqVO : scopeSaveReqVOS) {
                scopeSaveReqVO.setId(null);
                scopeSaveReqVO.setTaskId(updateObj.getId());
                mcTaskScopeService.createMcTaskScope(scopeSaveReqVO);
            }
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int removeMcTask(Collection<Long> idList) {
        for(Long id:idList)collectionAccess.task(id,"remove");
        for (Long id : idList) {
            McTaskSchedulerDO scheduler = mcTaskSchedulerService.getMcTaskSchedulerBytaskId(id);
            McTaskDO task = mcTaskMapper.selectById(id);

            // Offline tasks and schedulers first
            if (task != null && scheduler != null && StringUtils.isNotEmpty(scheduler.getTaskCode())) {
                if (ScheduleConstants.QUARTZ.equals(scheduler.getScheduler())) {
                    Long jobId = Long.valueOf(scheduler.getJobId());
                    mcTaskQuartzService.offlineSchedulerOnlyQuartz(jobId);
                    mcTaskQuartzService.deleteSchedulerQuartz(jobId);
                } else {
                    try {
                        Long schedulerId = StringUtils.isNotEmpty(scheduler.getJobId()) ?
                                Long.parseLong(scheduler.getJobId()) : null;
                        mcTaskDolphinSchedulerService.offlineTaskAndScheduler(scheduler.getTaskCode(), schedulerId);
                    } catch (Exception e) {
                        log.warn("Failed to take task offline, taskId={}", id, e);
                    }

                    // Delete task
                    try {
                        mcTaskDolphinSchedulerService.deleteTask(scheduler.getTaskCode());
                    } catch (Exception e) {
                        log.warn("Failed to delete the DolphinScheduler task, taskId={}", id, e);
                    }
                }
            }
        }
        // Delete collection tasks in batches
        return mcTaskMapper.deleteBatchIds(idList);
    }

    @Override
    public BatchDeleteCheck<Long> batchDeleteCheck(List<Long> list) {
        int cannotDeleteCount = 0;
        List<Long> canDeleteIds = new ArrayList<>();
        for (Long id : list) {
            McTaskRespVO mcTaskByIdNew = this.getMcTaskByIdNew(id);
            if (StringUtils.equals("1", mcTaskByIdNew.getStatus())
                    || StringUtils.equals("1", mcTaskByIdNew.getSchedulerStatus())) {
                cannotDeleteCount++;
                continue;
            }
            canDeleteIds.add(id);
        }
        return new BatchDeleteCheck<>(cannotDeleteCount, canDeleteIds);
    }


    @Override
    public McTaskDO getMcTaskById(Long id) {
        MPJLambdaWrapper<McTaskDO> lambdaWrapper = new MPJLambdaWrapper();

        lambdaWrapper.selectAll(McTaskDO.class)
                .select("t5.NICK_NAME AS personChargeName")
                .leftJoin("SYSTEM_USER t5 ON t.LEADER = t5.USER_ID AND t5.DEL_FLAG = '0'")
                .eq(McTaskDO::getId, id);

        return mcTaskMapper.selectOne(lambdaWrapper);
    }

    @Override
    public McTaskRespVO getMcTaskByIdNew(Long id) {
        McTaskRespVO bean = BeanUtils.toBean(this.getMcTaskById(id), McTaskRespVO.class);

        McTaskSchedulerDO scheduler = mcTaskSchedulerService.getMcTaskSchedulerBytaskId(id);
        if (scheduler != null) {
            bean.setCronExpression(scheduler.getCronExpression());
            bean.setSchedulerStatus(scheduler.getStatus());
            bean.setJobId(scheduler.getJobId());
            bean.setScheduler(scheduler.getScheduler());
            bean.setTaskCode(scheduler.getTaskCode());  // Get taskCode from schedule
        }

        List<McTaskScopeDO> mcTaskScopeDOS = mcTaskScopeService.getMcTaskScopeListBytaskId(id);
        bean.setScopeSaveReqVOS(mcTaskScopeDOS);

        DaDatasourceRespDTO mcDatasourceById = daDatasourceApiService.getDatasourceById(bean.getDatasourceId());
        bean.setDatasourceDO(mcDatasourceById);

        McTaskInstanceDO mcTaskInstanceByTaskId = mcTaskInstanceService.getMcTaskInstanceByTaskId(id);
        if (mcTaskInstanceByTaskId != null) {
            bean.setLastExecuteTime(DateUtil.format(mcTaskInstanceByTaskId.getCreateTime(), "yyyy-MM-dd HH:mm:ss"));
        }

        return bean;
    }

    @Override
    public List<McTaskDO> getMcTaskList() {
        return mcTaskMapper.selectList();
    }

    @Override
    public Map<Long, McTaskDO> getMcTaskMap() {
        List<McTaskDO> mcTaskList = mcTaskMapper.selectList();
        return mcTaskList.stream().collect(Collectors.toMap(McTaskDO::getId, mcTaskDO -> mcTaskDO,
                // Keep existing values
                (existing, replacement) -> existing));
    }


    /**
     * Import collection task data
     *
     * @param importExcelList collection task data list
     * @param isUpdateSupport Whether to update support, if it already exists, update the data
     * @param operName        operating user
     * @return result
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String importMcTask(List<McTaskRespVO> rows, boolean updateSupport, String operName) {
        if(rows==null || rows.isEmpty())throw new IllegalArgumentException("导入不能为空");
        List<McTaskSaveReqVO> requests=new ArrayList<>();
        for(var row:rows){
            var request=BeanUtils.toBean(row,McTaskSaveReqVO.class);
            if(!updateSupport && request.getId()!=null)throw new IllegalArgumentException("新增导入不能指定已有任务ID");
            collectionAccess.save(request,request.getId()!=null);requests.add(request);
        }
        for(var request:requests){if(request.getId()==null)createMcTask(request);else updateMcTask(request);}
        return "成功导入"+requests.size()+"条采集任务";
    }

    @Override
    public List<McTaskScopeDO> getRealtimeMcTaskScopeList(Long id) {
        List<DbName> dbNameList = daDatasourceApiService.getDatabaseListByDatasourceId(id);
        List<McTaskScopeDO> taskScopeDOList = new ArrayList<>();

        if (CollectionUtils.isEmpty(dbNameList)) {
            return taskScopeDOList;
        }

        for (DbName dbName : dbNameList) {
            List<DbName> children = dbName.getChildren();
            if (CollectionUtils.isNotEmpty(children)) {
                for (DbName child : children) {
                    McTaskScopeDO mcTaskScopeDO = new McTaskScopeDO();
                    mcTaskScopeDO.setDbName(dbName.getDbName());
                    mcTaskScopeDO.setSchemaName(child.getDbName());
                    taskScopeDOList.add(mcTaskScopeDO);
                }
            } else {
                McTaskScopeDO mcTaskScopeDO = new McTaskScopeDO();
                mcTaskScopeDO.setDbName(dbName.getDbName());
                taskScopeDOList.add(mcTaskScopeDO);
            }
        }
        return taskScopeDOList;
    }

    @Override
    public Map<String, Object> updateReleaseJobTask(McTaskSaveReqVO mcTask) {
        collectionAccess.existingRequest(mcTask,"release");
        McTaskRespVO mcTaskByIdNew = this.getMcTaskByIdNew(mcTask.getId());
        String status = mcTaskByIdNew.getStatus();
        if (StringUtils.equals(status, mcTask.getStatus())) {
            return new HashMap<>();
        }

        if (StringUtils.equals("0", mcTask.getStatus()) && StringUtils.equals("1", mcTaskByIdNew.getSchedulerStatus())) {
            throw new ServiceException(MessageUtils.messageEn("mc.error.scheduler.online.first"));
        }

        McTaskDO updateObj = new McTaskDO();
        updateObj.setId(mcTask.getId());
        updateObj.setStatus(mcTask.getStatus());

        int rows = mcTaskMapper.updateById(updateObj);

        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        return result;
    }

    @Override
    public Map<String, Object> updateReleaseSchedule(McTaskSaveReqVO mcTask) {
        collectionAccess.existingRequest(mcTask,"schedule");
        if("1".equals(mcTask.getStatus()))executionProjectGuard.validate(mcTask.getId());
        McTaskRespVO mcTaskByIdNew = this.getMcTaskByIdNew(mcTask.getId());
        String schedulerStatus = mcTaskByIdNew.getSchedulerStatus();
        if (StringUtils.equals(schedulerStatus, mcTask.getStatus())) {
            return new HashMap<>();
        }

        // Get scheduler information
        McTaskSchedulerDO scheduler = mcTaskSchedulerService.getMcTaskSchedulerBytaskId(mcTask.getId());

        if (scheduler != null && StringUtils.isNotEmpty(scheduler.getTaskCode())) {
            Long schedulerId = StringUtils.isNotEmpty(scheduler.getJobId()) ?
                    Long.parseLong(scheduler.getJobId()) : null;

            // Offline scheduler (disable scheduled triggering)
            if (SchedulerStatusEnum.isDisabled(mcTask.getStatus())) {
                if (ScheduleConstants.QUARTZ.equals(scheduler.getScheduler())) {
                    mcTaskQuartzService.offlineSchedulerOnlyQuartz(schedulerId);
                } else
                    mcTaskDolphinSchedulerService.offlineSchedulerOnly(schedulerId);
            }

            // Online scheduler (enable scheduled triggering)
            if (SchedulerStatusEnum.isEnabled(mcTask.getStatus())) {
                if (ScheduleConstants.QUARTZ.equals(scheduler.getScheduler())) {
                    mcTaskQuartzService.onlineSchedulerOnlyQuartz(schedulerId);
                } else
                    mcTaskDolphinSchedulerService.onlineSchedulerOnly(schedulerId);
            }
        }

        McTaskSchedulerSaveReqVO updateReqVO = new McTaskSchedulerSaveReqVO();
        updateReqVO.setTaskId(mcTask.getId());
        updateReqVO.setStatus(mcTask.getStatus());
        mcTaskSchedulerService.updateReleaseSchedule(updateReqVO);

        return new HashMap<>();
    }

    @Override
    public Map<String, Object> runJobOnce(McTaskSaveReqVO mcTask) {
        collectionAccess.existingRequest(mcTask,"execute");
        executionProjectGuard.validate(mcTask.getId());
        String redisKey = buildRunLockKey(mcTask.getId());
        if (!checkTaskRunLock(redisKey)) {
            throw new RuntimeException(MessageUtils.messageEn("mc.error.task.running"));
        }
        // FIXME records the person who used redis to solve the problem once
        redisService.set(redisKey + ":creatorId", mcTask.getCreatorId().toString(), 60 * 60 * 12);
        redisService.set(redisKey + ":createBy", mcTask.getCreateBy().toString(), 60 * 60 * 12);

        McTaskRespVO mcTaskByIdNew = this.getMcTaskByIdNew(mcTask.getId());

        // Use DolphinScheduler to execute tasks immediately
        if (mcTaskByIdNew != null) {
            // Get taskCode from scheduling information
            McTaskSchedulerDO scheduler = mcTaskSchedulerService.getMcTaskSchedulerBytaskId(mcTask.getId());
            if (scheduler != null && StringUtils.isNotEmpty(scheduler.getTaskCode())) {
                if (ScheduleConstants.QUARTZ.equals(scheduler.getScheduler())) {
                    mcTaskQuartzService.startTaskQuartz(Long.valueOf(scheduler.getJobId()));
                } else {
                    mcTaskDolphinSchedulerService.startTask(scheduler.getTaskCode());
                }
            }
        }

        return new HashMap<>();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean runDaDiscoveryTask(Long taskId) {
        executionProjectGuard.validate(taskId);

        String redisKey = buildRunLockKey(taskId);
        var lease=tech.qiantong.qdata.redis.service.OwnedRedisLease.acquire(redisService,redisKey+":lease",120);
        McTaskInstanceDO instance=null;
        try {
            McTaskRespVO task=loadTask(taskId);
            instance=createTaskInstance(task);
            executionJdbc.update("UPDATE mc_task SET active_instance_id=? WHERE id=?",instance.getId(),taskId);
            McExecutionFence.open(taskId,instance.getId(),lease);
            Long instanceId=instance.getId();
            safeLog(instanceId,taskId,MessageUtils.messageEn("mc.log.task.start"));
            TableProcessResult tableProcessResult = executeTaskSafely(task, instance);

            if (tableProcessResult != null) {
                Long addCount = tableProcessResult.getAddCount();
                Long delCount = tableProcessResult.getDelCount();
                Long updateCount = tableProcessResult.getUpdateCount();
                instance.setAddCount(addCount);
                instance.setDelCount(delCount);
                instance.setUpdateCount(updateCount);
                instance.setTotalCount(tableProcessResult.getTotalCount());
                instance.setSuccessCount(tableProcessResult.getSuccessCount());
                instance.setFailCount(tableProcessResult.getTotalCount() - tableProcessResult.getSuccessCount());

                if (StringUtils.equals("2", task.getCollectType())) {
                    if ((addCount != null && addCount != 0L)
                            || (delCount != null && delCount != 0L)
                            || (updateCount != null && updateCount != 0L)) {
                        instance.setValidFlag(Boolean.TRUE);
                    }
                }
            }
            McTaskInstanceDO completed=instance;
            mcTableTxService.runInTransaction(()->{markSuccess(completed);return true;});
            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.task.success"));
            return true;
        } catch (Exception e) {
            if(instance!=null) {
                safeLog(instance.getId(),taskId,MessageUtils.messageEn("mc.log.task.failed",e.getMessage()));
                markFail(instance,e);
            }
            return false;
        } finally {
            try {
                if(instance!=null) {
                    safeLog(instance.getId(),taskId,MessageUtils.messageEn("mc.log.task.summary",instance.getTotalCount(),instance.getSuccessCount(),instance.getFailCount(),instance.getDuration()));
                    finalizeTask(redisKey,instance);
                    executionJdbc.update("UPDATE mc_task SET active_instance_id=NULL WHERE id=? AND active_instance_id=?",taskId,instance.getId());
                    if(lease.isOwner())redisService.set(redisKey,"9".equals(instance.getStatus())?"2":"3",300);
                }
            } finally {McExecutionFence.close();lease.close();}
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000,initialDelay=30000)
    public void reconcileLostCollectors() {
        var now=java.sql.Timestamp.from(java.time.Instant.now());
        var running=executionJdbc.queryForList("SELECT id,task_id FROM mc_task_instance WHERE status='1' AND start_time<?",java.sql.Timestamp.from(now.toInstant().minusSeconds(180)));
        for(var row:running) {
            long taskId=((Number)row.get("task_id")).longValue();String key=buildRunLockKey(taskId);
            if(redisService.hasKey(key+":lease") || "1".equals(redisService.get(key)))continue;
            executionJdbc.update("UPDATE mc_task_instance SET status='2',fail_cause=?,end_time=?,duration=TIMESTAMPDIFF(SECOND,start_time,?) WHERE id=? AND status='1'","采集进程已失联，不能确认完整提交，请发起新实例",now,now,row.get("id"));
            executionJdbc.update("UPDATE mc_task SET active_instance_id=NULL WHERE id=? AND active_instance_id=?",taskId,row.get("id"));
        }
    }

    private String buildRunLockKey(Long taskId) {
        // Unify the prefix to avoid key conflicts with other modules
        return "mc:task:run:" + taskId;
    }

    private void validateTableSelection(McTaskSaveReqVO request) {
        // Partial legacy updates must not silently clear an existing selection.
        if (request.getTableAllowlist() == null && request.getId() != null) {
            McTaskDO saved = mcTaskMapper.selectById(request.getId());
            if (saved != null) request.setTableAllowlist(saved.getTableAllowlist());
        }
        var scopes = request.getScopeSaveReqVOS() == null ? List.<String>of() :
                request.getScopeSaveReqVOS().stream().map(McTaskScopeSaveReqVO::getDbName).toList();
        var source = daDatasourceApiService.getDatasourceById(request.getDatasourceId());
        if (source == null) throw new IllegalArgumentException("数据连接不存在");
        if (DbType.HIVE.getDb().equalsIgnoreCase(source.getDatasourceType())) {
            if (!"1".equals(request.getCollectionScope()) || scopes.isEmpty())
                throw new IllegalArgumentException("Hive采集请选择自定义库，以固定有权访问的数据库范围");
            Set<String> allowed = daDatasourceApiService.getDatabaseListByDatasourceId(request.getDatasourceId())
                    .stream().map(DbName::getDbName).collect(Collectors.toSet());
            if (!allowed.containsAll(scopes)) throw new IllegalArgumentException("采集范围包含未授权或不可见的Hive数据库");
        }
        request.setTableAllowlist(McTableSelection.validate(request.getTableAllowlist(), source.getDatasourceType(),
                request.getCollectionScope(), scopes, request.getBlacklist()));
    }

    private McTaskRespVO loadTask(Long taskId) {
        McTaskRespVO task = this.getMcTaskByIdNew(taskId);
        if (task == null) {
            throw new DataQueryException("mc.error.collection.task.notfound",
                    "Collection task does not exist, taskId={0}", taskId);
        }
        return task;
    }

    private boolean checkTaskRunLock(String redisKey) {
        return !redisService.hasKey(redisKey+":lease");
    }

    private McTaskInstanceDO createTaskInstance(McTaskRespVO task) {
        McTaskInstanceDO instance = McTaskInstanceDO.builder()
                .taskId(task.getId())
                .sourceSystemId(task.getSourceSystemId())
                .sourceSystemName(task.getSourceSystemName())
                .collectionMode(task.getCollectionMode())
                .collectionScope(task.getCollectionScope())
                .tableAllowlist(task.getTableAllowlist())
                .status("1")
                .successCount(0L)
                .failCount(0L)
                .totalCount(0L)
                .addCount(0L)
                .delCount(0L)
                .updateCount(0L)
                .startTime(new Date())
                .validFlag(Boolean.TRUE)
                .delFlag(Boolean.FALSE)
                .build();
        String creatorId = redisService.get(buildRunLockKey(task.getId()) + ":creatorId");
        String createBy = redisService.get(buildRunLockKey(task.getId()) + ":createBy");
        instance.setCreateBy(StringUtils.isNotEmpty(createBy) ? createBy : "System Collection Task");
        instance.setCreatorId(StringUtils.isNotEmpty(creatorId) ? Long.parseLong(creatorId) : 1L);

        String collectType = task.getCollectType();
        if (StringUtils.equals("2", collectType)) {
            instance.setValidFlag(Boolean.FALSE);
        }

        Long mcTaskInstance = mcTableTxService.runInTransaction(() -> mcTaskInstanceService.createMcTaskInstance(instance));
//        Long mcTaskInstance = mcTaskInstanceService.createMcTaskInstance(instance);
        instance.setId(mcTaskInstance);
        return instance;
    }

    private void safeLog(Long instanceId, Long taskId, String msg) {
        try {
            mcTaskInstanceLogService.taskInstanceLogAppend(instanceId, taskId, msg);
        } catch (Exception e) {
            // ⚠️ Only local logs are allowed when logging fails, and will never be thrown again to avoid overwriting business exceptions.
            log.error("Failed to write task instance log: instanceId={}, msg={}", instanceId, msg, e);
        }
    }


    private void markSuccess(McTaskInstanceDO instance) {
        if (instance.getFailCount() != null && instance.getFailCount() > 0) {
            throw new IllegalStateException("Metadata collection incomplete: " + instance.getFailCount()
                    + " table(s) failed; see the collection log for details");
        }
        instance.setStatus("9");
        instance.setEndTime(new Date());
        instance.setDuration((instance.getEndTime().getTime() - instance.getStartTime().getTime()) / 1000);
        mcTaskInstanceService.updateById(instance);
    }


    private void markFail(McTaskInstanceDO instance, Exception e) {
        instance.setStatus("2");
        String reason = StringUtils.defaultString(e.getMessage(), e.getClass().getSimpleName());
        // MC_TASK_INSTANCE.FAIL_CAUSE is varchar(512); full diagnostics remain in the task log.
        if (reason.codePointCount(0, reason.length()) > 512) {
            reason = reason.substring(0, reason.offsetByCodePoints(0, 509)) + "...";
        }
        instance.setFailCause(reason);
        instance.setEndTime(new Date());
        instance.setDuration((instance.getEndTime().getTime() - instance.getStartTime().getTime()) / 1000);
        mcTaskInstanceService.updateById(instance);
    }

    private void finalizeTask(String redisKey, McTaskInstanceDO instance) {
        safeLog(instance.getId(), instance.getTaskId(), "FINALIZE_SESSION");
        safeLog(instance.getId(), instance.getTaskId(), MessageUtils.messageEn("mc.log.task.end"));
    }

    private DaDatasourceRespDTO prepareDatasource(McTaskRespVO task, McTaskInstanceDO instance) {

        Long taskId = task.getId();
        Long instanceId = instance.getId();

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.fetch.datasource"));

        Long datasourceId = task.getDatasourceId();
        if (datasourceId == null) {
            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.datasource.id.null"));
            throw new DataQueryException(MessageUtils.messageEn("mc.error.datasource.id.empty"));
        }

        DaDatasourceRespDTO datasource;
        try {
            datasource = daDatasourceApiService.getDatasourceById(datasourceId);
        } catch (Exception e) {
            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.fetch.datasource.exception", e.getMessage()));
            throw e;
        }

        if (datasource == null) {
            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.fetch.datasource.failed", datasourceId));
            throw new DataQueryException(MessageUtils.messageEn("mc.error.datasource.query.failed"));
        }

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.fetch.datasource.success", datasourceId));
        return datasource;
    }

    public static boolean isInBlacklist(String input, String blacklist) {
        if (StringUtils.isBlank(blacklist) || StringUtils.isBlank(input)) {
            return false;
        }

        String[] list = blacklist.split(",");

        for (String item : list) {
            String black = item.trim();
            if (input.equalsIgnoreCase(black)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Main process
     *
     * @param task
     * @param instance
     */
    private TableProcessResult executeTaskSafely(McTaskRespVO task, McTaskInstanceDO instance) {

        safeLog(instance.getId(), task.getId(), MessageUtils.messageEn("mc.log.prepare.datasource"));

        DaDatasourceRespDTO datasource = prepareDatasource(task, instance);

        safeLog(instance.getId(), task.getId(), MessageUtils.messageEn("mc.log.load.connection"));


        Long instanceId = instance.getId();
        Long taskId = task.getId();

        String scopeDesc;
        if ("1".equals(task.getCollectionScope())) {
            scopeDesc = MessageUtils.messageEn("mc.log.scope.custom");
        } else if ("2".equals(task.getCollectionScope())) {
            scopeDesc = MessageUtils.messageEn("mc.log.scope.all");
        } else {
            scopeDesc = MessageUtils.messageEn("mc.log.scope.unknown", task.getCollectionScope());
        }
        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.parsing.scope", scopeDesc));

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.task.params", scopeDesc, task.getDatasourceId(), task.getSourceSystemName(), task.getSourceSystemId()));

        // 1. According to the collection range, obtain the "library level" range
        List<McTaskScopeDO> databaseScopes;
        if ("2".equalsIgnoreCase(task.getCollectionScope())) {
            // Full volume: Load the database from the data source in real time
            databaseScopes = loadDatabaseScopesFromDatasource(task, instance, datasource);
        } else {
            // Increment: directly use the collection range configured by the task
            databaseScopes = loadDatabaseScopesFromTask(task, instance);
        }
        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.scope.count", databaseScopes.size()));

        if (CollectionUtils.isEmpty(databaseScopes)) {
            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.scope.empty"));
            return null;
        }

        // 2. Library-level comparison (whether to add/change/delete)
        List<McDbSaveReqVO> dbReqDTOList = compareAndRecordDatabaseScope(task, instance, databaseScopes, datasource);

        List<McDbRespVO> mcDbByTaskId = mcDbService.getMcDbByTaskId(taskId);


        Long addCount = 0L;
        Long delCount = 0L;
        Long updateCount = 0L;

        Long totalCount = 0L;
        Long successCount = 0L;
        List<Long> updateTableIds = new ArrayList<>();
        String blacklist = task.getBlacklist();
        safeLog(instanceId, taskId,
                MessageUtils.messageEn("mc.log.blacklist.config",
                        StringUtils.defaultIfBlank(blacklist, MessageUtils.messageEn("mc.log.blacklist.empty"))));

        int dbIndex = 1;
        // 3. Loop through each library
        for (McDbSaveReqVO dbScope : dbReqDTOList) {
            String dbName = dbScope.getDbName();
            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.processing", dbIndex++, databaseScopes.size(), dbName, StringUtils.isNotBlank(dbScope.getSchemaName()) ? ", schema=" + dbScope.getSchemaName() : ""));

            //Blacklist
            if (StringUtils.isNotEmpty(blacklist)) {
                if (isInBlacklist(dbName, blacklist)) {
                    safeLog(instanceId, taskId,
                            MessageUtils.messageEn("mc.log.db.skip", dbName,
                                    StringUtils.defaultIfBlank(blacklist, MessageUtils.messageEn("mc.log.blacklist.empty"))));
                    continue;
                }
                safeLog(instanceId, taskId,
                        MessageUtils.messageEn("mc.log.db.pass", dbName,
                                StringUtils.isBlank(blacklist) ? MessageUtils.messageEn("mc.log.blacklist.empty") : ""));
            }

            McDbRespVO matchedDb = findMatchedDb(dbScope, datasource, mcDbByTaskId);
            if (matchedDb == null) {
                Long mcDbId = mcDbTxService.createDbAndCommit(dbScope);
                dbScope.setId(mcDbId);
            } else {
                dbScope.setId(matchedDb.getId());
                mcTableTxService.runInTransaction(()->mcDbMapper.update(null, Wrappers.<McDbDO>lambdaUpdate()
                        .eq(McDbDO::getId, matchedDb.getId())
                        .set(McDbDO::getIp, datasource.getIp())
                        .set(McDbDO::getPort, datasource.getPort())
                        .set(McDbDO::getDatasourceConfig, datasource.getDatasourceConfig())
                        .set(McDbDO::getBelongingSystem, datasource.getDatasourceName())));
            }

            DbQueryContext dbQuery = createDbQueryForScope(datasource, dbScope, task, instance);
            try {
                TableProcessResult tableProcessResult = executeSingleDatabase(dbQuery, task, instance, dbScope, datasource);

                if (tableProcessResult != null) {
                    addCount = addCount + tableProcessResult.getAddCount();
                    updateCount = updateCount + tableProcessResult.getUpdateCount();
                    delCount = delCount + tableProcessResult.getDelCount();
                    totalCount = totalCount + tableProcessResult.getTotalCount();
                    successCount = successCount + tableProcessResult.getSuccessCount();
                    if (tableProcessResult.getUpdateTableIds().size() > 0) {
                        updateTableIds.addAll(tableProcessResult.getUpdateTableIds());
                    }
                }
            } finally {
                closeDbQuerySafely(dbQuery, task, instance, dbScope);
            }
        }

        // Update person in charge and responsible department
        mcTableTxService.runInTransaction(() -> this.updateResponsibleInfoForMetadata(task));

        //
        List<McDbRespVO> dbsOnlyInResp = StringUtils.isBlank(task.getTableAllowlist())
                ? findDbsOnlyInResp(databaseScopes, datasource, mcDbByTaskId) : new ArrayList<>();
        if (CollectionUtils.isNotEmpty(dbsOnlyInResp)) {

            if (StringUtils.isNotEmpty(blacklist)) {
                dbsOnlyInResp.removeIf(db -> {
                    String dbName = db.getDbName();
                    boolean hit = isInBlacklist(dbName, blacklist);
                    if (hit) {
                        safeLog(instanceId, taskId,
                                MessageUtils.messageEn("mc.log.db.resp.delete", dbName,
                                        StringUtils.defaultIfBlank(blacklist, MessageUtils.messageEn("mc.log.blacklist.empty"))));
                    }
                    return hit;
                });
            }

            List<Long> collect = dbsOnlyInResp.stream().map(a -> a.getId()).collect(Collectors.toList());

            List<McTableRespVO> mcTableByDbId = mcTableService.getMcTableByDbId(collect);
            if (CollectionUtils.isNotEmpty(mcTableByDbId)) {
                delCount = delCount + mcTableByDbId.size();
            }

            List<Long> tableIds = mcTableByDbId.stream().map(a -> a.getId()).collect(Collectors.toList());
            mcTableTxService.runInTransaction(()->{mcTableService.removeMcTable(tableIds);mcDbService.removeMcDb(collect);return true;});
        }
        return new TableProcessResult(addCount, delCount, updateCount, totalCount, successCount, updateTableIds);

    }

    private Void updateResponsibleInfoForMetadata(McTaskRespVO task) {
        Long leader = task.getLeader();
        Long dept = task.getResponsibleDept();
        LambdaUpdateWrapper<McDbDO> updateDbWrapper = new LambdaUpdateWrapper<>();
        updateDbWrapper.eq(McDbDO::getTaskId, task.getId());

        LambdaUpdateWrapper<McTableDO> updateTbWrapper = new LambdaUpdateWrapper<>();
        updateTbWrapper.eq(McTableDO::getTaskId, task.getId());

        LambdaUpdateWrapper<McColumnDO> updateColWrapper = new LambdaUpdateWrapper<>();
        updateColWrapper.eq(McColumnDO::getTaskId, task.getId());

        boolean hasUpdate = false;
        if (leader != null) {
            updateDbWrapper.set(McDbDO::getBusinessLeader, leader);
            updateTbWrapper.set(McTableDO::getBusinessLeader, leader);
            updateColWrapper.set(McColumnDO::getBusinessLeader, leader);
            hasUpdate = true;
        }
        if (dept != null) {
            updateDbWrapper.set(McDbDO::getResponsibleDept, dept);
            updateTbWrapper.set(McTableDO::getResponsibleDept, dept);
            updateColWrapper.set(McColumnDO::getResponsibleDept, dept);
            hasUpdate = true;
        }

        if (hasUpdate) {
            mcDbMapper.update(null, updateDbWrapper);
            mcTableMapper.update(null, updateTbWrapper);
            mcColumnMapper.update(null, updateColWrapper);
        }
        return null;
    }

    private List<McDbRespVO> findDbsOnlyInResp(List<McTaskScopeDO> databaseScopes, DaDatasourceRespDTO datasource, List<McDbRespVO> mcDbByTaskId) {

        List<McDbRespVO> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(mcDbByTaskId)) {
            return result;
        }

        for (McDbRespVO resp : mcDbByTaskId) {
            boolean exists = false;

            if (CollectionUtils.isNotEmpty(databaseScopes)) {
                for (McTaskScopeDO scope : databaseScopes) {
                    if (Objects.equals(resp.getDatasourceId(), datasource.getId()) && Objects.equals(resp.getDbType(), datasource.getDatasourceType()) && Objects.equals(resp.getDbName(), scope.getDbName()) && Objects.equals(resp.getSchemaName(), scope.getSchemaName())) {
                        exists = true;
                        break;
                    }
                }
            }

            if (!exists) {
                result.add(resp);
            }
        }
        return result;
    }

    private McDbRespVO findMatchedDb(McDbSaveReqVO dbScope, DaDatasourceRespDTO datasource, List<McDbRespVO> mcDbByTaskId) {

        if (dbScope == null || CollectionUtils.isEmpty(mcDbByTaskId)) {
            return null;
        }

        for (McDbRespVO resp : mcDbByTaskId) {
            if (Objects.equals(resp.getDatasourceId(), datasource.getId()) && Objects.equals(resp.getDbType(), datasource.getDatasourceType()) && Objects.equals(resp.getDbName(), dbScope.getDbName()) && Objects.equals(resp.getSchemaName(), dbScope.getSchemaName())) {
                return resp;
            }
        }
        return null;
    }

    private DbQueryContext createDbQueryForScope(DaDatasourceRespDTO datasource, McDbSaveReqVO dbScope, McTaskRespVO task, McTaskInstanceDO instance) {
        DbQueryProperty property = new DbQueryProperty(datasource.getDatasourceType(), datasource.getIp(), datasource.getPort(), datasource.getDatasourceConfig());

        // PG / Kingbase Cheku + schema
        if (DbType.KINGBASE8.getDb().equals(property.getDbType()) || DbType.POSTGRE_SQL.getDb()
                .equals(property.getDbType())) {
            property.setDbName(dbScope.getDbName());
            property.setSid(dbScope.getSchemaName());
        }

        DbQuery dbQuery = dataSourceFactory.createDbQuery(property);
        if (!dbQuery.valid()) {
            safeLog(instance.getId(), task.getId(), MessageUtils.messageEn("mc.log.connection.failed", dbScope.getDbName()));
            throw new DataQueryException(MessageUtils.messageEn("mc.error.connection.failed"));
        }

        property.setDbName(dbScope.getDbName());
        property.setSid(dbScope.getSchemaName());
        return new DbQueryContext(dbQuery, property);
    }

    private TableProcessResult executeSingleDatabase(DbQueryContext dbQuery, McTaskRespVO task, McTaskInstanceDO instance, McDbSaveReqVO dbScope, DaDatasourceRespDTO datasource) {
        String blacklist = task.getBlacklist();
        boolean notEmptyBlacklist = StringUtils.isNotEmpty(blacklist);
        Long taskId = task.getId();
        Long instanceId = instance.getId();

        Long addCount = 0L;
        Long delCount = 0L;
        Long updateCount = 0L;

        Long totalCount = 0L;
        Long successCount = 0L;


        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.start", dbScope.getDbName(), (StringUtils.isNotBlank(dbScope.getSchemaName()) ? "，schema=" + dbScope.getSchemaName() : "")));

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.snapshot", addCount, updateCount, delCount));

        // 1. Table list (no connection will be established)
        List<DbTable> tables = McTableSelection.select(task.getTableAllowlist(), dbScope.getDbName(),
                loadTablesByDatabase(dbQuery, task, instance, dbScope));
        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.tables.loaded", dbScope.getDbName(), tables.size()));
        if (CollectionUtils.isEmpty(tables)) {
            return null;
        }
        int size = tables.size();
        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.tables.detail", dbScope.getDbName(), (StringUtils.isNotBlank(dbScope.getSchemaName()) ? ", schema=" + dbScope.getSchemaName() : ""), size));
        // Excluded tables are skipped, so they must not become failed collections.
        totalCount += countCollectableTables(tables, dbScope.getDbName(), blacklist);
        List<McTableRespVO> tableRespDTOList = getMcTableById(task, instance, dbScope);

        // 2. Table-level comparison
        List<McTableSaveReqVO> mcTables = compareAndRecordTables(task, instance, dbScope, tables);


        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.tables.start", mcTables.size()));


        List<DbColumn> columns = DbType.HIVE.getDb().equals(dbQuery.getProperty().getDbType())
                ? List.of() : loadColumnsByTable(dbQuery, task, instance, dbScope);


        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.columns.loaded", dbScope.getDbName(), columns.size()));


        Map<String, List<DbColumn>> tableColumnMap = columns.stream()
                .collect(Collectors.groupingBy(DbColumn::getTableName));

        List<Long> updateTableIds = new ArrayList<>();
        // 3. Table loop
        for (McTableSaveReqVO table : mcTables) {
            if (notEmptyBlacklist) {
                String dbName = dbScope.getDbName();
                String tableName = table.getTableName();
                String fullTableName = dbName + "." + tableName;
                if (isInBlacklist(fullTableName, blacklist)) {
                    safeLog(instanceId, taskId,
                            MessageUtils.messageEn("mc.log.db.table.skip", fullTableName,
                                    StringUtils.defaultIfBlank(blacklist, MessageUtils.messageEn("mc.log.blacklist.empty"))));
                    continue;
                }

                safeLog(instanceId, taskId,
                        MessageUtils.messageEn("mc.log.db.table.pass", fullTableName,
                                StringUtils.isBlank(blacklist) ? MessageUtils.messageEn("mc.log.blacklist.empty") : ""));

            }

            try {
                List<DbColumn> finalDbColumns = DbType.HIVE.getDb().equals(dbQuery.getProperty().getDbType())
                        ? dbQuery.getDbQuery().getTableColumns(dbQuery.getProperty(), table.getTableName())
                        : tableColumnMap.get(table.getTableName());
                if (CollectionUtils.isEmpty(finalDbColumns)) {
                    safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.table.fail", table.getTableName(), MessageUtils.messageEn("mc.log.fields.not.obtained")));
                    continue;
                }

                TableProcessResult result = persistCollectedTable(() -> doProcessSingleTable(task, instance, dbScope, table, tableRespDTOList, finalDbColumns));
                if (result != null) {
                    addCount = addCount + result.getAddCount();
                    updateCount = updateCount + result.getUpdateCount();
                    successCount = successCount + result.getSuccessCount();
                    if (result.getUpdateCount() != 0 || result.getAddCount() != 0) {
                        updateTableIds.add(table.getId());
                    }
                }

            } catch (Exception e) {
                McExecutionFence.verifyOwner();
                safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.table.fail", table.getTableName(), e.getMessage()));
            }
        }

        //Update library storage size based on sum of table storage sizes
        mcTableTxService.runInTransaction(() -> mcDbMapper.updateStorageSizeById(dbScope.getId()));
        //Update the number of data rows in the database based on the sum of the number of rows in the table
        mcTableTxService.runInTransaction(() -> mcDbMapper.updateDataRowCountById(dbScope.getId()));

        //Update the number of fields in the metadata table
        mcTableTxService.runInTransaction(() -> mcDbMapper.updateColumnCountByDbId(dbScope.getId()));


        List<McTableRespVO> tablesOnlyInResp = findTablesOnlyInResp(mcTables, tableRespDTOList);
        tablesOnlyInResp.removeIf(table -> !McTableSelection.includes(task.getTableAllowlist(), dbScope.getDbName(), table.getTableName()));
        if (notEmptyBlacklist) {
            Iterator<McTableRespVO> iterator = tablesOnlyInResp.iterator();
            while (iterator.hasNext()) {
                McTableRespVO table = iterator.next();

                String dbName = table.getDbName();      // Confirm this field name
                String tableName = table.getTableName();
                String fullName = dbName + "." + tableName;

                // Blacklist judgment
                if (isInBlacklist(fullName, blacklist)) {
                    safeLog(instanceId, taskId,
                            MessageUtils.messageEn("mc.log.db.table.delete.resp", fullName,
                                    StringUtils.defaultIfBlank(blacklist, MessageUtils.messageEn("mc.log.blacklist.empty"))));

                    iterator.remove(); // Safe delete
                }
            }
        }

        delCount = delCount + tablesOnlyInResp.size();
        if (CollectionUtils.isNotEmpty(tablesOnlyInResp)) {
            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.delete.tables", tablesOnlyInResp.size()));
            for (McTableRespVO resp : tablesOnlyInResp) {
                safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.table.delete", resp.getTableName()));
            }
            List<Long> collect = tablesOnlyInResp.stream().map(a -> a.getId()).collect(Collectors.toList());
            mcTableTxService.runInTransaction(()->mcTableService.removeMcTable(collect));
        }
        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.db.complete", dbScope.getDbName(), addCount, updateCount, delCount, successCount));


        return new TableProcessResult(addCount, delCount, updateCount, totalCount, successCount, updateTableIds);
    }

    static long countCollectableTables(List<DbTable> tables, String dbName, String blacklist) {
        return tables.stream()
                .filter(table -> !isInBlacklist(dbName + "." + table.getTableName(), blacklist))
                .count();
    }

    /** Commit the table, all columns and both version histories as one indivisible collection result. */
    TableProcessResult persistCollectedTable(java.util.function.Supplier<TableProcessResult> collect) {
        return mcTableTxService.runInTransaction(() -> {
            var result = collect.get();
            if (result != null && CollectionUtils.isNotEmpty(result.getMcColumnReqList())) {
                var columns = mcColumnService.createMcColumnList(result.getMcColumnReqList());
                mcColumnLogService.createMcColumnLog(columns);
            }
            return result;
        });
    }

    private TableProcessResult doProcessSingleTable(McTaskRespVO task, McTaskInstanceDO instance, McDbSaveReqVO dbScope, McTableSaveReqVO table, List<McTableRespVO> tableRespDTOList, List<DbColumn> columns) {
        Long taskId = task.getId();
        Long instanceId = instance.getId();
        Long addCount = 0L;
        Long updateCount = 0L;
        Long successCount = 0L;

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.start", dbScope.getDbName() + "." + table.getTableName()));

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.columns", table.getTableName(), columns.size()));

        if (CollectionUtils.isEmpty(columns)) {
            return null;
        }

        List<McColumnSaveReqVO> columnReqDTOS = compareAndRecordColumns(task, instance, dbScope, table, columns);

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.columns.detail", table.getTableName(), columns.size()));

        McTableRespVO matched = findMatchedTable(table, tableRespDTOList);

        if (matched != null) {

            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.exist", table.getTableName()));

            table.setId(matched.getId());

            List<McColumnRespVO> mcColumnRespDTOList = getMcColumnByTaskId(table, instance, dbScope);

            boolean updated = isTableUpdated(table, matched, columnReqDTOS, mcColumnRespDTOList);
            boolean updated2 = isTableUpdated2(table, matched);
            if (updated || updated2) {

                safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.changed", table.getTableName()));

                updateCount++;
                // Collection refreshes physical metadata without unpublishing governed tables.
                table.setStatus(matched.getStatus());
                mcTableTxService.runInTransaction(() -> mcTableService.updateMcTable(table));
//                mcTableService.updateMcTable(table);

                safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.updated", table.getTableName()));

                if (!updated) {
                    // Row counts, storage size and indexes do not invalidate field identities or standards.
                    mcTableTxService.runInTransaction(() -> mcTableLogService.createMcTableLog(table));
                    successCount++;
                    return new TableProcessResult(addCount, updateCount, successCount, new ArrayList<>());
                }
                removeMcColumn(table, instance, dbScope);
            } else {
                safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.unchanged", table.getTableName()));
                successCount++;
                return new TableProcessResult(addCount, updateCount, successCount, new ArrayList<>());
            }

        } else {

            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.new", table.getTableName()));

            DatabaseDialect dialect = DatabaseDialectFactory.getDialect(mcDbMapper.findById(dbScope.getId()));
            if (dialect != null) {
                DatabaseDialect.TableMetadata statistics = dialect.getTableMetadata(mcDbMapper.findById(dbScope.getId()), table.getTableName());
                table.setRowCount(statistics.getRowCount());
                table.setStorageSize(statistics.getTableSize());
                table.setStorageEngine(statistics.getStorageEngine());
                table.setPartitionKey(statistics.getPartitionFields());
                table.setTbIndex(statistics.getIndexes());
            }
            Long mcTableId = mcTableTxService.runInTransaction(() -> mcTableService.createMcTable(table));
//            Long mcTableId = mcTableService.createMcTable(table);

            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.created", mcTableId, table.getTableName()));

            table.setId(mcTableId);
            addCount++;
            //All fields in the newly added table are set to version 1
            columnReqDTOS.forEach(columnReqDTO -> columnReqDTO.setVersion(1));
        }
        //Add table version change record
        Long mcTableLogId = mcTableTxService.runInTransaction(() -> mcTableLogService.createMcTableLog(table));

        for (McColumnSaveReqVO columnReqDTO : columnReqDTOS) {
            columnReqDTO.setTableId(table.getId());
            columnReqDTO.setMcTableLogId(mcTableLogId);
            columnReqDTO.setTbPartitionKey(table.getPartitionKey());
        }

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.table.columns.processed", table.getTableName(), columnReqDTOS.size()));

        successCount++;


        return new TableProcessResult(addCount, updateCount, successCount, columnReqDTOS);
    }


    private void removeMcColumn(McTableSaveReqVO table, McTaskInstanceDO instance, McDbSaveReqVO dbScope) {

        McColumnRespVO createReqVO = new McColumnRespVO();
        createReqVO.setTaskId(table.getTaskId());
        createReqVO.setTableId(table.getId());
        mcColumnService.removeMcColumn(createReqVO);
    }

    private boolean isTableUpdated(McTableSaveReqVO reqTable, McTableRespVO respTable, List<McColumnSaveReqVO> reqColumns, List<McColumnRespVO> respColumns) {

        // 1️⃣ Table comments are inconsistent → Update
        boolean result = false;
        StringBuilder updateMsg = new StringBuilder();
        Set<String> type = new HashSet<>();//Change type collection
        String reqComment = StringUtils.defaultString(reqTable.getTableComment());
        String respComment = StringUtils.defaultString(respTable.getTableComment());
        if (!reqComment.equals(respComment)) {
            result = true;
            updateMsg.append("Table comment changed; previous comment: ")
                    .append(respComment)
                    .append(", new comment: ")
                    .append(reqComment)
                    .append(";\n");
            type.add("1");
        }

        // 2️⃣ The number of fields is inconsistent → Update
        int reqSize = reqColumns == null ? 0 : reqColumns.size();
        int respSize = respColumns == null ? 0 : respColumns.size();
        if (reqSize != respSize) {
            result = true;
        }

        // 3️⃣ Build a Map of respColumns (columnName is unique)
        Map<String, McColumnRespVO> respColumnMap = new HashMap<>();
        if (respColumns != null) {
            for (McColumnRespVO respCol : respColumns) {
                respColumnMap.put(respCol.getColumnName(), respCol);
            }
        }
        Set<String> addColumnNames = new HashSet<>(), updateColumnNames = new HashSet<>(), deleteColumnNames = new HashSet<>();
        // 4️⃣ Loop reqColumns and judge field by field
        if (reqColumns != null) {
            for (McColumnSaveReqVO reqCol : reqColumns) {

                McColumnRespVO respCol = respColumnMap.get(reqCol.getColumnName());

                // Field does not exist → update
                if (respCol == null) {
                    result = true;
                    reqCol.setVersion(1);
                    reqCol.setUpdateType("1");
                    reqCol.setUpdateMsg("New column");
                    addColumnNames.add(reqCol.getColumnName());
                }

                if (respCol != null) {
                    reqCol.setDataElemId(respCol.getDataElemId());
                    reqCol.setStatus(respCol.getStatus());
                }
                // Field attributes are inconsistent → Update
                if (respCol != null && isColumnUpdated(reqCol, respCol)) {
                    result = true;
                    updateColumnNames.add(reqCol.getColumnName());
                }
            }
        }

        // 5 Check the deleted fields: traverse the original fields to see if they exist in the new field list
        if (respColumns != null) {
            for (McColumnRespVO respCol : respColumns) {
                boolean found = false;
                if (reqColumns != null) {
                    for (McColumnSaveReqVO reqCol : reqColumns) {
                        if (respCol.getColumnName().equals(reqCol.getColumnName())) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    result = true;
                    deleteColumnNames.add(respCol.getColumnName());
                }
            }
        }

        if (!addColumnNames.isEmpty()) {
            type.add("2");
            updateMsg.append("Added: ")
                    .append(addColumnNames.size())
                    .append(" columns: ")
                    .append(String.join(",", addColumnNames))
                    .append(";\n");
        }
        if (!deleteColumnNames.isEmpty()) {
            type.add("2");
            updateMsg.append("Deleted: ")
                    .append(deleteColumnNames.size())
                    .append(" columns: ")
                    .append(String.join(",", deleteColumnNames))
                    .append(";\n");
        }
        if (!updateColumnNames.isEmpty()) {
            type.add("2");
            updateMsg.append("Updated: ")
                    .append(updateColumnNames.size())
                    .append(" columns: ")
                    .append(String.join(",", updateColumnNames))
                    .append(";\n");
        }

        reqTable.setUpdateMsg(updateMsg.toString());
        reqTable.setUpdateType(String.join(",", type));
        int previousVersion = respTable.getVersion() == null ? 1 : respTable.getVersion();
        reqTable.setVersion(result ? Math.addExact(previousVersion, 1) : previousVersion);
        return result;
    }

    private boolean isTableUpdated2(McTableSaveReqVO reqTable, McTableRespVO respTable) {
        boolean result = false;
        StringBuilder updateMsg = new StringBuilder(StringUtils.defaultString(reqTable.getUpdateMsg()));
        Set<String> type = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(reqTable.getUpdateType())) type.addAll(Arrays.asList(reqTable.getUpdateType().split(",")));

        // Check whether the index field has changed and whether the storage size has changed. respTables is the data in the matched table.
        McTableDO mcTableDO = BeanUtils.toBean(respTable, McTableDO.class);
        // Get the index fields and storage size originally stored in the table
        String tbIndex = mcTableDO.getTbIndex();
        Integer storageSize = mcTableDO.getStorageSize();
        if (storageSize == null) {
            storageSize = 0;
        }
        // Get database metadata information, including database type
        McDbDO mcDbDO = mcDbMapper.findById(mcTableDO.getDbId());
        if (mcDbDO != null) {
            // Use the database dialect to obtain the number of rows, indexes, partition fields and other information of the table
            DatabaseDialect dialect = DatabaseDialectFactory.getDialect(mcDbDO);
            if (dialect != null) {
                // Obtain table metadata information in batches
                DatabaseDialect.TableMetadata metadata = dialect.getTableMetadata(mcDbDO, mcTableDO.getTableName());
                // Persist every sampled metric so the same value is not a change on every collection.
                reqTable.setTbIndex(metadata.getIndexes());
                reqTable.setStorageSize(metadata.getTableSize());
                reqTable.setRowCount(metadata.getRowCount());
                reqTable.setStorageEngine(metadata.getStorageEngine());
                reqTable.setPartitionKey(metadata.getPartitionFields());
                // Compare index fields and storage size
                if (!StringUtils.defaultString(tbIndex).equals(StringUtils.defaultString(metadata.getIndexes()))) {
                    result = true;
                    updateMsg.append("Table index fields changed; previous indexes: ")
                            .append(tbIndex)
                            .append(", new index fields: ")
                            .append(metadata.getIndexes())
                            .append(";\n");
                    type.add("3");
                }

                if (metadata.getTableSize() != null && !Objects.equals(mcTableDO.getStorageSize(), metadata.getTableSize())) {
                    result = true;
                    updateMsg.append("Table storage size changed; previous size: ")
                            .append(storageSize)
                            .append(", new size: ")
                            .append(metadata.getTableSize())
                            .append(";\n");
                    type.add("4");
                }
                if (metadata.getRowCount() != null && !Objects.equals(respTable.getRowCount(), metadata.getRowCount())) {
                    result = true;
                    updateMsg.append("Table row count changed; new count: ").append(metadata.getRowCount()).append(";\n");
                    type.add("4");
                }
            }
        }
        reqTable.setUpdateMsg(updateMsg.toString());
        reqTable.setUpdateType(String.join(",", type));
        return result;
    }

    private boolean isColumnUpdated(McColumnSaveReqVO req, McColumnRespVO resp) {

        StringBuilder updateMsg = new StringBuilder();
        Set<String> type = new HashSet<>();//Change type collection
        boolean result = false;

        // Field comment changes
        String reqComment = StringUtils.defaultString(req.getColumnComment());
        String respComment = StringUtils.defaultString(resp.getColumnComment());
        if (!reqComment.equals(respComment)) {
            result = true;
            updateMsg.append("Column comment changed; previous comment: ")
                    .append(respComment)
                    .append(", new comment: ")
                    .append(reqComment)
                    .append(";\n");
            type.add("1");
        }

        // Field type change
        String reqType = StringUtils.defaultString(req.getColumnType());
        String respType = StringUtils.defaultString(resp.getColumnType());
        if (!reqType.equals(respType)) {
            result = true;
            updateMsg.append("Column type changed; previous type: ").append(respType)
                    .append(", new type: ").append(reqType).append(";\n");
            type.add("2");
        }

        // Field length changes
        if (!Objects.equals(req.getColumnLength(), resp.getColumnLength())) {
            result = true;
            updateMsg.append("Column length changed; previous length: ")
                    .append(resp.getColumnLength())
                    .append(", new length: ")
                    .append(req.getColumnLength())
                    .append(";\n");
            type.add("3");
        }

        // Field precision changes
        if (!Objects.equals(req.getColumnPrecision(), resp.getColumnPrecision())) {
            result = true;
            updateMsg.append("Column precision changed; previous precision: ")
                    .append(resp.getColumnPrecision())
                    .append(", new precision: ")
                    .append(req.getColumnPrecision())
                    .append(";\n");
            type.add("4");
        }

        // Field decimal places change
        if (!Objects.equals(req.getColumnScale(), resp.getColumnScale())) {
            result = true;
            updateMsg.append("Column scale changed; previous scale: ")
                    .append(resp.getColumnScale())
                    .append(", new scale: ")
                    .append(req.getColumnScale())
                    .append(";\n");
            type.add("5");
        }

        // Field default value changed
        String reqDefault = StringUtils.defaultString(req.getDefaultValue());
        String respDefault = StringUtils.defaultString(resp.getDefaultValue());
        if (!reqDefault.equals(respDefault)) {
            result = true;
            updateMsg.append("Column default changed; previous value: ")
                    .append(respDefault)
                    .append(", new value: ")
                    .append(reqDefault)
                    .append(";\n");
            type.add("6");
        }

        // Primary key identifier change
        String reqPkFlag = StringUtils.defaultString(req.getPkFlag());
        String respPkFlag = StringUtils.defaultString(resp.getPkFlag());
        if (!reqPkFlag.equals(respPkFlag)) {
            result = true;
            updateMsg.append("Primary-key flag changed; previous flag: ")
                    .append(respPkFlag)
                    .append(", new flag: ")
                    .append(reqPkFlag)
                    .append(";\n");
            type.add("7");
        }

        // Foreign key identification change
        String reqFkFlag = StringUtils.defaultString(req.getFkFlag());
        String respFkFlag = StringUtils.defaultString(resp.getFkFlag());
        if (!reqFkFlag.equals(respFkFlag)) {
            result = true;
            updateMsg.append("Foreign-key flag changed; previous flag: ")
                    .append(respFkFlag)
                    .append(", new flag: ")
                    .append(reqFkFlag)
                    .append(";\n");
            type.add("8");
        }

        // Nullable flag change
        String reqNullableFlag = StringUtils.defaultString(req.getNullableFlag());
        String respNullableFlag = StringUtils.defaultString(resp.getNullableFlag());
        if (!reqNullableFlag.equals(respNullableFlag)) {
            result = true;
            updateMsg.append("Nullable flag changed; previous flag: ")
                    .append(respNullableFlag)
                    .append(", new flag: ")
                    .append(reqNullableFlag)
                    .append(";\n");
            type.add("9");
        }

        req.setUpdateMsg(updateMsg.toString());
        req.setUpdateType(String.join(",", type));
        //Field adjustment version plus 1
        if (result) {
            req.setVersion(resp.getVersion() != null ? resp.getVersion() + 1 : 1);
        } else {
            req.setVersion(resp.getVersion());
        }
        return result;
    }


    private List<McColumnRespVO> getMcColumnByTaskId(McTableSaveReqVO table, McTaskInstanceDO instance, McDbSaveReqVO dbScope) {
        McColumnRespVO createReqVO = new McColumnRespVO();
        createReqVO.setTaskId(table.getTaskId());
        createReqVO.setTableId(table.getId());
        return mcColumnService.getMcColumnList(createReqVO);
    }

    private List<McTableRespVO> findTablesOnlyInResp(List<McTableSaveReqVO> mcTables, List<McTableRespVO> tableRespDTOList) {

        List<McTableRespVO> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(tableRespDTOList)) {
            return result;
        }

        for (McTableRespVO resp : tableRespDTOList) {
            boolean exists = false;
            if (CollectionUtils.isNotEmpty(mcTables)) {
                for (McTableSaveReqVO req : mcTables) {
                    if (Objects.equals(req.getDbName(), resp.getDbName()) && Objects.equals(req.getSchemaName(), resp.getSchemaName()) && Objects.equals(req.getTableName(), resp.getTableName())) {
                        exists = true;
                        break;
                    }
                }
            }
            if (!exists) {
                result.add(resp);
            }
        }
        return result;
    }


    private McTableRespVO findMatchedTable(McTableSaveReqVO req, List<McTableRespVO> tableRespDTOList) {
        if (req == null || CollectionUtils.isEmpty(tableRespDTOList)) {
            return null;
        }

        for (McTableRespVO resp : tableRespDTOList) {
            if (Objects.equals(req.getDbId(), resp.getDbId()) && Objects.equals(req.getTableName(), resp.getTableName())) {
                return resp;
            }
        }
        return null;
    }


    private List<McTableRespVO> getMcTableById(McTaskRespVO task, McTaskInstanceDO instance, McDbSaveReqVO dbScope) {
        McTableRespVO mcTableReqDTO = new McTableRespVO();
        mcTableReqDTO.setTaskId(task.getId());
        mcTableReqDTO.setDbId(dbScope.getId());
        return mcTableService.getMcTableById(mcTableReqDTO);
    }

    private List<DbTable> loadTablesByDatabase(DbQueryContext dbQuery, McTaskRespVO task, McTaskInstanceDO instance, McDbSaveReqVO dbScope) {

        try {
            List<DbTable> tables = dbQuery.getDbQuery().getTables(dbQuery.getProperty());
            return tables == null ? new ArrayList<>() : tables;
        } catch (Exception e) {
            safeLog(instance.getId(), task.getId(), MessageUtils.messageEn("mc.log.load.table.exception", dbScope.getDbName(), e.getMessage()));
            return new ArrayList<>();
        }
    }

    private List<DbColumn> loadColumnsByTable(DbQueryContext dbQuery, McTaskRespVO task, McTaskInstanceDO instance, McDbSaveReqVO dbScope) {

        try {
            List<DbColumn> tableColumns = dbQuery.getDbQuery().getDbColumns(dbQuery.getProperty());
            return tableColumns == null ? new ArrayList<>() : tableColumns;
        } catch (Exception e) {
            safeLog(instance.getId(), task.getId(), MessageUtils.messageEn("mc.log.load.column.exception", dbScope.getDbName(), e.getMessage()));
            return new ArrayList<>();
        }
    }

    private void closeDbQuerySafely(DbQueryContext ctx, McTaskRespVO task, McTaskInstanceDO instance, McDbSaveReqVO dbScope) {
        try {
            ctx.getDbQuery().close();
        } catch (Exception e) {
            safeLog(instance.getId(), task.getId(), MessageUtils.messageEn("mc.log.close.connection.exception", dbScope.getDbName(), e.getMessage()));
        }
    }


    private List<McTaskScopeDO> loadDatabaseScopesFromDatasource(McTaskRespVO task, McTaskInstanceDO instance, DaDatasourceRespDTO datasource) {

        Long taskId = task.getId();
        Long instanceId = instance.getId();

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.mode.full"));

        // 1. Build DbQueryProperty
        DbQueryProperty baseProperty = new DbQueryProperty(datasource.getDatasourceType(), datasource.getIp(), datasource.getPort(), datasource.getDatasourceConfig());

        // 2. Get the database list (including hierarchy)
        List<DbName> dbNames;
        DbQuery rootQuery = dataSourceFactory.createDbQuery(baseProperty);
        try {
            if (!rootQuery.valid()) {
                safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.connection.failed"));
                throw new DataQueryException("mc.error.database.connection.fail", "Database connection failed");
            }
            dbNames = rootQuery.getDbNames(null);
        } finally {
            rootQuery.close();
        }

        List<McTaskScopeDO> scopeList = new ArrayList<>();
        if (CollectionUtils.isEmpty(dbNames)) {
            safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.no.database"));
            return scopeList;
        }

        // 3. Single-layer structure: direct mapping dbName
        if (dbNames.get(0).getLevel() == 1 && dbNames.get(0).getTotalLevels() == 1) {
            for (DbName dbName : dbNames) {
                McTaskScopeDO scope = new McTaskScopeDO();
                scope.setDbName(dbName.getDbName());
                scopeList.add(scope);
            }
            return scopeList;
        }

        // 4. Multi-layer structure: load subordinates and map db + schema
        for (DbName dbName : dbNames) {

            DbQueryProperty childProperty = baseProperty;
            if (DbType.KINGBASE8.getDb().equals(baseProperty.getDbType()) || DbType.POSTGRE_SQL.getDb()
                    .equals(baseProperty.getDbType())) {

                childProperty = baseProperty.copy();
                childProperty.setDbName(dbName.getDbName());
            }

            DbQuery childQuery = dataSourceFactory.createDbQuery(childProperty);
            try {
                if (!childQuery.valid()) {
                    continue;
                }
                List<DbName> children = childQuery.getDbNames(dbName);
                dbName.setChildren(children);
            } catch (Exception e) {
                safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.load.schema.failed", dbName.getDbName(), e.getMessage()));
            } finally {
                childQuery.close();
            }

            List<DbName> children = dbName.getChildren();
            if (CollectionUtils.isNotEmpty(children)) {
                for (DbName child : children) {
                    McTaskScopeDO scope = new McTaskScopeDO();
                    scope.setDbName(dbName.getDbName());
                    scope.setSchemaName(child.getDbName());
                    scopeList.add(scope);
                }
            } else {
                McTaskScopeDO scope = new McTaskScopeDO();
                scope.setDbName(dbName.getDbName());
                scopeList.add(scope);
            }
        }

        safeLog(instanceId, taskId, MessageUtils.messageEn("mc.log.databases.loaded", scopeList.size()));
        return scopeList;
    }


    private List<McTaskScopeDO> loadDatabaseScopesFromTask(McTaskRespVO task, McTaskInstanceDO instance) {

        safeLog(instance.getId(), task.getId(), MessageUtils.messageEn("mc.log.mode.incremental"));

        return task.getScopeSaveReqVOS();
    }

    private List<McDbSaveReqVO> compareAndRecordDatabaseScope(McTaskRespVO task, McTaskInstanceDO instance, List<McTaskScopeDO> databaseScopes, DaDatasourceRespDTO datasource) {
        List<McDbSaveReqVO> dbReqDTOList = new ArrayList<>();
        String creatorId = redisService.get(buildRunLockKey(task.getId()) + ":creatorId");
        String createBy = redisService.get(buildRunLockKey(task.getId()) + ":createBy");
        //TODO logic needs to be improved
        for (McTaskScopeDO databaseScope : databaseScopes) {

            McDbSaveReqVO createReqVO = new McDbSaveReqVO();
            //Collection ID
            createReqVO.setTaskId(task.getId());

            // ====== Source System ======
            createReqVO.setSourceSystemId(task.getSourceSystemId());
            createReqVO.setSourceSystemName(task.getSourceSystemName());

            // ====== Basic information of data source ======
            createReqVO.setDatasourceId(datasource.getId());
            createReqVO.setDbType(datasource.getDatasourceType());
            createReqVO.setIp(datasource.getIp());
            createReqVO.setPort(datasource.getPort() != null ? datasource.getPort().intValue() : null);
            createReqVO.setDatasourceConfig(datasource.getDatasourceConfig());
            createReqVO.setBelongingSystem(datasource.getDatasourceName());

            createReqVO.setCreateBy(StringUtils.isNotEmpty(createBy) ? createBy : "System Collection Task");
            createReqVO.setCreatorId(StringUtils.isNotEmpty(creatorId) ? Long.parseLong(creatorId) : 1L);

            // ====== Libraries/Patterns ======
            createReqVO.setDbName(databaseScope.getDbName());
            createReqVO.setSchemaName(databaseScope.getSchemaName());

            // ====== Description ======
            createReqVO.setDescription(databaseScope.getDescription());

            // ====== Status and flag bits (the backend can provide the details in a unified manner, and they are explicitly given here) ======
            createReqVO.setStatus("0");      // Unpublished
            createReqVO.setAuditStatus("2");
            createReqVO.setVersion(1);
            createReqVO.setAuditTime(new Date());

            dbReqDTOList.add(createReqVO);
        }

        return dbReqDTOList;
    }

    private List<McTableSaveReqVO> compareAndRecordTables(McTaskRespVO task, McTaskInstanceDO instance, McDbSaveReqVO dbScope, List<DbTable> tables) {
        List<McTableSaveReqVO> mcTableReqDTOList = new ArrayList<>();
        String creatorId = redisService.get(buildRunLockKey(task.getId()) + ":creatorId");
        String createBy = redisService.get(buildRunLockKey(task.getId()) + ":createBy");

        for (DbTable table : tables) {

            McTableSaveReqVO mcTableReqDTO = new McTableSaveReqVO();

            // ====== Related relationships ======
            mcTableReqDTO.setTaskId(task.getId());
            mcTableReqDTO.setDbId(dbScope.getId());
            mcTableReqDTO.setDatasourceId(task.getDatasourceId());

            // ====== Table basic information ======
            mcTableReqDTO.setTableName(table.getTableName());
            mcTableReqDTO.setTableComment(StringUtils.isEmpty(table.getTableComment()) ? "" : table.getTableComment());

            // ====== Libraries/Patterns ======
            mcTableReqDTO.setDbName(dbScope.getDbName());
            mcTableReqDTO.setSchemaName(dbScope.getSchemaName());

            mcTableReqDTO.setCreateBy(StringUtils.isNotEmpty(createBy) ? createBy : "System Collection Task");
            mcTableReqDTO.setCreatorId(StringUtils.isNotEmpty(creatorId) ? Long.parseLong(creatorId) : 1L);

            // ====== Status and Flags ======
            mcTableReqDTO.setStatus("0");     // Unpublished
            mcTableReqDTO.setVersion(1);
            mcTableReqDTO.setMasterFlag("1");
            mcTableReqDTO.setTempFlag("0");
            mcTableReqDTO.setAuditStatus("2");
            mcTableReqDTO.setAuditTime(new Date());

            // ====== Description ======
            mcTableReqDTO.setDescription(table.getTableComment());

            // ====== Call metadata service ======
//            Long mcTableId = mcTableService.createMcTable(mcTableReqDTO);
//
//            mcTableReqDTO.setId(mcTableId);
            mcTableReqDTOList.add(mcTableReqDTO);
        }

        return mcTableReqDTOList;
    }

    private List<McColumnSaveReqVO> compareAndRecordColumns(McTaskRespVO task, McTaskInstanceDO instance, McDbSaveReqVO dbScope, McTableSaveReqVO table, List<DbColumn> columns) {

        List<McColumnSaveReqVO> columnReqDTOS = new ArrayList<>();
        String creatorId = redisService.get(buildRunLockKey(task.getId()) + ":creatorId");
        String createBy = redisService.get(buildRunLockKey(task.getId()) + ":createBy");
        for (DbColumn column : columns) {
            if (null != column) {
                McColumnSaveReqVO createReqVO = new McColumnSaveReqVO();

                // ====== Related information ======
                createReqVO.setTaskId(task.getId());
                createReqVO.setDbId(dbScope.getId());
                createReqVO.setTableId(table.getId());
                createReqVO.setDatasourceId(task.getDatasourceId());

                // ====== Field basic information ======
                createReqVO.setColumnName(StringUtils.isEmpty(column.getColName()) ? "" : column.getColName());
                createReqVO.setColumnComment(StringUtils.isEmpty(column.getColComment()) ? "" : column.getColComment());
                createReqVO.setColumnType(StringUtils.isEmpty(column.getDataType()) ? "" : column.getDataType());

                // ====== Length/Precision ======
                createReqVO.setColumnLength(parseInt(column.getDataLength()));
                createReqVO.setColumnPrecision(parseInt(column.getDataPrecision()));
                createReqVO.setColumnScale(parseInt(column.getDataScale()));

                // ====== Default value ======
                createReqVO.setDefaultValue(column.getDataDefault());

                // ====== Primary key / nullable ======
                createReqVO.setPkFlag(Boolean.TRUE.equals(column.getColKey()) ? "1" : "0");
                createReqVO.setNullableFlag(Boolean.FALSE.equals(column.getNullable()) ? "1" : "0");
                createReqVO.setFkFlag("0");

                createReqVO.setCreateBy(StringUtils.isNotEmpty(createBy) ? createBy : "System Collection Task");
                createReqVO.setCreatorId(StringUtils.isNotEmpty(creatorId) ? Long.parseLong(creatorId) : 1L);

                // ====== Status and Flags ======
                createReqVO.setStatus("0");     // Unpublished
                createReqVO.setVersion(1);
                createReqVO.setAuditStatus("2");
                createReqVO.setAuditTime(new Date());

                // ====== Description ======
                createReqVO.setDescription(column.getColComment());


                columnReqDTOS.add(createReqVO);
                // ====== Call field metadata service ======

                // If you need to write back columnId, you can extend the field in DbColumn
            }
        }
        return columnReqDTOS;
    }

    /**
     * Safe String -> Integer conversion
     */
    private Integer parseInt(String val) {
        if (val == null || val.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Verify whether tasks are repeated
     *
     * @param reqVO task information
     * @param excludeTaskId Excluded task ID (used to exclude itself when updating)
     */
    private void validateDuplicateTask(McTaskSaveReqVO reqVO, Long excludeTaskId) {
        String collectionScope = reqVO.getCollectionScope();
        Long datasourceId = reqVO.getDatasourceId();

        if (datasourceId == null) {
            return;
        }

        // All databases (0-all databases)
        if (CollectionScopeEnum.isAll(collectionScope)) {
            // Check if there are any tasks already for this data source
            boolean exists = mcTaskMapper.existsByDatasourceId(datasourceId, excludeTaskId);
            if (exists) {
                throw new ServiceException(MessageUtils.message("mc.error.datasource.conflict"), HttpStatus.CONFLICT);
            }
        }
        // Custom Library (1-Custom Library)
        else if (CollectionScopeEnum.isCustom(collectionScope)) {
            // 1. Check whether all library schemas exist and have the same data source
            boolean hasAllScope = mcTaskMapper.existsByDatasourceAndScope(datasourceId, CollectionScopeEnum.ALL.getScope(), excludeTaskId);
            if (hasAllScope) {
                throw new ServiceException(MessageUtils.messageEn("mc.error.datasource.full.conflict"), HttpStatus.CONFLICT);
            }

            // 2. Check whether there are custom library tasks with the same data source and duplicate libraries in the collection range.
            List<McTaskDO> existCustomTasks = mcTaskMapper.selectByDatasourceAndScope(datasourceId, CollectionScopeEnum.CUSTOM.getScope(), excludeTaskId);
            if (!CollectionUtils.isEmpty(existCustomTasks)) {
                // Get the collection range of the current task
                List<McTaskScopeSaveReqVO> currentScopes = reqVO.getScopeSaveReqVOS();
                if (CollectionUtils.isEmpty(currentScopes)) {
                    return;
                }

                // Historical tasks may already share a scope. Editing their table allowlist
                // must not create a new library conflict; new scopes still need validation.
                List<McTaskScopeDO> retainedScopes = List.of();
                if (excludeTaskId != null) {
                    McTaskDO previous = mcTaskMapper.selectById(excludeTaskId);
                    if (previous != null && Objects.equals(previous.getDatasourceId(), datasourceId)
                            && CollectionScopeEnum.isCustom(previous.getCollectionScope())) {
                        retainedScopes = mcTaskScopeService.getMcTaskScopeListBytaskId(excludeTaskId);
                    }
                }

                for (McTaskDO existTask : existCustomTasks) {
                    // Get the collection range of existing tasks
                    List<McTaskScopeDO> existScopes = mcTaskScopeService.getMcTaskScopeListBytaskId(existTask.getId());
                    if (CollectionUtils.isEmpty(existScopes)) {
                        continue;
                    }

                    // Check if there are duplicate libraries
                    for (McTaskScopeSaveReqVO currentScope : currentScopes) {
                        boolean alreadyOwned = false;
                        for (McTaskScopeDO owned : retainedScopes) {
                            if (isSameDatabase(currentScope, owned)) { alreadyOwned = true; break; }
                        }
                        if (alreadyOwned) continue;
                        for (McTaskScopeDO existScope : existScopes) {
                            if (isSameDatabase(currentScope, existScope)) {
                                String dbName = currentScope.getDbName();
                                String schemaName = currentScope.getSchemaName();
                                String dbInfo = StringUtils.isNotBlank(schemaName)
                                        ? dbName + "." + schemaName
                                        : dbName;
                                throw new ServiceException(MessageUtils.messageEn("mc.error.scope.conflict", dbInfo), HttpStatus.CONFLICT);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Determine whether two collection ranges point to the same database
     *
     * @param scope1 collection scope 1
     * @param scope2 collection scope 2
     * @return Is it the same?
     */
    private boolean isSameDatabase(McTaskScopeSaveReqVO scope1, McTaskScopeDO scope2) {
        if (scope1 == null || scope2 == null) {
            return false;
        }
        return Objects.equals(scope1.getDbName(), scope2.getDbName()) &&
                Objects.equals(scope1.getSchemaName(), scope2.getSchemaName());
    }

    @Override
    public List<McTaskSourceTreeRespVO> getSourceSystemTree() {
        // 1. Get all valid source systems
        List<AttSourceSystemRespDTO> validSourceSystems = attSourceSystemApiService.getValidSourceSystems();
        if (CollectionUtils.isEmpty(validSourceSystems)) {
            return Lists.newArrayList();
        }

        // 2. Query all tasks for building data sources and database nodes
        List<McTaskDO> allTasks = mcTaskMapper.selectList();
        Map<Long, List<McTaskDO>> tasksBySourceSystemMap = Maps.newHashMap();
        List<DaDatasourceRespDTO> daDatasourceRespDTOList = Lists.newArrayList();
        if (CollectionUtils.isNotEmpty(allTasks)) {
            // Group tasks by source system ID
            tasksBySourceSystemMap = allTasks.stream()
                    .filter(task -> task.getSourceSystemId() != null)
                    .collect(Collectors.groupingBy(McTaskDO::getSourceSystemId));
            // 3. Obtain data source information involved in all tasks
            Set<Long> datasourceIds = allTasks.stream()
                    .map(McTaskDO::getDatasourceId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            daDatasourceRespDTOList = daDatasourceApiService
                    .getDatabaseListByIds(new ArrayList<>(datasourceIds));
        }

        Map<Long, DaDatasourceRespDTO> daDatasourceRespDTOMap = daDatasourceRespDTOList
                .stream()
                .collect(Collectors.toMap(DaDatasourceRespDTO::getId, daDatasourceRespDTO -> daDatasourceRespDTO));

        List<McTaskSourceTreeRespVO> treeList = Lists.newArrayList();

        // 4. Build a first-level node: source system
        for (AttSourceSystemRespDTO sourceSystem : validSourceSystems) {
            McTaskSourceTreeRespVO sourceNode = new McTaskSourceTreeRespVO();
            sourceNode.setId(sourceSystem.getId());
            sourceNode.setName(sourceSystem.getName());
            sourceNode.setType("SOURCE");

            // 5. Get all tasks under the source system
            List<McTaskDO> sourceSystemTasks = tasksBySourceSystemMap.getOrDefault(sourceSystem.getId(), Lists.newArrayList());

            // 6. Build secondary nodes: data source (remove duplication)
            Map<Long, List<McTaskDO>> datasourceGroupMap = sourceSystemTasks.stream()
                    .filter(task -> task.getDatasourceId() != null)
                    .collect(Collectors.groupingBy(McTaskDO::getDatasourceId));

            List<McTaskSourceTreeRespVO> datasourceChildren = Lists.newArrayList();
            for (Map.Entry<Long, List<McTaskDO>> dsEntry : datasourceGroupMap.entrySet()) {
                Long datasourceId = dsEntry.getKey();
                List<McTaskDO> dsTasks = dsEntry.getValue();
                if (CollectionUtils.isEmpty(dsTasks)) {
                    continue;
                }

                DaDatasourceRespDTO daDatasourceRespDTO = daDatasourceRespDTOMap.get(datasourceId);

                McTaskSourceTreeRespVO datasourceNode = new McTaskSourceTreeRespVO();
                datasourceNode.setId(datasourceId);
                datasourceNode.setName(daDatasourceRespDTO == null ? "" : daDatasourceRespDTO.getDatasourceName());
                datasourceNode.setDatasourceType(daDatasourceRespDTO == null ? "" : daDatasourceRespDTO.getDatasourceType());
                datasourceNode.setType("DATASOURCE");

                // 7. Build a third-level node: database (a database that merges all tasks)
                List<McTaskSourceTreeRespVO> dbChildren = Lists.newArrayList();
                for (McTaskDO task : dsTasks) {
                    List<McTaskSourceTreeRespVO> taskDbNodes = buildDatabaseNodes(task);
                    dbChildren.addAll(taskDbNodes);
                }
                datasourceNode.setChildren(dbChildren);

                datasourceChildren.add(datasourceNode);
            }

            sourceNode.setChildren(datasourceChildren);
            treeList.add(sourceNode);
        }

        return treeList;
    }

    /**
     * Build database node list
     *
     * @param task collection task
     * @return database node list
     */
    private List<McTaskSourceTreeRespVO> buildDatabaseNodes(McTaskDO task) {
        List<McTaskSourceTreeRespVO> dbNodes = Lists.newArrayList();

        // Collection range: 2-all databases, 1-custom databases
        if (CollectionScopeEnum.isAll(task.getCollectionScope())) {
            // All databases: Query all databases under this data source
            List<McDbDO> allDbs = mcDbMapper.selectList(
                    new QueryWrapper<McDbDO>()
                            .eq("DATASOURCE_ID", task.getDatasourceId())
                            .eq("DEL_FLAG", "0")
            );

            if (CollectionUtils.isNotEmpty(allDbs)) {
                for (McDbDO db : allDbs) {
                    McTaskSourceTreeRespVO dbNode = new McTaskSourceTreeRespVO();
                    dbNode.setId(db.getId());
                    dbNode.setName(db.getDbName());
                    dbNode.setType("DATABASE");
                    dbNode.setTaskId(task.getId());
                    dbNodes.add(dbNode);
                }
            }
        } else if (CollectionScopeEnum.isCustom(task.getCollectionScope())) {
            List<McDbDO> allDbs = mcDbMapper.selectList(
                    new QueryWrapper<McDbDO>()
                            .eq("DATASOURCE_ID", task.getDatasourceId())
                            .eq("DEL_FLAG", "0")
            );

            if (CollectionUtils.isNotEmpty(allDbs)) {
                for (McDbDO db : allDbs) {
                    McTaskSourceTreeRespVO dbNode = new McTaskSourceTreeRespVO();
                    dbNode.setId(db.getId());
                    dbNode.setName(db.getDbName());
                    dbNode.setType("DATABASE");
                    dbNode.setTaskId(task.getId());
                    dbNodes.add(dbNode);
                }
            }
            // Custom library: Query the collection range of the task
            //List<McTaskScopeDO> scopes = mcTaskScopeService.getMcTaskScopeListBytaskId(task.getId());
            //
            //if (CollectionUtils.isNotEmpty(scopes)) {
            //    for (McTaskScopeDO scope : scopes) {
            //        McTaskSourceTreeRespVO dbNode = new McTaskSourceTreeRespVO();
            //        dbNode.setId(scope.getId());
            //        dbNode.setName(StringUtils.isNotBlank(scope.getSchemaName())
            //                ? scope.getDbName() + "." + scope.getSchemaName()
            //                : scope.getDbName());
            //        dbNode.setType("DATABASE");
            //        dbNode.setTaskId(task.getId());
            //        dbNodes.add(dbNode);
            //    }
            //}
        }

        return dbNodes;
    }
}
