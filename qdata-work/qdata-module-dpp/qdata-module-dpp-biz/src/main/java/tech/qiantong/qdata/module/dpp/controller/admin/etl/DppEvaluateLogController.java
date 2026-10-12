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
import java.util.Arrays;
import cn.hutool.core.date.DateUtil;

import java.util.Date;
import java.util.List;

import com.alibaba.fastjson2.JSONObject;
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
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.*;
import tech.qiantong.qdata.module.dpp.controller.admin.qa.vo.CheckErrorDataReqDTO;
import tech.qiantong.qdata.module.dpp.convert.etl.DppEvaluateLogConvert;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEvaluateLogDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEvaluateLogService;

/**
 * Evaluation Rule Result Controller
 *
 * @author qdata
 * @date 2025-07-21
 */
@Tag(name = "Evaluation Rule Result")
@RestController
@RequestMapping("/dpp/evaluateLog")
@org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityLog:index')")
@Validated
public class DppEvaluateLogController extends BaseController {
    @Resource
    private tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess qualityAccess;
    @Resource
    private IDppEvaluateLogService dppEvaluateLogService;

    @Operation(summary = "查询评测规则结果列表")
//    @PreAuthorize("@ss.hasPermi('dpp:evaluateLog:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DppEvaluateLogRespVO>> list(DppEvaluateLogPageReqVO dppEvaluateLog) {
        dppEvaluateLog.setAccessibleLogIds(qualityAccess.visibleLogs());
        PageResult<DppEvaluateLogDO> page = dppEvaluateLogService.getDppEvaluateLogPage(dppEvaluateLog);
        return CommonResult.success(BeanUtils.toBean(page, DppEvaluateLogRespVO.class));
    }

    @Operation(summary = "导出评测规则结果列表")
//    @PreAuthorize("@ss.hasPermi('dpp:evaluateLog:export')")
    @Log(title = "log.op.title.dpp.evaluate.result", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DppEvaluateLogPageReqVO exportReqVO) {
        exportReqVO.setAccessibleLogIds(qualityAccess.visibleLogs());
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DppEvaluateLogDO> list = (List<DppEvaluateLogDO>) dppEvaluateLogService.getDppEvaluateLogPage(exportReqVO).getRows();
        ExcelUtil<DppEvaluateLogRespVO> util = new ExcelUtil<>(DppEvaluateLogRespVO.class);
        util.exportExcel(response, DppEvaluateLogConvert.INSTANCE.convertToRespVOList(list), "Application Management Data");
    }

    @Operation(summary = "导入评测规则结果列表")
//    @PreAuthorize("@ss.hasPermi('dpp:evaluateLog:import')")
    @Log(title = "log.op.title.dpp.evaluate.result", businessType = BusinessType.IMPORT)
    @org.springframework.security.access.prepost.PreAuthorize("denyAll()")
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<DppEvaluateLogRespVO> util = new ExcelUtil<>(DppEvaluateLogRespVO.class);
        List<DppEvaluateLogRespVO> importExcelList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = dppEvaluateLogService.importDppEvaluateLog(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取评测规则结果详细信息")
//    @PreAuthorize("@ss.hasPermi('dpp:evaluateLog:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DppEvaluateLogRespVO> getInfo(@PathVariable("id") Long id) {
        qualityAccess.report(String.valueOf(id));
        DppEvaluateLogDO dppEvaluateLogDO = dppEvaluateLogService.getDppEvaluateLogById(id);
        return CommonResult.success(BeanUtils.toBean(dppEvaluateLogDO, DppEvaluateLogRespVO.class));
    }

    @Operation(summary = "新增评测规则结果")
//    @PreAuthorize("@ss.hasPermi('dpp:evaluateLog:add')")
    @Log(title = "log.op.title.dpp.evaluate.result", businessType = BusinessType.INSERT)
    @org.springframework.security.access.prepost.PreAuthorize("denyAll()")
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DppEvaluateLogSaveReqVO dppEvaluateLog) {
        dppEvaluateLog.setCreatorId(getUserId());
        dppEvaluateLog.setCreateBy(getNickName());
        dppEvaluateLog.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(dppEvaluateLogService.createDppEvaluateLog(dppEvaluateLog));
    }

    @Operation(summary = "修改评测规则结果")
//    @PreAuthorize("@ss.hasPermi('dpp:evaluateLog:edit')")
    @Log(title = "log.op.title.dpp.evaluate.result", businessType = BusinessType.UPDATE)
    @org.springframework.security.access.prepost.PreAuthorize("denyAll()")
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DppEvaluateLogSaveReqVO dppEvaluateLog) {
        dppEvaluateLog.setUpdatorId(getUserId());
        dppEvaluateLog.setUpdateBy(getNickName());
        dppEvaluateLog.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(dppEvaluateLogService.updateDppEvaluateLog(dppEvaluateLog));
    }

    @Operation(summary = "删除评测规则结果")
//    @PreAuthorize("@ss.hasPermi('dpp:evaluateLog:remove')")
    @Log(title = "log.op.title.dpp.evaluate.result", businessType = BusinessType.DELETE)
    @org.springframework.security.access.prepost.PreAuthorize("denyAll()")
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        return CommonResult.toAjax(dppEvaluateLogService.removeDppEvaluateLog(Arrays.asList(ids)));
    }

    @Operation(summary = "统计评测规则结果")
    @GetMapping("/statisticsEvaluateOne/{id}")
    public CommonResult<List<DppEvaluateLogStatisticsVO>> statisticsEvaluateOne(@PathVariable Long id) {
        qualityAccess.log(id);
        return CommonResult.success(dppEvaluateLogService.statisticsEvaluateOne(id));
    }


    @Operation(summary = "统计评测规则结果")
    @GetMapping("/statisticsEvaluateTow")
    public CommonResult<JSONObject> statisticsEvaluateTow( Long id , Date deDate , Date oldDate , int type) {
        qualityAccess.log(id);
        return CommonResult.success(dppEvaluateLogService.statisticsEvaluateTow(id , deDate , oldDate , type));
    }

    @Operation(summary = "统计评测规则结果")
    @GetMapping("/statisticsEvaluateTable/{id}")
    public CommonResult<List<DppEvaluateLogRespVO>> statisticsEvaluateTable(@PathVariable Long id) {
        qualityAccess.log(id);
        return CommonResult.success(dppEvaluateLogService.statisticsEvaluateTable(id));
    }

    @Operation(summary = "统计评测规则结果")
    @GetMapping("/errorDataCapabilities")
    public CommonResult<?> errorDataCapabilities(@RequestParam String reportId){return CommonResult.success(qualityAccess.problemCapabilities(reportId));}

    @GetMapping("/pageErrorData")
    public CommonResult<JSONObject> pageErrorData(CheckErrorDataReqDTO checkErrorDataReqDTO) {
        qualityAccess.errorData(checkErrorDataReqDTO);
        return CommonResult.success(dppEvaluateLogService.pageErrorData(checkErrorDataReqDTO));
    }
    @Operation(summary = "修改错误数据")
    @GetMapping("/errorDataHistory")
    public CommonResult<java.util.List<java.util.Map<String,Object>>> errorDataHistory(CheckErrorDataReqDTO dto) {
        qualityAccess.errorData(dto);
        return CommonResult.success(dppEvaluateLogService.errorDataHistory(dto));
    }

    @PreAuthorize("@ss.hasPermi('da:qualityTask:edit')")
    @PostMapping("/updateErrorData")
    public CommonResult<Boolean> updateErrorData(@RequestBody CheckErrorDataReqDTO checkErrorDataReqDTO) {
        qualityAccess.errorData(checkErrorDataReqDTO,true);
        boolean success = dppEvaluateLogService.updateErrorData(checkErrorDataReqDTO);
        return CommonResult.success(success);
    }
}
