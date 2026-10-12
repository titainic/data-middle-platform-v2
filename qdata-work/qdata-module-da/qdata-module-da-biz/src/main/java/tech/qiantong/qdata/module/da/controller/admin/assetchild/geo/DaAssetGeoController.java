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

package tech.qiantong.qdata.module.da.controller.admin.assetchild.geo;

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
import tech.qiantong.qdata.module.da.controller.admin.assetchild.geo.vo.DaAssetGeoPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.geo.vo.DaAssetGeoRespVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.geo.vo.DaAssetGeoSaveReqVO;
import tech.qiantong.qdata.module.da.convert.assetchild.geo.DaAssetGeoConvert;
import tech.qiantong.qdata.module.da.dal.dataobject.assetchild.geo.DaAssetGeoDO;
import tech.qiantong.qdata.module.da.service.assetchild.geo.IDaAssetGeoService;

/**
 * Data Asset - Vector Controller
 *
 * @author qdata
 * @date 2025-04-14
 */
@Tag(name = "数据资产-矢量")
@RestController
@RequestMapping("/da/assetGeo")
@Validated
public class DaAssetGeoController extends BaseController {
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetChildAccess children;
    @Resource
    private IDaAssetGeoService daAssetGeoService;

    @Operation(summary = "查询数据资产-矢量列表")
    @PreAuthorize("@ss.hasPermi('da:assetGeo:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DaAssetGeoRespVO>> list(DaAssetGeoPageReqVO daAssetGeo) {
        daAssetGeo.setAuthorizedIds(children.ids(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.GEO,"list"));
        PageResult<DaAssetGeoDO> page = daAssetGeoService.getDaAssetGeoPage(daAssetGeo);
        return CommonResult.success(BeanUtils.toBean(page, DaAssetGeoRespVO.class));
    }

    @Operation(summary = "导出数据资产-矢量列表")
    @PreAuthorize("@ss.hasPermi('da:assetGeo:export')")
    @Log(title = "log.op.title.da.asset.geo", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DaAssetGeoPageReqVO exportReqVO) {
        exportReqVO.setAuthorizedIds(children.ids(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.GEO,"export"));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DaAssetGeoDO> list = (List<DaAssetGeoDO>) daAssetGeoService.getDaAssetGeoPage(exportReqVO).getRows();
        ExcelUtil<DaAssetGeoRespVO> util = new ExcelUtil<>(DaAssetGeoRespVO.class);
        util.exportExcel(response, DaAssetGeoConvert.INSTANCE.convertToRespVOList(list), "Application Management Data");
    }

    @Operation(summary = "导入数据资产-矢量列表")
    @PreAuthorize("@ss.hasPermi('da:assetGeo:import')")
    @Log(title = "log.op.title.da.asset.geo", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<DaAssetGeoRespVO> util = new ExcelUtil<>(DaAssetGeoRespVO.class);
        List<DaAssetGeoRespVO> importExcelList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = daAssetGeoService.importDaAssetGeo(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取数据资产-矢量详细信息")
    @PreAuthorize("@ss.hasPermi('da:assetGeo:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DaAssetGeoRespVO> getInfo(@PathVariable("id") Long id) {
        children.get(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.GEO,id,"query");
        DaAssetGeoDO daAssetGeoDO = daAssetGeoService.getDaAssetGeoById(id);
        return CommonResult.success(BeanUtils.toBean(daAssetGeoDO, DaAssetGeoRespVO.class));
    }

    @Operation(summary = "新增数据资产-矢量")
    @PreAuthorize("@ss.hasPermi('da:assetGeo:add')")
    @Log(title = "log.op.title.da.asset.geo", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DaAssetGeoSaveReqVO daAssetGeo) {
        children.save(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.GEO,daAssetGeo,false);
        return CommonResult.toAjax(daAssetGeoService.createDaAssetGeo(daAssetGeo));
    }

    @Operation(summary = "修改数据资产-矢量")
    @PreAuthorize("@ss.hasPermi('da:assetGeo:edit')")
    @Log(title = "log.op.title.da.asset.geo", businessType = BusinessType.UPDATE)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DaAssetGeoSaveReqVO daAssetGeo) {
        children.save(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.GEO,daAssetGeo,true);
        return CommonResult.toAjax(daAssetGeoService.updateDaAssetGeo(daAssetGeo));
    }

    @Operation(summary = "删除数据资产-矢量")
    @PreAuthorize("@ss.hasPermi('da:assetGeo:remove')")
    @Log(title = "log.op.title.da.asset.geo", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        children.remove(tech.qiantong.qdata.module.da.service.asset.AssetChildAccess.Kind.GEO,Arrays.asList(ids));
        return CommonResult.toAjax(daAssetGeoService.removeDaAssetGeo(Arrays.asList(ids)));
    }

}
