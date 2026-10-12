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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.*;
import java.util.Arrays;

import cn.hutool.core.date.DateUtil;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
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
import tech.qiantong.qdata.module.dpp.api.etl.dto.DppEtlTaskInstanceLogStatusRespDTO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.*;
import tech.qiantong.qdata.module.dpp.convert.etl.DppEtlTaskInstanceConvert;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskInstanceDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskInstanceService;

/**
 * Data Integration Task Instance Controller
 *
 * @author qdata
 * @date 2025-02-13
 */
@Tag(name = "Data Integration Task Instance")
@RestController
@RequestMapping("/dpp/etlTaskInstance")
@Validated
public class DppEtlTaskInstanceController extends BaseController {
    @Resource
    private IDppEtlTaskInstanceService dppEtlTaskInstanceService;
    @Resource private tech.qiantong.qdata.module.dpp.service.etl.DevelopmentResourceAccess resources;
    private void scope(DppEtlTaskInstancePageReqVO request){resources.readType(request.getTaskType());request.setAccessibleTaskIds(resources.taskIds());}
    private List<DppEtlTaskInstanceTreeListRespVO> visibleTree(List<DppEtlTaskInstanceTreeListRespVO> rows){
        if(rows==null)return java.util.List.of();var visible=new java.util.ArrayList<DppEtlTaskInstanceTreeListRespVO>();
        for(var row:rows){if(row.getDataType()==null)continue;try{if(java.util.Set.of("1","5").contains(row.getDataType()))resources.instance(row.getId());else if(java.util.Set.of("2","3").contains(row.getDataType()))resources.nodeInstance(row.getId());else continue;row.setChildren(visibleTree(row.getChildren()));visible.add(row);}catch(org.springframework.security.access.AccessDeniedException denied){/* Never expose a foreign subtask through an owned parent. */}}
        return visible;
    }

    @Operation(summary = "查询数据集成任务实例列表")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTaskInstance:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DppEtlTaskInstanceRespVO>> list(DppEtlTaskInstancePageReqVO dppEtlTaskInstance) {
        if (StringUtils.isBlank(dppEtlTaskInstance.getTaskType())) {
            dppEtlTaskInstance.setTaskType("1");// Default offline data integration
        }
        scope(dppEtlTaskInstance);
        PageResult<DppEtlTaskInstanceDO> page = dppEtlTaskInstanceService.getDppEtlTaskInstancePage(dppEtlTaskInstance);
        return CommonResult.success(BeanUtils.toBean(page, DppEtlTaskInstanceRespVO.class));
    }

    @Operation(summary = "导出数据集成任务实例列表")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTaskInstance:export')")
    @Log(title = "log.op.title.dpp.task.instance", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DppEtlTaskInstancePageReqVO exportReqVO) {
        scope(exportReqVO);
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DppEtlTaskInstanceDO> list = (List<DppEtlTaskInstanceDO>) dppEtlTaskInstanceService.getDppEtlTaskInstancePage(exportReqVO).getRows();
        ExcelUtil<DppEtlTaskInstanceRespVO> util = new ExcelUtil<>(DppEtlTaskInstanceRespVO.class);
        util.exportExcel(response, DppEtlTaskInstanceConvert.INSTANCE.convertToRespVOList(list), "Application Management Data");
    }

    @Operation(summary = "导入数据集成任务实例列表")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTaskInstance:import')")
    @Log(title = "log.op.title.dpp.task.instance", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    @PreAuthorize("@ss.hasPermi('dpp:etl:etlnodeinstance:import')")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        resources.requireVersionedTaskWrite();
        ExcelUtil<DppEtlTaskInstanceRespVO> util = new ExcelUtil<>(DppEtlTaskInstanceRespVO.class);
        List<DppEtlTaskInstanceRespVO> importExcelList = util.importExcel(file.getInputStream());
        for(var row:importExcelList){resources.task(row.getTaskId(),"edit");if(row.getId()!=null)resources.instance(row.getId(),"edit");}
        String operName = getUsername();
        String message = dppEtlTaskInstanceService.importDppEtlTaskInstance(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取数据集成任务实例详细信息")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTaskInstance:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DppEtlTaskInstanceRespVO> getInfo(@PathVariable("id") Long id) {
        resources.instance(id);
        DppEtlTaskInstanceDO dppEtlTaskInstanceDO = dppEtlTaskInstanceService.getDppEtlTaskInstanceById(id);
        return CommonResult.success(BeanUtils.toBean(dppEtlTaskInstanceDO, DppEtlTaskInstanceRespVO.class));
    }

    @Operation(summary = "新增数据集成任务实例")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTaskInstance:add')")
    @Log(title = "log.op.title.dpp.task.instance", businessType = BusinessType.INSERT)
    @PostMapping
    @PreAuthorize("@ss.hasPermi('dpp:etl:etlnodeinstance:add')")
    public CommonResult<Long> add(@Valid @RequestBody DppEtlTaskInstanceSaveReqVO dppEtlTaskInstance) {
        resources.requireVersionedTaskWrite();
        resources.instanceRequest(dppEtlTaskInstance,false);
        if(dppEtlTaskInstance.getId()!=null)throw new org.springframework.security.access.AccessDeniedException("新增实例不能复用指定的历史标识");
        dppEtlTaskInstance.setId(tech.qiantong.qdata.common.utils.uuid.IdUtils.generateArtificialId());
        dppEtlTaskInstance.setCreatorId(getUserId());
        dppEtlTaskInstance.setCreateBy(getNickName());
        dppEtlTaskInstance.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(dppEtlTaskInstanceService.createDppEtlTaskInstance(dppEtlTaskInstance));
    }

    @Operation(summary = "修改数据集成任务实例")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTaskInstance:edit')")
    @Log(title = "log.op.title.dpp.task.instance", businessType = BusinessType.UPDATE)
    @PutMapping
    @PreAuthorize("@ss.hasPermi('dpp:etl:etlnodeinstance:edit')")
    public CommonResult<Integer> edit(@Valid @RequestBody DppEtlTaskInstanceSaveReqVO dppEtlTaskInstance) {
        resources.requireVersionedTaskWrite();
        resources.instanceRequest(dppEtlTaskInstance,true);
        dppEtlTaskInstance.setUpdatorId(getUserId());
        dppEtlTaskInstance.setUpdateBy(getNickName());
        dppEtlTaskInstance.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(dppEtlTaskInstanceService.updateDppEtlTaskInstance(dppEtlTaskInstance));
    }

    @Operation(summary = "删除数据集成任务实例")
//    @PreAuthorize("@ss.hasPermi('dpp:etlTaskInstance:remove')")
    @Log(title = "log.op.title.dpp.task.instance", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    @PreAuthorize("@ss.hasPermi('dpp:etl:etlnodeinstance:remove')")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        resources.requireVersionedTaskWrite();
        for(Long id:ids)resources.instance(id,"remove");
        return CommonResult.toAjax(dppEtlTaskInstanceService.removeDppEtlTaskInstance(Arrays.asList(ids)));
    }

    @Operation(summary = "获取数据集成任务实例列表")
    @GetMapping("/treeList")
    public CommonResult<PageResult<DppEtlTaskInstanceTreeListRespVO>> treeList(DppEtlTaskInstanceTreeListReqVO dppEtlTaskInstance) {
        resources.readType("4");dppEtlTaskInstance.setAccessibleTaskIds(resources.taskIds());
        var page=dppEtlTaskInstanceService.treeList(dppEtlTaskInstance);page.setRows(visibleTree(page.getRows()));return CommonResult.success(page);
    }

    @Operation(summary = "获取子任务列表")
    @GetMapping("/subNodeList")
    public CommonResult<List<DppEtlTaskInstanceTreeListRespVO>> subNodelist(@RequestParam Long taskInstanceId, @RequestParam Long nodeInstanceId) {
        resources.nodeInInstance(taskInstanceId,nodeInstanceId);
        return CommonResult.success(visibleTree(dppEtlTaskInstanceService.subNodelist(taskInstanceId, nodeInstanceId)));
    }

    @Operation(summary = "获取正在运行的实例")
    @GetMapping("/getRunTaskInstance")
    public CommonResult<Long> getRunTaskInstance(@RequestParam Long taskId) {
        resources.task(taskId);
        return CommonResult.success(dppEtlTaskInstanceService.getRunTaskInstance(taskId));
    }

    @Operation(summary = "通过实例id获取日志")
    @GetMapping("/getLogByTaskInstanceId")
    public CommonResult<DppEtlTaskInstanceLogDetailRespVO> getLogByTaskInstanceId(@RequestParam Long taskInstanceId) {
        resources.instance(taskInstanceId);
        return CommonResult.success(dppEtlTaskInstanceService.getLogDetailByTaskInstanceId(taskInstanceId));
    }

    @RequestMapping(value = "/downloadLog", method = RequestMethod.POST)
    @Operation(summary = "下载日志文件")
    public void downloadLog(HttpServletResponse response, Long taskInstanceId, String name) {
        resources.instance(taskInstanceId);
        try {
            // Get file path
            DppEtlTaskInstanceLogStatusRespDTO dto = dppEtlTaskInstanceService.getLogByTaskInstanceId(taskInstanceId);
            // If file exists
            // Set response content type to file download
            response.setContentType("application/octet-stream");
            // Set download filename
            response.setHeader("Content-Disposition", "attachment;filename=" + name + ".log");

            // Create file input stream
            try (InputStream in = new ByteArrayInputStream(dto.getLog().getBytes("UTF-8"));
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                // Write file content to output stream
                while ((length = in.read(buffer)) != -1) {
                    out.write(buffer, 0, length);
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write(MessageUtils.messageWithFallback(
                        "dpp.error.file.download.fail", "File download failed: {0}", e.getMessage()));
            } catch (IOException ioException) {
                logger.error("Failed to write error information", ioException);
            }
        }
    }

    @Operation(summary = "根据任务实例id获取数据集成任务详细信息")
    @GetMapping(value = "/getTaskInfo/{id}")
    public CommonResult<DppEtlTaskUpdateQueryRespVO> getTaskInfo(@PathVariable("id") Long id) {
        resources.instance(id);
        return CommonResult.success(dppEtlTaskInstanceService.getTaskInfo(id));
    }

    @Operation(summary = "Get data integration task instance statistics")
    @GetMapping("/statistics")
    public CommonResult<DppEtlTaskInstanceStatisticsRespVO> statistics(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false, defaultValue = "1") String taskType) {
        resources.readType(taskType);
        if(taskId!=null){var parent=resources.task(taskId);Long owner=((Number)parent.get("project_id")).longValue();if(projectId!=null && !projectId.equals(owner))throw new org.springframework.security.access.AccessDeniedException("任务与项目不匹配");projectId=owner;}
        projectId=resources.resolveProject(projectId,projectCode);
        return CommonResult.success(
                dppEtlTaskInstanceService.getStatistics(projectId, projectCode, taskId, taskType));
    }

}
