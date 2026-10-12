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

package tech.qiantong.qdata.module.att.controller.admin.cat;

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
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatPageReqVO;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatRespVO;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatSaveReqVO;
import tech.qiantong.qdata.module.att.convert.cat.AttTaskCatConvert;
import tech.qiantong.qdata.module.att.dal.dataobject.cat.AttTaskCatDO;
import tech.qiantong.qdata.module.att.service.cat.IAttTaskCatService;

/**
 * Data Integration Task Category Management Controller
 *
 * @author qdata
 * @date 2025-03-11
 */
@Tag(name = "数据集成任务类目管理")
@RestController
@RequestMapping("/att/taskCat")
@Validated
public class AttTaskCatController extends BaseController {
    @Resource
    private tech.qiantong.qdata.module.att.service.project.ProjectCatalogAccess catalogs;
    private static final tech.qiantong.qdata.module.att.service.project.ProjectCatalogAccess.Kind KIND=tech.qiantong.qdata.module.att.service.project.ProjectCatalogAccess.Kind.INTEGRATION;
    @Resource
    private IAttTaskCatService attTaskCatService;

    @Operation(summary = "查询数据集成任务类目管理列表")
    @GetMapping("/list")
    public CommonResult<List<AttTaskCatRespVO>> list(AttTaskCatPageReqVO attTaskCat) {
        attTaskCat.setAccessibleProjectIds(catalogs.projects(KIND,"read",attTaskCat.getProjectId(),attTaskCat.getProjectCode()));
        List<AttTaskCatDO> attTaskCatDOList = attTaskCatService.getAttTaskCatList(attTaskCat);
        return CommonResult.success(BeanUtils.toBean(attTaskCatDOList, AttTaskCatRespVO.class));
    }

    @Operation(summary = "导出数据集成任务类目管理列表")
    @PreAuthorize("@ss.hasPermi('att:taskCat:export')")
    @Log(title = "log.op.title.att.task.cat", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AttTaskCatPageReqVO exportReqVO) {
        exportReqVO.setAccessibleProjectIds(catalogs.projects(KIND,"export",exportReqVO.getProjectId(),exportReqVO.getProjectCode()));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<AttTaskCatDO> list = (List<AttTaskCatDO>) attTaskCatService.getAttTaskCatPage(exportReqVO).getRows();
        ExcelUtil<AttTaskCatRespVO> util = new ExcelUtil<>(AttTaskCatRespVO.class);
        util.exportExcel(response, AttTaskCatConvert.INSTANCE.convertToRespVOList(list), "Application Management Data");
    }

    @Operation(summary = "导入数据集成任务类目管理列表")
    @PreAuthorize("@ss.hasPermi('att:taskCat:import')")
    @Log(title = "log.op.title.att.task.cat", businessType = BusinessType.IMPORT)
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<AttTaskCatRespVO> util = new ExcelUtil<>(AttTaskCatRespVO.class);
        List<AttTaskCatRespVO> importExcelList = util.importExcel(file.getInputStream());
        for(var row:importExcelList){
            if(!updateSupport && row.getId()!=null)throw new IllegalArgumentException("新增类目不能指定历史标识");
            var owner=catalogs.prepare(KIND,row.getId(),row.getProjectId(),row.getProjectCode(),row.getParentId(),row.getCode(),row.getName(),row.getId()==null?"add":"edit");
            row.setName(row.getName().trim());row.setProjectId(owner.projectId());row.setProjectCode(owner.projectCode());row.setCode(owner.code());row.setParentId(owner.parentId());row.setDelFlag(null);row.setCreatorId(null);row.setCreateBy(null);row.setCreateTime(null);row.setUpdaterId(null);row.setUpdateBy(null);row.setUpdateTime(null);
        }
        String operName = getUsername();
        String message = attTaskCatService.importAttTaskCat(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取数据集成任务类目管理详细信息")
    @PreAuthorize("@ss.hasPermi('att:taskCat:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<AttTaskCatRespVO> getInfo(@PathVariable("id") Long id) {
        catalogs.category(KIND,id,"read");
        AttTaskCatDO attTaskCatDO = attTaskCatService.getAttTaskCatById(id);
        return CommonResult.success(BeanUtils.toBean(attTaskCatDO, AttTaskCatRespVO.class));
    }

    @Operation(summary = "检查类目及其子类目下是否存在数据集成任务")
    @PreAuthorize("@ss.hasPermi('att:taskCat:edit')")
    @GetMapping("/hasIntegrationTask/{id}")
    public CommonResult<Boolean> hasIntegrationTask(@PathVariable("id") Long id) {
        catalogs.category(KIND,id,"edit");
        return CommonResult.success(attTaskCatService.hasIntegrationTask(id));
    }

    @Operation(summary = "检查同一上级类目下名称是否已被使用")
    @PreAuthorize("@ss.hasAnyPermi('att:taskCat:add,att:taskCat:edit')")
    @GetMapping("/nameUsed")
    public CommonResult<Boolean> isNameUsed(@RequestParam(required = false) Long id,
                                            @RequestParam Long parentId,
                                            @RequestParam String name, @RequestParam(required=false) Long projectId, @RequestParam(required=false) String projectCode) {
        return CommonResult.success(catalogs.nameUsed(KIND,id,parentId,name,projectId,projectCode));
    }

    @Operation(summary = "查询类目关联的数据集成任务数量")
    @PreAuthorize("@ss.hasPermi('att:taskCat:edit')")
    @GetMapping("/integrationTaskCount/{id}")
    public CommonResult<Long> getIntegrationTaskCount(@PathVariable("id") Long id) {
        catalogs.category(KIND,id,"edit");
        return CommonResult.success(attTaskCatService.getIntegrationTaskCount(id));
    }

    @Operation(summary = "新增数据集成任务类目管理")
    @PreAuthorize("@ss.hasPermi('att:taskCat:add')")
    @Log(title = "log.op.title.att.task.cat", businessType = BusinessType.INSERT)
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody AttTaskCatSaveReqVO attTaskCat) {
        if(attTaskCat.getId()!=null)throw new IllegalArgumentException("新增类目不能指定历史标识");
        var owner=catalogs.prepare(KIND,attTaskCat.getId(),attTaskCat.getProjectId(),attTaskCat.getProjectCode(),attTaskCat.getParentId(),attTaskCat.getCode(),attTaskCat.getName(),"add");
        attTaskCat.setName(attTaskCat.getName().trim());attTaskCat.setProjectId(owner.projectId());attTaskCat.setProjectCode(owner.projectCode());attTaskCat.setCode(owner.code());attTaskCat.setParentId(owner.parentId());attTaskCat.setCreatorId(null);attTaskCat.setCreateBy(null);attTaskCat.setCreateTime(null);
        attTaskCat.setCreatorId(getUserId());
        attTaskCat.setCreateBy(getNickName());
        attTaskCat.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(attTaskCatService.createAttTaskCat(attTaskCat));
    }

    @Operation(summary = "修改数据集成任务类目管理")
    @PreAuthorize("@ss.hasPermi('att:taskCat:edit')")
    @Log(title = "log.op.title.att.task.cat", businessType = BusinessType.UPDATE)
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody AttTaskCatSaveReqVO attTaskCat) {
        var owner=catalogs.prepare(KIND,attTaskCat.getId(),attTaskCat.getProjectId(),attTaskCat.getProjectCode(),attTaskCat.getParentId(),attTaskCat.getCode(),attTaskCat.getName(),"edit");
        attTaskCat.setName(attTaskCat.getName().trim());attTaskCat.setProjectId(owner.projectId());attTaskCat.setProjectCode(owner.projectCode());attTaskCat.setCode(owner.code());attTaskCat.setParentId(owner.parentId());attTaskCat.setCreatorId(null);attTaskCat.setCreateBy(null);attTaskCat.setCreateTime(null);
        attTaskCat.setUpdatorId(getUserId());
        attTaskCat.setUpdateBy(getNickName());
        attTaskCat.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(attTaskCatService.updateAttTaskCat(attTaskCat));
    }

    @Operation(summary = "删除数据集成任务类目管理")
    @PreAuthorize("@ss.hasPermi('att:taskCat:remove')")
    @Log(title = "log.op.title.att.task.cat", businessType = BusinessType.DELETE)
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)catalogs.category(KIND,id,"remove");
        return CommonResult.toAjax(attTaskCatService.removeAttTaskCat(Arrays.asList(ids)));
    }

}
