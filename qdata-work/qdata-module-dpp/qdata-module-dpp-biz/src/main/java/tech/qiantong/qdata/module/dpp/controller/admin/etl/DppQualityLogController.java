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
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
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
import tech.qiantong.qdata.common.core.domain.ReturnT;
import tech.qiantong.qdata.common.core.page.PageParam;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppQualityLogPageReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppQualityLogRespVO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppQualityLogSaveReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.LogResult;
import tech.qiantong.qdata.module.dpp.convert.etl.DppQualityLogConvert;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppQualityLogDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppQualityLogService;

/**
 * Data Quality Log Controller
 *
 * @author qdata
 * @date 2025-07-19
 */
@Tag(name = "Data Quality Log")
@RestController
@RequestMapping("/dpp/qualityLog")
@Validated
@org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('da:qualityLog:index')")
public class DppQualityLogController extends BaseController {
    @Resource
    private tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess qualityAccess;
    @Resource
    private IDppQualityLogService dppQualityLogService;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @GetMapping("/{id}/snapshot")
    public CommonResult<?> snapshot(@PathVariable Long id) {
        qualityAccess.log(id);
        if(dppQualityLogService.getDppQualityLogById(id)==null)throw new IllegalArgumentException("质量执行记录不存在");
        var rows=jdbc.queryForList("SELECT log_id,task_id,definition_json,definition_sha256,created_at FROM dpp_quality_execution_snapshot WHERE log_id=?",id);
        return CommonResult.success(rows.isEmpty()?null:rows.get(0));
    }

    @Operation(summary = "查询数据质量日志列表")
    @GetMapping("/list")
    public CommonResult<PageResult<DppQualityLogRespVO>> list(DppQualityLogPageReqVO dppQualityLog) {
        dppQualityLog.setAccessibleTaskIds(qualityAccess.visibleLogTasks());
        PageResult<DppQualityLogDO> page = dppQualityLogService.getDppQualityLogPage(dppQualityLog);
        return CommonResult.success(BeanUtils.toBean(page, DppQualityLogRespVO.class));
    }

    @Operation(summary = "导出数据质量日志列表")
    @Log(title = "log.op.title.dpp.quality.log", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DppQualityLogPageReqVO exportReqVO) {
        exportReqVO.setAccessibleTaskIds(qualityAccess.visibleLogTasks());
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DppQualityLogDO> list = (List<DppQualityLogDO>) dppQualityLogService.getDppQualityLogPage(exportReqVO).getRows();
        ExcelUtil<DppQualityLogRespVO> util = new ExcelUtil<>(DppQualityLogRespVO.class);
        util.exportExcel(response, DppQualityLogConvert.INSTANCE.convertToRespVOList(list), "Application Management Data");
    }

    @Operation(summary = "导入数据质量日志列表")
    @Log(title = "log.op.title.dpp.quality.log", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    @org.springframework.security.access.prepost.PreAuthorize("denyAll()")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<DppQualityLogRespVO> util = new ExcelUtil<>(DppQualityLogRespVO.class);
        List<DppQualityLogRespVO> importExcelList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = dppQualityLogService.importDppQualityLog(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取数据质量日志详细信息")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DppQualityLogRespVO> getInfo(@PathVariable("id") Long id) {
        qualityAccess.log(id);
        DppQualityLogDO dppQualityLogDO = dppQualityLogService.getDppQualityLogById(id);
        return CommonResult.success(BeanUtils.toBean(dppQualityLogDO, DppQualityLogRespVO.class));
    }

    @Operation(summary = "新增数据质量日志")
    @Log(title = "log.op.title.dpp.quality.log", businessType = BusinessType.INSERT)
    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("denyAll()")
    public CommonResult<Long> add(@Valid @RequestBody DppQualityLogSaveReqVO dppQualityLog) {
        dppQualityLog.setCreatorId(getUserId());
        dppQualityLog.setCreateBy(getNickName());
        dppQualityLog.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(dppQualityLogService.createDppQualityLog(dppQualityLog));
    }

    @Operation(summary = "修改数据质量日志")
    @Log(title = "log.op.title.dpp.quality.log", businessType = BusinessType.UPDATE)
    @PutMapping
    @org.springframework.security.access.prepost.PreAuthorize("denyAll()")
    public CommonResult<Integer> edit(@Valid @RequestBody DppQualityLogSaveReqVO dppQualityLog) {
        dppQualityLog.setUpdatorId(getUserId());
        dppQualityLog.setUpdateBy(getNickName());
        dppQualityLog.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(dppQualityLogService.updateDppQualityLog(dppQualityLog));
    }

    @Operation(summary = "删除数据质量日志")
    @Log(title = "log.op.title.dpp.quality.log", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    @org.springframework.security.access.prepost.PreAuthorize("denyAll()")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        return CommonResult.toAjax(dppQualityLogService.removeDppQualityLog(Arrays.asList(ids)));
    }


    @RequestMapping(value = "/logDetailCat", method = RequestMethod.GET)
    @Operation(summary = "运行日志详情")
    public ReturnT<LogResult> logDetailCat(String handleMsg) {
        qualityAccess.logPath(handleMsg);
        // Add log audit functionality
        try {
            if(handleMsg==null || jdbc.queryForObject("SELECT COUNT(*) FROM dpp_quality_log WHERE path=? AND del_flag='0'",Long.class,handleMsg)==0)
                throw new IllegalArgumentException("只能查看已登记的质量运行日志");
            var registered=java.nio.file.Path.of(handleMsg);
            if(!java.nio.file.Files.isRegularFile(registered) || java.nio.file.Files.size(registered)>16L*1024*1024)
                throw new IllegalArgumentException("日志不存在或超出在线查看大小");
            InputStream in = new FileInputStream(handleMsg);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            String logContent = new String(bos.toByteArray(), "UTF-8");
            if (bos != null) {
                bos.close();
            }
            if (in != null) {
                in.close();
            }
            // @TODO View log
            ReturnT<LogResult> returnT = new ReturnT<>(ReturnT.SUCCESS_CODE,
                    MessageUtils.messageWithFallback("dpp.log.query.success", "Log query succeeded"));
            LogResult logResult = new LogResult(0, 0, tech.qiantong.qdata.common.utils.SecretRedactor.redact(logContent), true);
            returnT.setContent(logResult);
            return returnT;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return new ReturnT<>(ReturnT.FAIL_CODE, MessageUtils.messageWithFallback(
                    "dpp.error.log.file.notfound", "No log file information is currently available"));
        }
    }

}
