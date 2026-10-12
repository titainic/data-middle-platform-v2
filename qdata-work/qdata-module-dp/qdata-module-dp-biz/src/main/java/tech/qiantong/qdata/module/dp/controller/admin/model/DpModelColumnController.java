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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.page.PageParam;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelColumnPageReqVO;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelColumnRespVO;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelColumnSaveReqVO;
import tech.qiantong.qdata.module.dp.convert.model.DpModelColumnConvert;
import tech.qiantong.qdata.module.dp.dal.dataobject.model.DpModelColumnDO;
import tech.qiantong.qdata.module.dp.service.model.IDpModelColumnService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;

/**
 * Logical Model Column Controller
 *
 * @author qdata
 * @date 2025-01-21
 */
@Tag(name = "逻辑模型属性信息")
@RestController
@RequestMapping("/dp/modelColumn")
@Validated
public class DpModelColumnController extends BaseController {
    @Resource
    private IDpModelColumnService dpModelColumnService;
    @Resource private tech.qiantong.qdata.module.dp.service.model.ModelResourceAccess resources;

    @Operation(summary = "查询逻辑模型属性信息列表")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DpModelColumnRespVO>> list(DpModelColumnPageReqVO dpModelColumn) {
        if(dpModelColumn.getModelId()!=null)resources.model(dpModelColumn.getModelId(),"columnQuery");
        dpModelColumn.setAccessibleModelIds(resources.modelIds("columnQuery",null,false));
        PageResult<DpModelColumnDO> page = dpModelColumnService.getDpModelColumnPage(dpModelColumn);
        return CommonResult.success(BeanUtils.toBean(page, DpModelColumnRespVO.class));
    }

    @Operation(summary = "查询逻辑模型属性信息列表")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:list')")
    @GetMapping("/getDpModelColumnList")
    public AjaxResult getDpModelColumnList(DpModelColumnSaveReqVO dpModelColumn) {
        if(dpModelColumn.getModelId()!=null)resources.model(dpModelColumn.getModelId(),"columnQuery");
        dpModelColumn.setAccessibleModelIds(resources.modelIds("columnQuery",null,false));
        List<DpModelColumnDO> dpModelColumnList = dpModelColumnService.getDpModelColumnList(dpModelColumn);
        return AjaxResult.success(dpModelColumnList);
    }

    @Operation(summary = "导出逻辑模型属性信息列表")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:export')")
    @Log(title = "log.op.title.dp.model.column", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DpModelColumnPageReqVO exportReqVO) {
        if(exportReqVO.getModelId()!=null)resources.model(exportReqVO.getModelId(),"columnExport");
        exportReqVO.setAccessibleModelIds(resources.modelIds("columnExport",null,false));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DpModelColumnDO> list = (List<DpModelColumnDO>) dpModelColumnService.getDpModelColumnPage(exportReqVO).getRows();
        ExcelUtil<DpModelColumnRespVO> util = new ExcelUtil<>(DpModelColumnRespVO.class);
        util.exportExcel(response, DpModelColumnConvert.INSTANCE.convertToRespVOList(list), "Application Management Data");
    }

    @Operation(summary = "导入逻辑模型属性信息列表")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:import')")
    @Log(title = "log.op.title.dp.model.column", businessType = BusinessType.IMPORT)
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport, @RequestParam(required=false) Long modelId) throws Exception {
        ExcelUtil<DpModelColumnRespVO> util = new ExcelUtil<>(DpModelColumnRespVO.class);
        List<DpModelColumnRespVO> importExcelList = util.importExcel(file.getInputStream());
        if(importExcelList.isEmpty())throw new IllegalArgumentException("导入字段不能为空");
        for(var row:importExcelList){
            if(!updateSupport)row.setId(null);else resources.column(row.getId(),"columnEdit");
            var request=BeanUtils.toBean(row,DpModelColumnSaveReqVO.class);if(modelId!=null)request.setModelId(modelId);resources.columnRequest(request,"columnImport");row.setModelId(request.getModelId());
            row.setCreateBy(updateSupport?null:getUsername());row.setCreatorId(updateSupport?null:getUserId());row.setCreateTime(updateSupport?null:DateUtil.date());row.setUpdateBy(getUsername());row.setUpdaterId(getUserId());row.setUpdateTime(DateUtil.date());row.setDelFlag(updateSupport?null:false);row.setValidFlag(updateSupport?null:true);
        }
        String operName = getUsername();
        String message = dpModelColumnService.importDpModelColumn(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取逻辑模型属性信息详细信息")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DpModelColumnRespVO> getInfo(@PathVariable("id") Long id) {
        resources.column(id,"columnQuery");
        DpModelColumnDO dpModelColumnDO = dpModelColumnService.getDpModelColumnById(id);
        return CommonResult.success(BeanUtils.toBean(dpModelColumnDO, DpModelColumnRespVO.class));
    }

    @Operation(summary = "新增逻辑模型属性信息")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:add')")
    @Log(title = "log.op.title.dp.model.column", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DpModelColumnSaveReqVO dpModelColumn) {
        if(dpModelColumn.getId()!=null)throw new IllegalArgumentException("新增字段不能携带已有标识");
        resources.columnRequest(dpModelColumn,"columnAdd");
        dpModelColumn.setCreatorId(getUserId());
        dpModelColumn.setCreateBy(getNickName());
        dpModelColumn.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(dpModelColumnService.createDpModelColumn(dpModelColumn));
    }

    @Operation(summary = "修改逻辑模型属性信息")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:edit')")
    @Log(title = "log.op.title.dp.model.column", businessType = BusinessType.UPDATE)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DpModelColumnSaveReqVO dpModelColumn) {
        if(dpModelColumn.getId()==null)throw new IllegalArgumentException("修改字段需要标识");
        resources.columnRequest(dpModelColumn,"columnEdit");
        dpModelColumn.setUpdatorId(getUserId());
        dpModelColumn.setUpdateBy(getNickName());
        dpModelColumn.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(dpModelColumnService.updateDpModelColumn(dpModelColumn));
    }

    @Operation(summary = "批量新增逻辑模型属性信息")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:add')")
    @Log(title = "log.op.title.dp.model.column", businessType = BusinessType.INSERT)
    @org.springframework.transaction.annotation.Transactional
    @PostMapping(value = "/addList")
    public CommonResult<Boolean> addList(@Valid @RequestBody List<DpModelColumnSaveReqVO> dpModelColumnList) {
        resources.columnBatch(dpModelColumnList,false);
        for (DpModelColumnSaveReqVO dpModelColumnSaveReqVO : dpModelColumnList) {
            dpModelColumnSaveReqVO.setCreatorId(getUserId());
            dpModelColumnSaveReqVO.setCreateBy(getNickName());
            dpModelColumnSaveReqVO.setCreateTime(DateUtil.date());
        }
        return CommonResult.toAjax(dpModelColumnService.createDpModelColumnList(dpModelColumnList));
    }

    @Operation(summary = "批量修改逻辑模型属性信息")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:edit')")
    @Log(title = "log.op.title.dp.model.column", businessType = BusinessType.UPDATE)
    @org.springframework.transaction.annotation.Transactional
    @PutMapping(value = "/editList")
    public CommonResult<Boolean> editList(@Valid @RequestBody List<DpModelColumnSaveReqVO> dpModelColumnList) {
        resources.columnBatch(dpModelColumnList,true);
        for (DpModelColumnSaveReqVO dpModelColumnSaveReqVO : dpModelColumnList) {
            if(dpModelColumnSaveReqVO.getId()==null){dpModelColumnSaveReqVO.setCreatorId(getUserId());dpModelColumnSaveReqVO.setCreateBy(getUsername());dpModelColumnSaveReqVO.setCreateTime(DateUtil.date());}
            else {dpModelColumnSaveReqVO.setCreatorId(null);dpModelColumnSaveReqVO.setCreateBy(null);dpModelColumnSaveReqVO.setCreateTime(null);}
            dpModelColumnSaveReqVO.setUpdatorId(getUserId());dpModelColumnSaveReqVO.setUpdateBy(getUsername());dpModelColumnSaveReqVO.setUpdateTime(DateUtil.date());
        }
        return CommonResult.toAjax(dpModelColumnService.updateDpModelColumnList(dpModelColumnList));
    }

    @Operation(summary = "删除逻辑模型属性信息")
//    @PreAuthorize("@ss.hasPermi('dp:modelColumn:remove')")
    @Log(title = "log.op.title.dp.model.column", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)resources.column(id,"columnRemove");
        return CommonResult.toAjax(dpModelColumnService.removeDpModelColumn(Arrays.asList(ids)));
    }

}
