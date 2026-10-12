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
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpMaterializedMethodReqVO;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelMaterializedPageReqVO;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelMaterializedRespVO;
import tech.qiantong.qdata.module.dp.controller.admin.model.vo.DpModelMaterializedSaveReqVO;
import tech.qiantong.qdata.module.dp.dal.dataobject.model.DpModelMaterializedDO;
import tech.qiantong.qdata.module.dp.service.model.IDpModelMaterializedService;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.Arrays;

/**
 * Materialized Model Record Controller
 *
 * @author qdata
 * @date 2025-01-21
 */
@Tag(name = "物化模型记录")
@RestController
@RequestMapping("/dp/modelMaterialized")
@Validated
public class DpModelMaterializedController extends BaseController {
    @Resource
    private IDpModelMaterializedService dpModelMaterializedService;
    @Resource private tech.qiantong.qdata.module.dp.service.model.ModelResourceAccess resources;
    @Resource private tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService sources;
    @Resource private tech.qiantong.qdata.module.da.api.service.DataResourceAccess dataAccess;
    @Resource private tech.qiantong.qdata.module.dp.service.model.IDpModelService models;
    private void present(DpModelMaterializedRespVO row){if(row!=null){row.setMessage(tech.qiantong.qdata.common.utils.SecretRedactor.redact(row.getMessage()));row.setSqlCommand(tech.qiantong.qdata.common.utils.SecretRedactor.redact(row.getSqlCommand()));}}


    @Operation(summary = "查询物化模型记录列表")
//    @PreAuthorize("@ss.hasPermi('dp:modelMaterialized:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DpModelMaterializedRespVO>> list(DpModelMaterializedPageReqVO dpModelMaterialized) {
        if(dpModelMaterialized.getModelId()!=null)resources.model(dpModelMaterialized.getModelId(),"materializationRead",true);
        dpModelMaterialized.setAccessibleModelIds(resources.modelIds("materializationRead",null,true));
        PageResult<DpModelMaterializedDO> page = dpModelMaterializedService.getDpModelMaterializedPage(dpModelMaterialized);
        PageResult<DpModelMaterializedRespVO> result=BeanUtils.toBean(page,DpModelMaterializedRespVO.class);result.getRows().forEach(this::present);return CommonResult.success(result);
    }

    @Operation(summary = "获取物化模型记录详细信息")
//    @PreAuthorize("@ss.hasPermi('dp:modelMaterialized:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DpModelMaterializedRespVO> getInfo(@PathVariable("id") Long id) {
        resources.materialization(id,"materializationRead");DpModelMaterializedDO dpModelMaterializedDO = dpModelMaterializedService.getDpModelMaterializedById(id);
        var result=BeanUtils.toBean(dpModelMaterializedDO,DpModelMaterializedRespVO.class);present(result);return CommonResult.success(result);
    }

    @Operation(summary = "新增物化模型记录")
//    @PreAuthorize("@ss.hasPermi('dp:modelMaterialized:add')")
    @Log(title = "log.op.title.dp.model.materialized", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DpModelMaterializedSaveReqVO dpModelMaterialized) {
        throw new org.springframework.security.access.AccessDeniedException("物化记录由真实发布过程生成并只读保留，不支持手工增删改");
    }

    @Operation(summary = "新增物化模型记录")
//    @PreAuthorize("@ss.hasPermi('dp:modelMaterialized:add')")
    @Log(title = "log.op.title.dp.model.materialized", businessType = BusinessType.INSERT, isSaveRequestData=false, isSaveResponseData=false)
    @PostMapping("/createMaterializedTable")
    public CommonResult<Long> createMaterializedTable(@Valid @RequestBody DpMaterializedMethodReqVO dpModelMaterialized) {
        if(dpModelMaterialized.getModelId()==null || dpModelMaterialized.getModelId().isEmpty() || new java.util.HashSet<>(dpModelMaterialized.getModelId()).size()!=dpModelMaterialized.getModelId().size())throw new IllegalArgumentException("请选择不重复的模型");
        if(!java.util.Set.of("1","2").contains(java.util.Objects.toString(dpModelMaterialized.getReleaseMode(),"")))throw new IllegalArgumentException("物化方式无效");
        for(Long model:dpModelMaterialized.getModelId())resources.targetDatasource(model,dpModelMaterialized.getDatasourceId());
        var source=sources.getDatasourceById(dpModelMaterialized.getDatasourceId());if(source==null)throw new org.springframework.security.access.AccessDeniedException("数据连接不存在");
        if("Hive".equalsIgnoreCase(source.getDatasourceType()))throw new org.springframework.security.access.AccessDeniedException("Hive 模型只能通过五层数仓版本流程物化，历史模型保留只读");
        var property=new tech.qiantong.qdata.common.database.constants.DbQueryProperty(source.getDatasourceType(),source.getIp(),source.getPort(),source.getDatasourceConfig());
        for(Long model:dpModelMaterialized.getModelId()){
            String table=models.getDpModelById(model).getTableName();dataAccess.requireTable(source.getId(),table);
        }
        dpModelMaterialized.setDatasourceName(source.getDatasourceName());dpModelMaterialized.setDatasourceType(source.getDatasourceType());dpModelMaterialized.setIp(source.getIp());dpModelMaterialized.setPort(source.getPort());dpModelMaterialized.setDatasourceConfig(source.getDatasourceConfig());dpModelMaterialized.setDbName(property.getDbName());

        dpModelMaterialized.setCreatorId(getUserId());
        dpModelMaterialized.setCreateBy(getNickName());
        dpModelMaterialized.setCreateTime(DateUtil.date());
        return CommonResult.success(dpModelMaterializedService.createMaterializedTable(dpModelMaterialized));
    }

    @Operation(summary = "修改物化模型记录")
//    @PreAuthorize("@ss.hasPermi('dp:modelMaterialized:edit')")
    @Log(title = "log.op.title.dp.model.materialized", businessType = BusinessType.UPDATE)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DpModelMaterializedSaveReqVO dpModelMaterialized) {
        throw new org.springframework.security.access.AccessDeniedException("物化记录由真实发布过程生成并只读保留，不支持手工增删改");
    }

    @Operation(summary = "删除物化模型记录")
//    @PreAuthorize("@ss.hasPermi('dp:modelMaterialized:remove')")
    @Log(title = "log.op.title.dp.model.materialized", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        throw new org.springframework.security.access.AccessDeniedException("物化记录由真实发布过程生成并只读保留，不支持手工增删改");
    }

}
