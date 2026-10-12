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

package tech.qiantong.qdata.module.dpp.controller.admin.qa;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import cn.hutool.core.date.DateUtil;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.page.PageParam;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.common.exception.enums.GlobalErrorCodeConstants;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskAssetReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskPageReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskRespVO;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskSaveReqVO;
import tech.qiantong.qdata.module.dpp.convert.qa.DppQualityTaskConvert;
import tech.qiantong.qdata.module.dpp.dal.dataobject.qa.DppQualityTaskDO;
import tech.qiantong.qdata.module.dpp.service.qa.IDppQualityTaskService;

/**
 * Data Quality Task Controller
 *
 * @author Chaos
 * @date 2025-07-21
 */
@Tag(name = "Data Quality Task")
@RestController
@RequestMapping("/dpp/qualityTask")
@Validated
public class DppQualityTaskController extends BaseController {
    @Resource
    private tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess qualityAccess;
    @Resource
    private IDppQualityTaskService dppQualityTaskService;
    @GetMapping("/projects")
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:index') or @ss.hasPermi('da:qualityTask:add')")
    public CommonResult<?> projects(){return CommonResult.success(qualityAccess.projectOptions());}


    @Operation(summary = "查询数据质量任务列表")
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:index')")
    @GetMapping("/list")
    public CommonResult<PageResult<DppQualityTaskRespVO>> list(DppQualityTaskPageReqVO dppQualityTask) {
        dppQualityTask.setAccessibleTaskIds(qualityAccess.visibleTasks("index",dppQualityTask.getProjectId()));
        PageResult<DppQualityTaskDO> page = dppQualityTaskService.getDppQualityTaskPage(dppQualityTask);
        PageResult<DppQualityTaskRespVO> result=BeanUtils.toBean(page,DppQualityTaskRespVO.class);result.getRows().forEach(qualityAccess::present);return CommonResult.success(result);
    }

    @Operation(summary = "导出数据质量任务列表")
    @Log(title = "log.op.title.dpp.quality", businessType = BusinessType.EXPORT)
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:index')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, DppQualityTaskPageReqVO exportReqVO) {
        exportReqVO.setAccessibleTaskIds(qualityAccess.visibleTasks("index",exportReqVO.getProjectId()));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DppQualityTaskDO> list = (List<DppQualityTaskDO>) dppQualityTaskService.getDppQualityTaskPage(exportReqVO).getRows();
        ExcelUtil<DppQualityTaskRespVO> util = new ExcelUtil<>(DppQualityTaskRespVO.class);
        var rows=DppQualityTaskConvert.INSTANCE.convertToRespVOList(list);rows.forEach(qualityAccess::present);util.exportExcel(response,rows,"Application Management Data");
    }

    @Operation(summary = "导入数据质量任务列表")
    @Log(title = "log.op.title.dpp.quality", businessType = BusinessType.IMPORT)
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:add')")
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<DppQualityTaskRespVO> util = new ExcelUtil<>(DppQualityTaskRespVO.class);
        List<DppQualityTaskRespVO> importExcelList = util.importExcel(file.getInputStream());
        for(var row:importExcelList){
            if(updateSupport)qualityAccess.task(row.getId(),"edit");else row.setId(null);
            var request=BeanUtils.toBean(row,DppQualityTaskSaveReqVO.class);qualityAccess.validateTask(request,updateSupport?"edit":"add");var from=new org.springframework.beans.BeanWrapperImpl(request);var to=new org.springframework.beans.BeanWrapperImpl(row);for(String field:java.util.List.of("projectId","status","cycle","taskId","taskCode","nodeId","nodeCode","systemJobId"))if(to.isWritableProperty(field))to.setPropertyValue(field,from.getPropertyValue(field));
            row.setCreatorId(updateSupport?null:getUserId());row.setCreateBy(updateSupport?null:getUsername());row.setCreateTime(updateSupport?null:DateUtil.date());
        }
        String operName = getUsername();
        String message = dppQualityTaskService.importDppQualityTask(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取数据质量任务详细信息")
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:info')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DppQualityTaskRespVO> getInfo(@PathVariable("id") Long id) {
        qualityAccess.task(id);
        DppQualityTaskRespVO dppQualityTaskDO = dppQualityTaskService.getDppQualityTaskById(id);
        qualityAccess.present(dppQualityTaskDO);return CommonResult.success(dppQualityTaskDO);
    }

    @Operation(summary = "获取数据质量任务详细信息")
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:info')")
    @GetMapping( "/getQualityTaskAsset")
    public CommonResult<DppQualityTaskRespVO> getQualityTaskAsset(DppQualityTaskAssetReqVO dppQualityTaskAssetReqVO) {
        Long task=qualityAccess.resolveAssetTask(dppQualityTaskAssetReqVO.getAssetId(),dppQualityTaskAssetReqVO.getProjectId());
        if(task==null)return CommonResult.success(null);
        dppQualityTaskAssetReqVO.setId(task);dppQualityTaskAssetReqVO.setProjectId(dppQualityTaskService.getDppQualityTaskById(task).getProjectId());
        DppQualityTaskRespVO dppQualityTaskDO = dppQualityTaskService.getQualityTaskAsset(dppQualityTaskAssetReqVO);
        qualityAccess.present(dppQualityTaskDO);return CommonResult.success(dppQualityTaskDO);
    }

    @Operation(summary = "新增数据质量任务")
    @Log(title = "log.op.title.dpp.quality", businessType = BusinessType.INSERT)
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:add')")
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DppQualityTaskSaveReqVO dppQualityTask) {
        if(dppQualityTask.getId()!=null)throw new IllegalArgumentException("新增质量任务不能指定已有标识");
        qualityAccess.validateTask(dppQualityTask,"add");
        dppQualityTask.setCreatorId(getUserId());
        dppQualityTask.setCreateBy(getNickName());
        dppQualityTask.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(dppQualityTaskService.createDppQualityTask(dppQualityTask));
    }

    @Operation(summary = "修改数据质量任务")
    @Log(title = "log.op.title.dpp.quality", businessType = BusinessType.UPDATE)
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:edit')")
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DppQualityTaskSaveReqVO dppQualityTask) {
        qualityAccess.validateTask(dppQualityTask,"edit");
        dppQualityTask.setUpdatorId(getUserId());
        dppQualityTask.setUpdateBy(getNickName());
        dppQualityTask.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(dppQualityTaskService.updateDppQualityTask(dppQualityTask));
    }

    @Operation(summary = "删除数据质量任务")
    @Log(title = "log.op.title.dpp.quality", businessType = BusinessType.DELETE)
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:remove')")
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)qualityAccess.task(id,"remove");
        return CommonResult.toAjax(dppQualityTaskService.removeDppQualityTask(Arrays.asList(ids)));
    }


    @Operation(summary = "修改数据质量任务")
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:edit')")
    @PostMapping("/updateDppQualityTaskStatus")
    public AjaxResult updateDaDiscoveryTaskStatus(@RequestBody DppQualityTaskSaveReqVO daDiscoveryTask)
    {
        qualityAccess.task(daDiscoveryTask.getId(),"edit");
        if("0".equals(daDiscoveryTask.getStatus()))qualityAccess.execute(daDiscoveryTask.getId(),"schedule");
        else if(!"1".equals(daDiscoveryTask.getStatus()))throw new IllegalArgumentException("任务状态无效");
        var command=new DppQualityTaskSaveReqVO();command.setId(daDiscoveryTask.getId());command.setStatus(daDiscoveryTask.getStatus());
        boolean result = dppQualityTaskService.updateDppQualityTaskStatus(command);
        return result ? success() : error(MessageUtils.messageWithFallback(
                "dpp.error.task.notfound.expired", "The task does not exist or has expired"));
    }

    @Log(title = "log.op.title.dpp.task.trigger", businessType = BusinessType.UPDATE)
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:once')")
    @PutMapping("/startDppQualityTask/{id}")
    public AjaxResult startDaDiscoveryTask(@PathVariable("id") Long id)
    {
        qualityAccess.execute(id,"once");
        return dppQualityTaskService.startDppQualityTask(id);
    }


    @Log(title = "log.op.title.dpp.quality.status", businessType = BusinessType.UPDATE)
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityTask:schedule')")
    @PostMapping("/updateDaDiscoveryTaskCronExpression")
    public AjaxResult updateDaDiscoveryTaskCronExpression(@RequestBody DppQualityTaskSaveReqVO daDiscoveryTask)
    {
        qualityAccess.task(daDiscoveryTask.getId(),"schedule");
        var command=new DppQualityTaskSaveReqVO();command.setId(daDiscoveryTask.getId());command.setCycle(daDiscoveryTask.getCycle());
        boolean result = dppQualityTaskService.updateDaDiscoveryTaskCronExpression(command);
        return result ? success() : error(MessageUtils.messageWithFallback(
                "dpp.error.task.notfound.expired", "The task does not exist or has expired"));
    }

}
