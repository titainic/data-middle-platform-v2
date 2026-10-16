/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.module.dpp.controller.admin.etl;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.Arrays;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.*;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService;

/**
 * Data Integration Task Controller
 *
 * @author qdata
 * @date 2025-02-13
 */
@Tag(name = "Data Integration Task")
@RestController
@RequestMapping("/dpp/etlTask")
@Validated
public class DppEtlTaskController extends BaseController {
    @Resource
    private IDppEtlTaskService dppEtlTaskService;
    @Resource
    private tech.qiantong.qdata.module.dpp.service.etl.DevelopmentResourceAccess resourceAccess;
    private void scope(DppEtlTaskPageReqVO request){resourceAccess.readType(request.getType());request.setAccessibleTaskIds(resourceAccess.taskIds());}

    @Operation(summary = "查询数据集成任务列表")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DppEtlTaskRespVO>> list(DppEtlTaskPageReqVO dppEtlTask) {
        if (StringUtils.isBlank(dppEtlTask.getType())) {
            dppEtlTask.setType("1");// Default offline data integration
        }
        scope(dppEtlTask);
        PageResult<DppEtlTaskDO> page = dppEtlTaskService.getDppEtlTaskPage(dppEtlTask);
        PageResult<DppEtlTaskRespVO> result=BeanUtils.toBean(page,DppEtlTaskRespVO.class);resourceAccess.present(result.getRows());
        return CommonResult.success(result);
    }

    @Operation(summary = "查询数据集成任务列表")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:list')")
    @GetMapping("/getDppEtlTaskPage")
    public CommonResult<PageResult<DppEtlTaskRespVO>> getDppEtlTaskPageList(DppEtlTaskPageReqVO dppEtlTask) {
        if (StringUtils.isBlank(dppEtlTask.getType())) {
            dppEtlTask.setType("1");// Default offline data integration
        }
        scope(dppEtlTask);
        PageResult<DppEtlTaskRespVO> result=dppEtlTaskService.getDppEtlTaskPageList(dppEtlTask);resourceAccess.present(result.getRows());
        return CommonResult.success(result);
    }

    @Operation(summary = "Get data integration task statistics")
    @GetMapping("/statistics")
    public CommonResult<DppEtlTaskStatisticsRespVO> statistics(
            @RequestParam Long projectId,
            @RequestParam String projectCode,
            @RequestParam(defaultValue = "1") String taskType) {
        resourceAccess.project(projectId,projectCode);resourceAccess.readType(taskType,projectId);
        return CommonResult.success(
                dppEtlTaskService.getStatistics(projectId, projectCode, taskType));
    }


    @Operation(summary = "删除数据集成任务")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:remove')")
    @Log(title = "log.op.title.dpp.etl", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)resourceAccess.task(id,"remove");
        return CommonResult.toAjax(dppEtlTaskService.removeDppEtlTask(Arrays.asList(ids)));
    }

    /**
     * 2025-06-18 revision, this is the historical version
     *
     * @param dppEtlNewNodeSaveReqVO
     * @return
     */
    @Operation(summary = "新增节点")
    @PostMapping("/createProcessDefinitionEx")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult createProcessDefinition(@Validated(DppEtlNewNodeSaveReqVO.TaskSave.class) @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        if (StringUtils.isBlank(dppEtlNewNodeSaveReqVO.getType())) {
            dppEtlNewNodeSaveReqVO.setType("1");// Default offline data integration
        }
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"add");
        DppEtlTaskSaveReqVO result = dppEtlTaskService.createProcessDefinition(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "上下线")
    @PostMapping("/updateReleaseTask")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult releaseTask(@Valid @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"edit");
        Map<String, Object> result = dppEtlTaskService.updateReleaseTask(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }


    @Operation(summary = "上下线-任务")
    @PostMapping("/updateReleaseJobTask")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult updateReleaseJobTask(@Valid @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"edit");
        Map<String, Object> result = dppEtlTaskService.updateReleaseJobTask(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "上下线-调度")
    @PostMapping("/updateReleaseSchedule")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult updateReleaseSchedule(@Valid @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"edit");
        Map<String, Object> result = dppEtlTaskService.updateReleaseSchedule(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "修改调度cron表达式")
    @PostMapping("/releaseTaskCrontab")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult releaseTaskCrontab(@Valid @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"edit");
        Map<String, Object> result = dppEtlTaskService.releaseTaskCrontab(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "新增数据集成节点-获取唯一健")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:add')")
    @GetMapping("/getNodeUniqueKey")
    public CommonResult<Long> getNodeUniqueKey(DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        resourceAccess.request(dppEtlNewNodeSaveReqVO,dppEtlNewNodeSaveReqVO.getId()==null?"add":"edit");
        return CommonResult.success(dppEtlTaskService.getNodeUniqueKey(dppEtlNewNodeSaveReqVO));
    }

    @Operation(summary = "获取数据集成任务详细信息")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DppEtlTaskRespVO> getInfo(@PathVariable("id") Long id) {
        resourceAccess.task(id);
        return CommonResult.success(dppEtlTaskService.getDppEtlTaskById(id));
    }


    @Operation(summary = "获取数据集成任务详细信息")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:query')")
    @GetMapping(value = "/updateQuery/{id}")
    public CommonResult<DppEtlTaskUpdateQueryRespVO> getuUpdateQueryInfo(@PathVariable("id") Long id) {
        resourceAccess.task(id);
        return CommonResult.success(dppEtlTaskService.getuUpdateQueryInfo(id));
    }


    @Operation(summary = "修改节点")
    @PostMapping("/updateProcessDefinition")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult updateProcessDefinition(@Validated(DppEtlNewNodeSaveReqVO.TaskSave.class) @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"edit");
        DppEtlTaskSaveReqVO result = dppEtlTaskService.updateProcessDefinition(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "查询数据集成任务列表")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:list')")
    @GetMapping("/getDppEtlTaskListTree")
    public CommonResult<List<DppEtlTaskTreeRespVO>> getDppEtlTaskListTree(DppEtlTaskPageReqVO dppEtlTask) {
        resourceAccess.readAny();if(dppEtlTask.getProjectId()!=null || StringUtils.isNotBlank(dppEtlTask.getProjectCode()))dppEtlTask.setProjectId(resourceAccess.resolveProject(dppEtlTask.getProjectId(),dppEtlTask.getProjectCode()));dppEtlTask.setAccessibleTaskIds(resourceAccess.taskIds());
        return CommonResult.success(dppEtlTaskService.getDppEtlTaskListTree(dppEtlTask));
    }


    @Operation(summary = "查询数据集成任务列表")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:list')")
    @GetMapping("/getSubTaskStatusList")
    public CommonResult<List<DppEtlTaskRespVO>> getSubTaskStatusList(DppEtlTaskPageReqVO dppEtlTask) {
        resourceAccess.task(dppEtlTask.getId());var allowed=new java.util.HashSet<>(resourceAccess.taskIds());
        return CommonResult.success(dppEtlTaskService.getSubTaskStatusList(dppEtlTask).stream().filter(task->allowed.contains(task.getId())).toList());
    }


    @Log(title = "log.op.title.dpp.task.trigger", businessType = BusinessType.UPDATE)
    @PutMapping("/startDppEtlTask/{id}")
    public AjaxResult startDppEtlTask(@PathVariable("id") Long id) {
        resourceAccess.task(id,"execute");
        return dppEtlTaskService.startDppEtlTask(id);
    }


    @Operation(summary = "新增数据汇聚任务")
    @PostMapping("/createEtlTask")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult createEtlTask(@Validated(DppEtlNewNodeSaveReqVO.TaskSave.class) @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        dppEtlNewNodeSaveReqVO.setType("1");// Default offline data integration
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"add");
        DppEtlTaskSaveReqVO result = dppEtlTaskService.createEtlTask(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "修改数据汇聚任务")
    @PostMapping("/updateEtlTask")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult updateEtlTask(@Validated(DppEtlNewNodeSaveReqVO.TaskSave.class) @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        dppEtlNewNodeSaveReqVO.setType("1");// Default offline data integration
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"edit");
        DppEtlTaskSaveReqVO result = dppEtlTaskService.updateEtlTask(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "新增数据汇聚任务")
    @PostMapping("/createEtlTaskFront")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult createEtlTaskFront(@Validated(DppEtlNewNodeSaveReqVO.TaskSave.class) @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        if (StringUtils.isBlank(dppEtlNewNodeSaveReqVO.getType())) {
            dppEtlNewNodeSaveReqVO.setType("1");// Default offline data integration
        }
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"add");
        DppEtlNewNodeSaveReqVO result = dppEtlTaskService.createEtlTaskFront(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "新增数据汇聚任务")
    @PostMapping("/createEtlTaskFrontPostposition")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult createEtlTaskFrontPostposition(@Validated(DppEtlNewNodeSaveReqVO.TaskSave.class) @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        if (StringUtils.isBlank(dppEtlNewNodeSaveReqVO.getType())) {
            dppEtlNewNodeSaveReqVO.setType("1");// Default offline data integration
        }
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"edit");
        DppEtlTaskSaveReqVO result = dppEtlTaskService.createEtlTaskFrontPostposition(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "复制数据汇聚任务")
    @PostMapping("/copyCreateEtl")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult copyCreateEtl(@Valid @RequestBody DppEtlNewNodeSaveReqVO dppEtlNewNodeSaveReqVO) {
        if (StringUtils.isBlank(dppEtlNewNodeSaveReqVO.getType())) {
            dppEtlNewNodeSaveReqVO.setType("1");// Default offline data integration
        }
        resourceAccess.request(dppEtlNewNodeSaveReqVO,"add");
        DppEtlTaskSaveReqVO result = dppEtlTaskService.copyCreateEtl(dppEtlNewNodeSaveReqVO);
        return CommonResult.success(result);
    }

    @Operation(summary = "获取数据集成任务详细信息--前置草稿任务")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTask:query')")
    @GetMapping(value = "/updateQueryFront/{id}")
    public CommonResult<DppEtlTaskUpdateQueryRespVO> getupdateQueryFront(@PathVariable("id") Long id) {
        resourceAccess.task(id);
        return CommonResult.success(dppEtlTaskService.getupdateQueryFront(id));
    }

    @GetMapping("/capabilities")
    public CommonResult<Map<String,Object>> capabilities(@RequestParam Long projectId,@RequestParam(required=false) String projectCode,@RequestParam(defaultValue="1") String type){
        return CommonResult.success(resourceAccess.capabilities(projectId,projectCode,type));
    }

}
