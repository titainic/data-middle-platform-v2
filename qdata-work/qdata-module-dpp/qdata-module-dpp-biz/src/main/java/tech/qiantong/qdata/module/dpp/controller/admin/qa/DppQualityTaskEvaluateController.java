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
import java.io.IOException;
import java.util.*;

import cn.hutool.core.date.DateUtil;

import com.alibaba.fastjson2.JSONObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
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
import tech.qiantong.qdata.common.httpClient.HeaderEntity;
import tech.qiantong.qdata.common.httpClient.HttpUtils;
import tech.qiantong.qdata.common.utils.JSONUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskEvaluatePageReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskEvaluateRespVO;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.DppQualityTaskEvaluateSaveReqVO;
import tech.qiantong.qdata.module.dpp.convert.qa.DppQualityTaskEvaluateConvert;
import tech.qiantong.qdata.module.dpp.dal.dataobject.qa.DppQualityTaskEvaluateDO;
import tech.qiantong.qdata.module.dpp.service.qa.IDppQualityTaskEvaluateService;
import tech.qiantong.qdata.module.dpp.service.qa.IDppQualityTaskService;

/**
 * Data Quality Task - Evaluation Rule Controller
 *
 * @author Chaos
 * @date 2025-07-21
 */
@Tag(name = "Data Quality Task - Evaluation Rule")
@RestController
@RequestMapping("/dpp/qualityTaskEvaluate")
@Validated
@PreAuthorize("@ss.hasPermi('da:qualityTask:info')")
public class DppQualityTaskEvaluateController extends BaseController {
    @Resource
    private tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess qualityAccess;
    @Resource
    private IDppQualityTaskEvaluateService dppQualityTaskEvaluateService;
    @Resource
    private IDppQualityTaskService dppQualityTaskService;

    @Operation(summary = "查询数据质量任务-评测规则列表")
    @GetMapping("/list")
    public CommonResult<PageResult<DppQualityTaskEvaluateRespVO>> list(DppQualityTaskEvaluatePageReqVO dppQualityTaskEvaluate) {
        dppQualityTaskEvaluate.setAccessibleTaskIds(qualityAccess.visibleTasks());
        PageResult<DppQualityTaskEvaluateDO> page = dppQualityTaskEvaluateService.getDppQualityTaskEvaluatePage(dppQualityTaskEvaluate);
        return CommonResult.success(BeanUtils.toBean(page, DppQualityTaskEvaluateRespVO.class));
    }

    @Operation(summary = "导出数据质量任务-评测规则列表")
    @Log(title = "log.op.title.dpp.quality.evaluate", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DppQualityTaskEvaluatePageReqVO exportReqVO) {
        exportReqVO.setAccessibleTaskIds(qualityAccess.visibleTasks());
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DppQualityTaskEvaluateDO> list = (List<DppQualityTaskEvaluateDO>) dppQualityTaskEvaluateService.getDppQualityTaskEvaluatePage(exportReqVO).getRows();
        ExcelUtil<DppQualityTaskEvaluateRespVO> util = new ExcelUtil<>(DppQualityTaskEvaluateRespVO.class);
        util.exportExcel(response, DppQualityTaskEvaluateConvert.INSTANCE.convertToRespVOList(list), "Application Management Data");
    }

    @Operation(summary = "导入数据质量任务-评测规则列表")
    @Log(title = "log.op.title.dpp.quality.evaluate", businessType = BusinessType.IMPORT)
    @PreAuthorize("@ss.hasPermi('da:qualityTask:edit')")
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<DppQualityTaskEvaluateRespVO> util = new ExcelUtil<>(DppQualityTaskEvaluateRespVO.class);
        List<DppQualityTaskEvaluateRespVO> importExcelList = util.importExcel(file.getInputStream());
        for(var row:importExcelList){if(!updateSupport)row.setId(null);var request=BeanUtils.toBean(row,DppQualityTaskEvaluateSaveReqVO.class);qualityAccess.validateRule(request);row.setTaskId(request.getTaskId());row.setCreatorId(updateSupport?null:getUserId());row.setCreateBy(updateSupport?null:getUsername());row.setCreateTime(updateSupport?null:DateUtil.date());}
        String operName = getUsername();
        String message = dppQualityTaskEvaluateService.importDppQualityTaskEvaluate(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取数据质量任务-评测规则详细信息")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DppQualityTaskEvaluateRespVO> getInfo(@PathVariable("id") Long id) {
        qualityAccess.rule(id);
        DppQualityTaskEvaluateDO dppQualityTaskEvaluateDO = dppQualityTaskEvaluateService.getDppQualityTaskEvaluateById(id);
        return CommonResult.success(BeanUtils.toBean(dppQualityTaskEvaluateDO, DppQualityTaskEvaluateRespVO.class));
    }

    @Operation(summary = "新增数据质量任务-评测规则")
    @Log(title = "log.op.title.dpp.quality.evaluate", businessType = BusinessType.INSERT)
    @PreAuthorize("@ss.hasPermi('da:qualityTask:edit')")
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DppQualityTaskEvaluateSaveReqVO dppQualityTaskEvaluate) {
        qualityAccess.validateRule(dppQualityTaskEvaluate);
        dppQualityTaskEvaluate.setCreatorId(getUserId());
        dppQualityTaskEvaluate.setCreateBy(getNickName());
        dppQualityTaskEvaluate.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(dppQualityTaskEvaluateService.createDppQualityTaskEvaluate(dppQualityTaskEvaluate));
    }

    @Operation(summary = "修改数据质量任务-评测规则")
    @Log(title = "log.op.title.dpp.quality.evaluate", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('da:qualityTask:edit')")
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DppQualityTaskEvaluateSaveReqVO dppQualityTaskEvaluate) {
        qualityAccess.validateRule(dppQualityTaskEvaluate);
        dppQualityTaskEvaluate.setUpdatorId(getUserId());
        dppQualityTaskEvaluate.setUpdateBy(getNickName());
        dppQualityTaskEvaluate.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(dppQualityTaskEvaluateService.updateDppQualityTaskEvaluate(dppQualityTaskEvaluate));
    }

    @Operation(summary = "删除数据质量任务-评测规则")
    @Log(title = "log.op.title.dpp.quality.evaluate", businessType = BusinessType.DELETE)
    @PreAuthorize("@ss.hasPermi('da:qualityTask:edit')")
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)qualityAccess.rule(id,"edit");
        return CommonResult.toAjax(dppQualityTaskEvaluateService.removeDppQualityTaskEvaluate(Arrays.asList(ids)));
    }

    @Operation(summary = "删除数据质量任务-检验功能")
    @Log(title = "log.op.title.dpp.quality.check", businessType = BusinessType.DELETE)
    @GetMapping("/verifyInterfaceValue")
    public CommonResult<String> verifyInterfaceValue(DppQualityTaskEvaluateSaveReqVO dppQualityTaskEvaluate) {
        qualityAccess.previewRule(dppQualityTaskEvaluate);

        String meg = dppQualityTaskService.verifyInterfaceValue(dppQualityTaskEvaluate);
        return CommonResult.success(meg);
    }

    @Operation(summary = "删除数据质量任务-错误抽查功能")
    @PostMapping("/validationErrorDataSql")
    public AjaxResult validationErrorDataSql(@Valid @RequestBody DppQualityTaskEvaluateSaveReqVO dppQualityTaskEvaluate) {
        qualityAccess.previewRule(dppQualityTaskEvaluate);
        return AjaxResult.success(dppQualityTaskService.validationErrorDataSql(dppQualityTaskEvaluate));
    }

    @Operation(summary = "删除数据质量任务-成功抽查功能")
    @PostMapping("/validationValidDataSql")
    public AjaxResult validationValidDataSql(@Valid @RequestBody DppQualityTaskEvaluateSaveReqVO dppQualityTaskEvaluate) {
        qualityAccess.previewRule(dppQualityTaskEvaluate);
        return AjaxResult.success(dppQualityTaskService.validationValidDataSql(dppQualityTaskEvaluate));
    }
}
