package tech.qiantong.qdata.module.mc.controller.admin.task;

import cn.hutool.core.date.DateUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.domain.BatchDeleteCheck;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.page.PageParam;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.mc.controller.admin.task.vo.*;
import tech.qiantong.qdata.module.mc.convert.task.McTaskConvert;
import tech.qiantong.qdata.module.mc.dal.dataobject.task.McTaskDO;
import tech.qiantong.qdata.module.mc.service.task.IMcTaskService;
import tech.qiantong.qdata.security.annotation.BizDataScope;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Collection taskController
 *
 * @author qdata
 * @date 2025-12-16
 */
@Tag(name = "采集任务")
@RestController
@RequestMapping("/mc/task")
@Validated
public class McTaskController extends BaseController {
    @Resource private tech.qiantong.qdata.module.mc.service.task.CollectionResourceAccess resources;
    @Resource private tech.qiantong.qdata.module.mc.service.task.CollectionProjectAccess projects;

    @Resource private tech.qiantong.qdata.module.mc.service.metadata.MetadataResourceAccess metadataResources;
    @Resource
    private IMcTaskService mcTaskService;

    @GetMapping("/projects")
    public CommonResult<List<Map<String,Object>>> projects(){return CommonResult.success(projects.projects());}

    @GetMapping("/sources")
    public CommonResult<List<Map<String,Object>>> sources(@RequestParam Long projectId){return CommonResult.success(projects.sources(projectId));}

    @GetMapping("/sourceSystems")
    public CommonResult<List<Map<String,Object>>> sourceSystems(@RequestParam Long projectId){return CommonResult.success(projects.sourceSystems(projectId));}

    @GetMapping("/leaders")
    public CommonResult<List<Map<String,Object>>> leaders(@RequestParam Long projectId){return CommonResult.success(projects.leaders(projectId));}

    @Operation(summary = "查询采集任务列表")
    @BizDataScope(code = "mc_task_list", userField = "leader", deptField = "responsibleDept")
    @GetMapping("/list")
    public CommonResult<PageResult<McTaskRespVO>> list(McTaskPageReqVO mcTask) {
        mcTask.setAccessibleIds(resources.taskIds());
        PageResult<McTaskDO> page = mcTaskService.getMcTaskPage(mcTask);
        var result=BeanUtils.toBean(page, McTaskRespVO.class);result.getRows().forEach(resources::present);return CommonResult.success(result);
    }

    @Operation(summary = "导出采集任务列表")
    @BizDataScope(code = "mc_task_list", userField = "leader", deptField = "responsibleDept")
    @Log(title = "log.op.title.mc.task", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, McTaskPageReqVO exportReqVO) {
        exportReqVO.setAccessibleIds(resources.taskIds("export"));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<McTaskDO> list = (List<McTaskDO>) mcTaskService.getMcTaskPage(exportReqVO).getRows();
        ExcelUtil<McTaskRespVO> util = new ExcelUtil<>(McTaskRespVO.class);
        util.exportExcel(response, McTaskConvert.INSTANCE.convertToRespVOList(list), "Application Management Data");
    }

    @Operation(summary = "导入采集任务列表")
    @Log(title = "log.op.title.mc.task", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<McTaskRespVO> util = new ExcelUtil<>(McTaskRespVO.class);
        List<McTaskRespVO> importExcelList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = mcTaskService.importMcTask(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取采集任务详细信息")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<McTaskRespVO> getInfo(@PathVariable("id") Long id) {
        resources.task(id,"read");
        var detail=mcTaskService.getMcTaskByIdNew(id);
        if(detail!=null && detail.getDatasourceDO()!=null) {
            var source=BeanUtils.toBean(detail.getDatasourceDO(),tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO.class);
            source.setDatasourceConfig(tech.qiantong.qdata.common.database.core.ConnectionDisplayConfig.sanitize(source.getDatasourceConfig()));
            detail.setDatasourceDO(source);
        }
        return CommonResult.success(resources.present(detail));
    }

    @Operation(summary = "新增采集任务")
    @Log(title = "log.op.title.mc.task", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody McTaskSaveReqVO mcTask) {
        resources.save(mcTask,false);
        mcTask.setCreatorId(getUserId());
        mcTask.setCreateBy(getNickName());
        mcTask.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(mcTaskService.createMcTask(mcTask));
    }

    @Operation(summary = "修改采集任务")
    @Log(title = "log.op.title.mc.task", businessType = BusinessType.UPDATE)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody McTaskSaveReqVO mcTask) {
        resources.save(mcTask,true);
        mcTask.setUpdatorId(getUserId());
        mcTask.setUpdateBy(getNickName());
        mcTask.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(mcTaskService.updateMcTask(mcTask));
    }

    @Operation(summary = "删除采集任务")
    @Log(title = "log.op.title.mc.task", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)resources.task(id,"remove");
        return CommonResult.toAjax(mcTaskService.removeMcTask(Arrays.asList(ids)));
    }

    @Operation(summary = "删除采集任务")
    @Log(title = "log.op.title.mc.task", businessType = BusinessType.DELETE)
    @GetMapping("/batchDeleteCheck/{ids}")
    public CommonResult<BatchDeleteCheck<Long>> batchDeleteCheck(@PathVariable Long[] ids) {
        for(Long id:ids)resources.task(id,"remove");
        BatchDeleteCheck<Long> result = mcTaskService.batchDeleteCheck(Arrays.asList(ids));
        return CommonResult.success(result);
    }

    @Operation(summary = "获取采集范围信息")
    @GetMapping(value = "/getRealtimeMcTaskScopeList/{id}")
    public CommonResult<List<McTaskScopeRespVO>> getRealtimeMcTaskScopeList(@PathVariable("id") Long id) {
        resources.source(id);
        return CommonResult.success(resources.visibleDatabases(id,BeanUtils.toBean(mcTaskService.getRealtimeMcTaskScopeList(id), McTaskScopeRespVO.class)));
    }



    @Operation(summary = "上下线-任务")
    @PostMapping("/updateReleaseJobTask")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult updateReleaseJobTask(@Valid @RequestBody McTaskSaveReqVO mcTask) {
        resources.existingRequest(mcTask,"release");
        Map<String, Object> result = mcTaskService.updateReleaseJobTask(mcTask);
        return CommonResult.success(result);
    }

    @Operation(summary = "上下线-调度")
    @PostMapping("/updateReleaseSchedule")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult updateReleaseSchedule(@Valid @RequestBody McTaskSaveReqVO mcTask) {
        resources.existingRequest(mcTask,"schedule");
        Map<String, Object> result = mcTaskService.updateReleaseSchedule(mcTask);
        return CommonResult.success(result);
    }

    @Operation(summary = "上下线-调度")
    @PostMapping("/runJobOnce")
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResult runJobOnce(@Valid @RequestBody McTaskSaveReqVO mcTask) {
        resources.existingRequest(mcTask,"execute");
        mcTask.setCreatorId(super.getUserId());
        mcTask.setCreateBy(super.getNickName());
        Map<String, Object> result = mcTaskService.runJobOnce(mcTask);
        return CommonResult.success(result);
    }

    @Operation(summary = "获取来源系统树形结构")
    @GetMapping("/sourceSystemTree")
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasAnyPermi('mc:metadata:table:list,mc:task:list')")
    public CommonResult<List<McTaskSourceTreeRespVO>> getSourceSystemTree() {
        List<McTaskSourceTreeRespVO> treeList = mcTaskService.getSourceSystemTree();
        return CommonResult.success(metadataResources.sourceTree(treeList));
    }


}
