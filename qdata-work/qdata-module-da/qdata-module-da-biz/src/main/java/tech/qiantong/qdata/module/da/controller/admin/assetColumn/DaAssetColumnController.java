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

package tech.qiantong.qdata.module.da.controller.admin.assetColumn;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import cn.hutool.core.date.DateUtil;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.common.exception.enums.GlobalErrorCodeConstants;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.DaAssetColumnPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.DaAssetColumnRespVO;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.DaAssetColumnSaveReqVO;
import tech.qiantong.qdata.module.da.convert.assetColumn.DaAssetColumnConvert;
import tech.qiantong.qdata.module.da.dal.dataobject.assetColumn.DaAssetColumnDO;
import tech.qiantong.qdata.module.da.service.assetColumn.IDaAssetColumnService;

/**
 * Data Asset Column Controller
 *
 * @author lhs
 * @date 2025-01-21
 */
@Tag(name = "数据资产字段")
@RestController
@RequestMapping("/da/assetColumn")
@Validated
public class DaAssetColumnController extends BaseController {
    @Resource
    private tech.qiantong.qdata.module.da.api.service.DataResourceAccess resourceAccess;
    @Resource
    private tech.qiantong.qdata.module.da.service.asset.AssetReferenceGuard referenceGuard;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetProjectReadAccess readAccess;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetMutationAccess mutations;
    private void scope(String id){if(id!=null && !id.isBlank())resourceAccess.requireAsset(Long.valueOf(id));}
    private void readScope(String id,String action){if(id!=null && !id.isBlank())readAccess.asset(Long.valueOf(id),"da:assetColumn:"+action);}
    @Resource
    private IDaAssetColumnService daAssetColumnService;

    @Operation(summary = "查询数据资产字段列表")
    @PreAuthorize("@ss.hasPermi('da:assetColumn:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DaAssetColumnRespVO>> list(DaAssetColumnPageReqVO daAssetColumn) {
        readScope(daAssetColumn.getAssetId(),"list");
        PageResult<DaAssetColumnDO> page = daAssetColumnService.getDaAssetColumnPage(daAssetColumn);
        var result=BeanUtils.toBean(page,DaAssetColumnRespVO.class);result.getRows().forEach(mutations::present);return CommonResult.success(result);
    }


    @Operation(summary = "根据选择的资产表id信息查询字段信息")
    @PreAuthorize("@ss.hasPermi('da:assetColumn:list')")
    @GetMapping("/getColumnByAssetId")
    public AjaxResult getColumnByAssetId(DaAssetColumnPageReqVO daAssetColumn) {
        readScope(daAssetColumn.getAssetId(),"list");
        return daAssetColumnService.getColumnByAssetId(daAssetColumn);
    }

    @Operation(summary = "导出数据资产字段列表")
    @PreAuthorize("@ss.hasPermi('da:assetColumn:export')")
    @Log(title = "log.op.title.da.asset.column", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DaAssetColumnPageReqVO exportReqVO) {
        readScope(exportReqVO.getAssetId(),"export");
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DaAssetColumnDO> list = (List<DaAssetColumnDO>) daAssetColumnService.getDaAssetColumnPage(exportReqVO).getRows();
        ExcelUtil<DaAssetColumnRespVO> util = new ExcelUtil<>(DaAssetColumnRespVO.class);
        util.exportExcel(response, DaAssetColumnConvert.INSTANCE.convertToRespVOList(list), "App Management Data");
    }

    @Operation(summary = "导入数据资产字段列表")
    @PreAuthorize("@ss.hasPermi('da:assetColumn:import')")
    @Log(title = "log.op.title.da.asset.column", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<DaAssetColumnRespVO> util = new ExcelUtil<>(DaAssetColumnRespVO.class);
        List<DaAssetColumnRespVO> importExcelList = util.importExcel(file.getInputStream());
        for(var row:importExcelList){scope(String.valueOf(row.getAssetId()));if(row.getId()!=null)resourceAccess.requireAsset(referenceGuard.columnAsset(row.getId()));else referenceGuard.requireManualColumnAddition(Long.valueOf(String.valueOf(row.getAssetId())));}
        String operName = getUsername();
        String message = daAssetColumnService.importDaAssetColumn(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取数据资产字段详细信息")
    @PreAuthorize("@ss.hasPermi('da:assetColumn:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DaAssetColumnRespVO> getInfo(@PathVariable("id") Long id) {
        readAccess.column(id,"da:assetColumn:query");
        DaAssetColumnDO daAssetColumnDO = daAssetColumnService.getDaAssetColumnById(id);
        return CommonResult.success(mutations.present(BeanUtils.toBean(daAssetColumnDO, DaAssetColumnRespVO.class)));
    }

    @Operation(summary = "新增数据资产字段")
    @PreAuthorize("@ss.hasPermi('da:assetColumn:add')")
    @Log(title = "log.op.title.da.asset.column", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DaAssetColumnSaveReqVO daAssetColumn) {
        if(daAssetColumn.getId()!=null)throw new IllegalArgumentException("新增字段不能指定已有ID");
        mutations.asset(Long.valueOf(daAssetColumn.getAssetId()),"da:assetColumn:add");
        referenceGuard.requireManualColumnAddition(Long.valueOf(daAssetColumn.getAssetId()));
        daAssetColumn.setCreatorId(getUserId());
        daAssetColumn.setCreateBy(getNickName());
        daAssetColumn.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(daAssetColumnService.createDaAssetColumn(daAssetColumn));
    }

    @Operation(summary = "修改数据资产字段")
    @PreAuthorize("@ss.hasPermi('da:assetColumn:edit')")
    @Log(title = "log.op.title.da.asset.column", businessType = BusinessType.UPDATE)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DaAssetColumnSaveReqVO daAssetColumn) {
        mutations.columnEdit(daAssetColumn);
        daAssetColumn.setUpdatorId(getUserId());
        daAssetColumn.setUpdateBy(getNickName());
        daAssetColumn.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(daAssetColumnService.updateDaAssetColumn(daAssetColumn));
    }

    @Operation(summary = "删除数据资产字段")
    @PreAuthorize("@ss.hasAnyPermi('da:assetColumn:remove,da:assetColumn:assetcolumn:remove')")
    @Log(title = "log.op.title.da.asset.column", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)mutations.column(id,"remove");
        return CommonResult.toAjax(daAssetColumnService.removeDaAssetColumn(Arrays.asList(ids)));
    }

}
