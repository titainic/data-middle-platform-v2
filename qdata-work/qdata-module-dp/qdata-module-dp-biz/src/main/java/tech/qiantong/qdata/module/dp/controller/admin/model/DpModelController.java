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

package tech.qiantong.qdata.module.dp.controller.admin.model;

import cn.hutool.core.date.DateUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.domain.BatchDeleteCheck;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.domain.TreeData;
import tech.qiantong.qdata.common.core.page.PageParam;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelPageReqVO;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelRespVO;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelSaveReqVO;
import tech.qiantong.qdata.module.dp.convert.model.DpModelConvert;
import tech.qiantong.qdata.module.dp.dal.dataobject.model.DpModelDO;
import tech.qiantong.qdata.module.dp.service.model.IDpModelService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;

/**
 * Logical Model Controller
 *
 * @author qdata
 * @date 2025-01-21
 */
@Tag(name = "逻辑模型")
@RestController
@RequestMapping("/dp/model")
@Validated
public class DpModelController extends BaseController {
    @Resource
    private IDpModelService dpModelService;
    @Resource private tech.qiantong.qdata.module.dp.service.model.ModelResourceAccess resources;

    @GetMapping("/projects")
    @PreAuthorize("@ss.hasPermi('dp:model:list')")
    public CommonResult<?> projects(){return CommonResult.success(resources.projectOptions());}
    @GetMapping("/datasources")
    @PreAuthorize("@ss.hasPermi('dp:model:add') or @ss.hasPermi('dp:model:edit')")
    public CommonResult<?> datasources(@RequestParam Long projectId){return CommonResult.success(resources.datasourceOptions(projectId));}
    @GetMapping("/materializationDatasources")
    @PreAuthorize("@ss.hasPermi('dp:model:edit') and @ss.hasPermi('dp:releaseModel:list')")
    public CommonResult<?> materializationDatasources(@RequestParam java.util.List<Long> modelIds){return CommonResult.success(resources.materializationSources(modelIds));}
    private void present(java.util.List<DpModelRespVO> rows){
        var visible=resources.presentations(rows.stream().map(DpModelRespVO::getId).toList());
        for(var row:rows){var value=visible.get(row.getId());if(value==null)throw new org.springframework.security.access.AccessDeniedException("模型已不再可访问");row.setProjectId(value.projectId());row.setProjectName(value.projectName());row.setManagedWarehouse(value.managed());row.setCapabilities(value.capabilities());row.setDatasourceConfig(null);}
    }


    @Operation(summary = "查询逻辑模型列表")
    @PreAuthorize("@ss.hasPermi('dp:model:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DpModelRespVO>> list(DpModelPageReqVO dpModel) {
        dpModel.setAccessibleModelIds(resources.modelIds("list",dpModel.getProjectId(),false));
        PageResult<DpModelDO> page = dpModelService.getDpModelPage(dpModel);
        PageResult<DpModelRespVO> result=BeanUtils.toBean(page,DpModelRespVO.class);present(result.getRows());return CommonResult.success(result);
    }

    @Operation(summary = "查询发布模型列表")
    @PreAuthorize("@ss.hasPermi('dp:releaseModel:list')")
    @GetMapping("/releaseList")
    public CommonResult<PageResult<DpModelRespVO>> releaseList(DpModelPageReqVO dpModel) {
        dpModel.setAccessibleModelIds(resources.modelIds("materializationRead",dpModel.getProjectId(),false));
        PageResult<DpModelDO> page = dpModelService.getReleaseListPage(dpModel);
        PageResult<DpModelRespVO> result=BeanUtils.toBean(page,DpModelRespVO.class);present(result.getRows());return CommonResult.success(result);
    }

    @Operation(summary = "导出逻辑模型列表")
    @PreAuthorize("@ss.hasPermi('dp:model:export')")
    @Log(title = "log.op.title.dp.model", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DpModelPageReqVO exportReqVO) {
        exportReqVO.setAccessibleModelIds(resources.modelIds("export",exportReqVO.getProjectId(),false));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DpModelDO> list = (List<DpModelDO>) dpModelService.getDpModelPage(exportReqVO).getRows();
        ExcelUtil<DpModelRespVO> util = new ExcelUtil<>(DpModelRespVO.class);
        var rows=DpModelConvert.INSTANCE.convertToRespVOList(list);present(rows);util.exportExcel(response,rows,"Application Management Data");
    }

    @Operation(summary = "导入逻辑模型列表")
    @PreAuthorize("@ss.hasPermi('dp:model:import')")
    @Log(title = "log.op.title.dp.model", businessType = BusinessType.IMPORT)
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport, @RequestParam(required=false) Long projectId) throws Exception {
        if(!updateSupport)throw new IllegalArgumentException("新建模型请在 Hive 五层建模中选择数仓空间；旧模型仅支持按标识更新导入");
        ExcelUtil<DpModelRespVO> util = new ExcelUtil<>(DpModelRespVO.class);
        List<DpModelRespVO> importExcelList = util.importExcel(file.getInputStream());
        if(importExcelList.isEmpty())throw new IllegalArgumentException("导入模型不能为空");
        for(var row:importExcelList){
            if(row.getId()==null)throw new IllegalArgumentException("旧模型更新导入必须提供已有模型标识");
            resources.editable(row.getId(),"edit");
            var request=BeanUtils.toBean(row,DpModelSaveReqVO.class);request.setDatasourceId(row.getDatasourceId()==null?null:row.getDatasourceId().toString());if(projectId!=null)request.setProjectId(projectId);
            resources.prepare(request,"import");row.setProjectId(request.getProjectId());row.setStatus(request.getStatus());
            row.setDatasourceConfig(null);row.setCreateBy(updateSupport?null:getUsername());row.setCreatorId(updateSupport?null:getUserId());row.setCreateTime(updateSupport?null:DateUtil.date());
            row.setUpdateBy(getUsername());row.setUpdaterId(getUserId());row.setUpdateTime(DateUtil.date());row.setDelFlag(updateSupport?null:false);row.setValidFlag(updateSupport?null:true);
        }
        String operName = getUsername();
        String message = dpModelService.importDpModel(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取逻辑模型详细信息")
    @PreAuthorize("@ss.hasPermi('dp:model:query')")
    @GetMapping(value = "/{ID:\\d+}")
    public CommonResult<DpModelRespVO> getInfo(@PathVariable("ID") Long ID) {
        resources.model(ID,"query");DpModelDO dpModelDO = dpModelService.getDpModelById(ID);
        var result=BeanUtils.toBean(dpModelDO,DpModelRespVO.class);present(java.util.List.of(result));return CommonResult.success(result);
    }

    @Operation(summary = "新增逻辑模型")
    @PreAuthorize("@ss.hasPermi('dp:model:add')")
    @Log(title = "log.op.title.dp.model", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DpModelSaveReqVO dpModel) {
        if(dpModel.getId()!=null)throw new IllegalArgumentException("新增模型不能携带已有标识");
        resources.prepare(dpModel,"add");
        throw new IllegalArgumentException("新建模型请在 Hive 五层建模中选择数仓空间、分层和物理数据库");
    }

    @Operation(summary = "修改逻辑模型")
    @PreAuthorize("@ss.hasPermi('dp:model:edit')")
    @Log(title = "log.op.title.dp.model", businessType = BusinessType.UPDATE)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DpModelSaveReqVO dpModel) {
        if(dpModel.getId()==null)throw new IllegalArgumentException("修改模型需要标识");
        resources.prepare(dpModel,"edit");
        dpModel.setUpdatorId(getUserId());
        dpModel.setUpdateBy(getNickName());
        dpModel.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(dpModelService.updateDpModel(dpModel));
    }

    @Operation(summary = "删除逻辑模型")
    @PreAuthorize("@ss.hasPermi('dp:model:remove')")
    @Log(title = "log.op.title.dp.model", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)resources.editable(id,"remove");
        return CommonResult.toAjax(dpModelService.removeDpModel(Arrays.asList(ids)));
    }

    @Operation(summary = "批量删除检查表元数据")
    @PreAuthorize("@ss.hasPermi('dp:model:remove')")
    @GetMapping("/batchDeleteCheck/{ids}")
    public CommonResult<BatchDeleteCheck<Long>> batchDeleteCheck(@PathVariable Long[] ids) {
        for(Long id:ids)resources.editable(id,"remove");
        BatchDeleteCheck<Long> result = dpModelService.batchDeleteCheck(Arrays.asList(ids));
        return CommonResult.success(result);
    }

    @Operation(summary = "删除逻辑模型连带字段一起删除")
    @PreAuthorize("@ss.hasPermi('dp:model:remove')")
    @Log(title = "log.op.title.dp.model", businessType = BusinessType.DELETE)
    @DeleteMapping("/columnAll/{ids}")
    public CommonResult<Integer> removeAndColumnAll(@PathVariable Long[] ids) {
        for(Long id:ids){resources.editable(id,"remove");resources.editable(id,"columnRemove");}
        return CommonResult.toAjax(dpModelService.removeDpModelAndColumnAll(Arrays.asList(ids)));
    }

    @Operation(summary = "更改状态")
    @PreAuthorize("@ss.hasPermi('dp:model:edit')")
    @Log(title = "log.op.title.dp.model.status", businessType = BusinessType.UPDATE)
    @PostMapping("/updateStatus/{id}/{status}")
    public CommonResult<Boolean> updateStatus(@PathVariable Long id, @PathVariable Long status) {
        resources.logicalStatus(id,status);
        return CommonResult.toAjax(dpModelService.updateStatus(id, status));
    }

    @Operation(summary = "获取树类目数据（多个数据组合而来）")
    @PreAuthorize("@ss.hasPermi('dp:model:list')")
    @GetMapping("/getTreeData")
    public CommonResult<List<TreeData>> getTree() {
        return CommonResult.success(dpModelService.getTreeData());
    }
}
