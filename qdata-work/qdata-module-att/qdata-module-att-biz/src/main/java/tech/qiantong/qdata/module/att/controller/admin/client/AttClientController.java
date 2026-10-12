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

package tech.qiantong.qdata.module.att.controller.admin.client;

import cn.hutool.core.util.IdUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
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
import tech.qiantong.qdata.module.att.controller.admin.client.vo.AttClientPageReqVO;
import tech.qiantong.qdata.module.att.controller.admin.client.vo.AttClientRespVO;
import tech.qiantong.qdata.module.att.controller.admin.client.vo.AttClientSaveReqVO;
import tech.qiantong.qdata.module.att.convert.client.AttClientConvert;
import tech.qiantong.qdata.module.att.dal.dataobject.client.AttClientDO;
import tech.qiantong.qdata.module.att.service.client.IAttClientService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;

/**
 * App Management Controller
 *
 * @author qdata
 * @date 2025-02-18
 */
@Tag(name = "应用管理")
@RestController
@RequestMapping("/att/client")
@Validated
public class AttClientController extends BaseController {
    @Resource
    private tech.qiantong.qdata.module.att.service.client.ClientResourceAccess resources;
    @Resource
    private IAttClientService attClientService;

    @Operation(summary = "查询应用管理列表")
    @PreAuthorize("@ss.hasPermi('att:client:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<AttClientRespVO>> list(AttClientPageReqVO attClient) {
        attClient.setAccessibleClientIds(resources.clientIds("list"));
        PageResult<AttClientDO> page = attClientService.getAttClientPage(attClient);
        PageResult<AttClientRespVO> result=BeanUtils.toBean(page,AttClientRespVO.class);
        result.getRows().forEach(resources::present);
        return CommonResult.success(result);
    }

    @Operation(summary = "导出应用管理列表")
    @PreAuthorize("@ss.hasPermi('att:client:export')")
    @Log(title = "log.op.title.att.client", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AttClientPageReqVO exportReqVO) {
        exportReqVO.setAccessibleClientIds(resources.clientIds("export"));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<AttClientDO> list = (List<AttClientDO>) attClientService.getAttClientPage(exportReqVO).getRows();
        ExcelUtil<AttClientRespVO> util = new ExcelUtil<>(AttClientRespVO.class);
        var values=AttClientConvert.INSTANCE.convertToRespVOList(list);
        values.forEach(resources::present);
        util.exportExcel(response, values, "Application Management Data");
    }

    @Operation(summary = "导入应用管理列表")
    @PreAuthorize("@ss.hasPermi('att:client:import')")
    @Log(title = "log.op.title.att.client", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<AttClientRespVO> util = new ExcelUtil<>(AttClientRespVO.class);
        List<AttClientRespVO> importExcelList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = attClientService.importAttClient(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取应用管理详细信息")
    @PreAuthorize("@ss.hasPermi('att:client:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<AttClientRespVO> getInfo(@PathVariable("id") Long id) {
        resources.client(id,"query");
        AttClientDO attClientDO = attClientService.getAttClientById(id);
        return CommonResult.success(resources.present(BeanUtils.toBean(attClientDO, AttClientRespVO.class)));
    }

    @Operation(summary = "新增应用管理")
    @PreAuthorize("@ss.hasPermi('att:client:add')")
    @Log(title = "log.op.title.att.client", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody AttClientSaveReqVO attClient) {
        return CommonResult.toAjax(attClientService.createAttClient(attClient));
    }

    @Operation(summary = "修改应用管理")
    @PreAuthorize("@ss.hasPermi('att:client:edit')")
    @Log(title = "log.op.title.att.client", businessType = BusinessType.UPDATE)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody AttClientSaveReqVO attClient) {
        return CommonResult.toAjax(attClientService.updateAttClient(attClient));
    }

    @Operation(summary = "删除应用管理")
    @PreAuthorize("@ss.hasPermi('att:client:remove')")
    @Log(title = "log.op.title.att.client", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        return CommonResult.toAjax(attClientService.removeAttClient(Arrays.asList(ids)));
    }

    @Operation(summary = "重置应用秘钥")
    @PreAuthorize("@ss.hasPermi('att:client:edit')")
    @Log(title = "log.op.title.att.client.reset.key", businessType = BusinessType.UPDATE, isSaveResponseData = false)
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    @PostMapping("/reset/secret")
    public CommonResult<String> resetSecret(Long id) {
        resources.client(id,"edit");
        AttClientDO client = attClientService.getAttClientById(id);
        client.setSecret(IdUtil.fastSimpleUUID());
        attClientService.updateById(client);
        return CommonResult.success(client.getSecret());
    }
    @PreAuthorize("@ss.hasPermi('att:client:edit')")
    @Log(title="应用密钥查看",businessType=BusinessType.OTHER,isSaveResponseData=false)
    @PostMapping("/secret")
    public CommonResult<String> secret(@RequestParam Long id, HttpServletResponse response) {
        resources.client(id,"edit");response.setHeader("Cache-Control","no-store");
        return CommonResult.success(attClientService.getAttClientById(id).getSecret());
    }
    @PreAuthorize("@ss.hasAnyPermi('att:client:list,att:client:query,att:client:add,att:client:edit')")
    @GetMapping("/projects")
    public CommonResult<java.util.List<java.util.Map<String,Object>>> projects(@RequestParam(defaultValue="false") boolean editable) {
        return CommonResult.success(resources.projectOptions(editable));
    }
    @PreAuthorize("@ss.hasPermi('att:client:edit')")
    @GetMapping("/grantOptions")
    public CommonResult<java.util.List<java.util.Map<String,Object>>> grantOptions(@RequestParam Long clientId) {
        return CommonResult.success(resources.grantOptions(clientId));
    }

}
