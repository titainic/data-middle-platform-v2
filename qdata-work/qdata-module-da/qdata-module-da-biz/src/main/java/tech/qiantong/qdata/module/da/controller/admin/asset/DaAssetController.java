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

package tech.qiantong.qdata.module.da.controller.admin.asset;

import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
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
import tech.qiantong.qdata.common.core.domain.TreeData;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.page.PageParam;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.core.text.Convert;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.att.api.Rel.dto.AttTagAssetRelReqDTO;
import tech.qiantong.qdata.module.att.api.Rel.dto.AttTagAssetRelRespDTO;
import tech.qiantong.qdata.module.att.api.service.cat.tag.IAttTagApiService;
import tech.qiantong.qdata.module.att.api.service.cat.tagRel.IAttTagAssetRelApiService;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetPageReqVO;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetRespVO;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetSaveReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetColumn.vo.DaAssetColumnRelRuleVO;
import tech.qiantong.qdata.module.da.convert.asset.DaAssetConvert;
import tech.qiantong.qdata.module.da.dal.dataobject.asset.DaAssetDO;
import tech.qiantong.qdata.module.da.service.asset.IDaAssetService;
import tech.qiantong.qdata.neo4j.dto.LineageDTO;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data Asset Controller
 *
 * @author lhs
 * @date 2025-01-21
 */
@Tag(name = "数据资产")
@RestController
@RequestMapping("/da/asset")
@Validated
public class DaAssetController extends BaseController {
    @Resource
    private tech.qiantong.qdata.module.da.api.service.DataResourceAccess resourceAccess;
    @Resource
    private tech.qiantong.qdata.module.da.service.asset.AssetReferenceGuard referenceGuard;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetProjectReadAccess readAccess;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetMutationAccess mutations;
    @Resource private tech.qiantong.qdata.module.da.service.asset.AssetIntakeAccess intake;
    @Resource private tech.qiantong.qdata.module.da.service.asset.FileAssetLifecycle fileLifecycle;

    @PostMapping("/file-columns")
    @PreAuthorize("@ss.hasAnyPermi('da:asset:add,da:asset:edit')")
    public CommonResult<?> fileColumns(@RequestBody JSONObject request){
        var owners=intake.fileInspection(request.getLong("projectId"),request.getLong("assetId"));
        return CommonResult.success(fileLifecycle.inspect(request.getStr("url"),request.getInt("startColumn"),request.getInt("startData"),owners,request.getLong("assetId")));
    }

    @PreAuthorize("@ss.hasPermi('da:asset:add')")
    @GetMapping("/registrationProjects")
    public AjaxResult registrationProjects(){return success(intake.projects());}

    @PreAuthorize("@ss.hasAnyPermi('da:asset:add,da:asset:edit')")
    @GetMapping("/registrationTables")
    public AjaxResult registrationTables(@RequestParam(required=false) Long projectId,@RequestParam(required=false) Long assetId,@RequestParam(required=false) String search){return success(intake.metadata(projectId,assetId,search));}
    private void scope(DaAssetPageReqVO request){scope(request,"da:asset:list");}
    private void scope(DaAssetPageReqVO request,String permission){readAccess.requireProject(request.getProjectId(),permission);request.setAccessibleAssetIds(readAccess.ids(permission));}
    private PageResult<DaAssetRespVO> present(PageResult<DaAssetRespVO> page){page.getRows().forEach(row->{readAccess.present(row);mutations.present(row);});return page;}
    private List<DaAssetRespVO> present(List<DaAssetRespVO> rows){rows.forEach(row->{readAccess.present(row);mutations.present(row);});return rows;}
    @Resource
    private IDaAssetService daAssetService;

    @Resource
    private IAttTagAssetRelApiService attTagAssetRelApiService;
    @Resource
    private IAttTagApiService attTagApiService;

    @Operation(summary = "查询数据资产列表")
    @PreAuthorize("@ss.hasPermi('da:asset:list')")
    @GetMapping("/list")
    public CommonResult<PageResult<DaAssetRespVO>> list(DaAssetPageReqVO daAsset) {
        scope(daAsset);
        PageResult<DaAssetDO> page = daAssetService.getDaAssetPage(daAsset, "1");
        PageResult<DaAssetRespVO> result = BeanUtils.toBean(page, DaAssetRespVO.class);
        if (result.getRows().size() > 0) {
            for (DaAssetRespVO item : result.getRows()) {
                if (StringUtils.isNotBlank(item.getTags())) {
                    JSONArray tags = JSONArray.parse(item.getTags());
                    item.setTagIds(
                            tags.stream()
                                    .map(tag -> ((com.alibaba.fastjson2.JSONObject) tag).getString("tagId"))
                                    .collect(Collectors.toList())
                    );
                    item.setTagNames(
                            tags.stream()
                                    .map(tag -> ((com.alibaba.fastjson2.JSONObject) tag).getString("tagName"))
                                    .collect(Collectors.toList())
                    );
                }
            }
        }
        return CommonResult.success(present(result));
    }

    @Operation(summary = "查询数据资产列表不分页")
    @PreAuthorize("@ss.hasPermi('da:asset:list')")
    @GetMapping("/listAll")
    public CommonResult<List<DaAssetRespVO>> listAll(DaAssetPageReqVO daAsset) {
        scope(daAsset);
        List<DaAssetDO> page = daAssetService.getDaAssetListAll(daAsset, "1");
        return CommonResult.success(present(BeanUtils.toBean(page, DaAssetRespVO.class)));
    }

    /**
     * Process asset tag information
     *
     * @param bean PageResult<DaAssetRespVO>
     * @return PageResult<DaAssetRespVO>
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public PageResult<DaAssetRespVO> fillAssetTags(PageResult<DaAssetRespVO> bean) {
        List<DaAssetRespVO> rows = (List<DaAssetRespVO>) bean.getRows();
        if (CollectionUtils.isEmpty(rows)) {
            return bean;
        }

        List<AttTagAssetRelRespDTO> apiList = attTagAssetRelApiService.getApiList(new AttTagAssetRelReqDTO());
        Map<Long, String> collect1 = attTagApiService.getApiList()
                .stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s.getName()));
        Map<String, List<AttTagAssetRelRespDTO>> collect = apiList.stream()
                .collect(Collectors.groupingBy(s -> s.getAssetId()));
        for (DaAssetRespVO row : rows) {
            List<AttTagAssetRelRespDTO> attTagAssetRelRespDTOS = collect.get(Convert.toStr(row.getId()));
            row.setTagIds(new ArrayList<>());

            if (attTagAssetRelRespDTOS != null) {
                List<String> list = new ArrayList<>();
                List<String> listName = new ArrayList<>();

                for (AttTagAssetRelRespDTO attTagAssetRelRespDTO : attTagAssetRelRespDTOS) {
                    String name = collect1.get(Convert.toLong(attTagAssetRelRespDTO.getTagId()));
                    if (StringUtils.isNotEmpty(name)) {
                        listName.add(name);
                    }
                    list.add(attTagAssetRelRespDTO.getTagId());
                }

                row.setTagIds(list);
//                row.setTags(listName);
            }
        }
        bean.setRows(rows);
        return bean;
    }


    @Operation(summary = "查询数据资产列表")
    @PreAuthorize("@ss.hasPermi('att:tag:query')")
    @GetMapping("/listAssetTag")
    public CommonResult<PageResult<DaAssetRespVO>> listAssetTag(DaAssetPageReqVO daAsset) {
        scope(daAsset,"att:tag:query");
        if (CollectionUtils.isEmpty(daAsset.getTagIdList())) {
            return CommonResult.success(null);
        }

        PageResult<DaAssetDO> page = daAssetService.getDaAssetPage(daAsset, "1");
        PageResult<DaAssetRespVO> bean = BeanUtils.toBean(page, DaAssetRespVO.class);

        return CommonResult.success(present(bean));
    }

    @Deprecated
    @Operation(summary = "查询数据研发下的数据资产列表")
    @PreAuthorize("@ss.hasPermi('da:asset:list')")
    @GetMapping("/dpp/list")
    public CommonResult<PageResult<DaAssetRespVO>> dppList(DaAssetPageReqVO daAsset) {
        scope(daAsset);
        PageResult<DaAssetDO> page = daAssetService.getDppAssetPage(daAsset);
        return CommonResult.success(present(this.fillAssetTags(BeanUtils.toBean(page, DaAssetRespVO.class))));
    }

    /**
     * Query by ID
     *
     * @param ids
     * @return
     */
    @Operation(summary = "查询数据资产列表")
    @PreAuthorize("@ss.hasPermi('da:asset:list')")
    @GetMapping("/listByIds/{ids}")
    public CommonResult<PageResult<DaAssetRespVO>> list(@PathVariable("ids") List<Long> ids) {
        ids.forEach(id->readAccess.asset(id,"da:asset:list"));

        PageResult<DaAssetDO> page = daAssetService.getDaAssetByIds(ids);
        return CommonResult.success(present(BeanUtils.toBean(page, DaAssetRespVO.class)));
    }

    @Operation(summary = "查询数据研发下的数据资产全部不分页列表")
    @PreAuthorize("@ss.hasPermi('da:asset:list')")
    @GetMapping("/dpp/noPage/list")
    public AjaxResult dppNoPageList(DaAssetPageReqVO daAsset) {
        scope(daAsset);
        List<DaAssetDO> daAssetDOList = daAssetService.getDppAssetNoPageList(daAsset);
        return AjaxResult.success(daAssetDOList);
    }

    @Operation(summary = "查询资产表")
    @PreAuthorize("@ss.hasPermi('da:asset:list')")
    @GetMapping("/getTablesByDataSourceId")
    public AjaxResult getTablesByDataSourceId(DaAssetPageReqVO daAsset) {
        scope(daAsset);
        List<DaAssetDO> tablesByDataSourceId = daAssetService.getTablesByDataSourceId(daAsset);
        return AjaxResult.success(tablesByDataSourceId);
    }


    @Operation(summary = "查询资产表")
    @PreAuthorize("@ss.hasPermi('da:asset:list')")
    @GetMapping("/getDaAssetRespList")
    public CommonResult<List<DaAssetRespVO>> getDaAssetRespList(DaAssetPageReqVO daAsset) {
        scope(daAsset);
        List<DaAssetDO> tablesByDataSourceId = daAssetService.getDaAssetList(daAsset);
        return CommonResult.success(present(BeanUtils.toBean(tablesByDataSourceId, DaAssetRespVO.class)));
    }


    @Operation(summary = "导出数据资产列表")
    @PreAuthorize("@ss.hasPermi('da:asset:export')")
    @Log(title = "log.op.title.da.asset", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DaAssetPageReqVO exportReqVO) {
        scope(exportReqVO,"da:asset:export");
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DaAssetDO> list = (List<DaAssetDO>) daAssetService.getDaAssetPage(exportReqVO, "1").getRows();
        ExcelUtil<DaAssetRespVO> util = new ExcelUtil<>(DaAssetRespVO.class);
        util.exportExcel(response, DaAssetConvert.INSTANCE.convertToRespVOList(list), "App Management Data");
    }

    @Operation(summary = "导入数据资产列表")
    @PreAuthorize("@ss.hasPermi('da:asset:import')")
    @Log(title = "log.op.title.da.asset", businessType = BusinessType.IMPORT)
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception {
        ExcelUtil<DaAssetRespVO> util = new ExcelUtil<>(DaAssetRespVO.class);
        List<DaAssetRespVO> importExcelList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = daAssetService.importDaAsset(importExcelList, updateSupport, operName);
        return success(message);
    }

    @Operation(summary = "获取数据资产详细信息")
    @PreAuthorize("@ss.hasPermi('da:asset:query')")
    @GetMapping(value = "/{id:\\d+}")
    public CommonResult<DaAssetRespVO> getInfo(@PathVariable("id") Long id) {
        resourceAccess.requireAsset(id);
        readAccess.asset(id,"da:asset:query");
        return CommonResult.success(mutations.present(readAccess.present(daAssetService.getDaAssetById(id))));
    }

    @Operation(summary = "获取数据资产详细信息预览数据")
    @PreAuthorize("@ss.hasPermi('da:asset:query')")
    @PostMapping(value = "/preview")
    public AjaxResult getPreview(@RequestBody JSONObject jsonObject) {
        if (StringUtils.isEmpty(jsonObject.getStr("id"))) {
            return error("Please provide the asset ID");
        }
        resourceAccess.requireAsset(Long.valueOf(jsonObject.getStr("id")));
        readAccess.preview(Long.valueOf(jsonObject.getStr("id")),jsonObject);
        Map<String, Object> columnData = daAssetService.getColumnData(jsonObject);
        if (columnData == null) {
            return error("Unable to retrieve table data from the database, please confirm whether the table exists!");
        }

        SysUser sysUser = SecurityUtils.getLoginUser().getUser();
       // List<Map<String, Object>> dataMaskingList = daAssetService.dataMasking(Long.valueOf(jsonObject.getStr("id")), (List<Map<String, Object>>) columnData.get("tableData"));
        //1. Data asset 2. Data query
        List<Map<String, Object>> dataMaskingList = daAssetService.dataMaskings(Long.valueOf(jsonObject.getStr("id")), (List<Map<String, Object>>) columnData.get("tableData"),sysUser.getUserId(),"1");

        if (dataMaskingList == null) {
            return error("Please check whether asset fields and data table fields are consistent");
        }
        columnData.put("tableData", dataMaskingList);
        return success(tech.qiantong.qdata.common.database.core.BrowserQueryValues.convert(columnData));
    }

    @Operation(summary = "数据资产绑定资源")
    @PreAuthorize("@ss.hasPermi('da:asset:edit')")
    @Log(title = "log.op.title.da.asset", businessType = BusinessType.INSERT)
    @PostMapping("/bindResources")
    public CommonResult<Long> bindResources(@Valid @RequestBody DaAssetSaveReqVO daAsset) {
        mutations.bind(daAsset);
        daAsset.setUpdatorId(getUserId());
        daAsset.setUpdateBy(getNickName());
        daAsset.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(daAssetService.createDaAssetBindResources(daAsset));
    }

    @Operation(summary = "新增数据资产")
    @PreAuthorize("@ss.hasPermi('da:asset:add')")
    @Log(title = "log.op.title.da.asset", businessType = BusinessType.INSERT)
    @PostMapping
    public CommonResult<Long> add(@Valid @RequestBody DaAssetSaveReqVO daAsset) {
        daAsset.setCreatorId(getUserId());
        daAsset.setCreateBy(getNickName());
        daAsset.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(daAssetService.createDaAssetNew(daAsset));
    }

    @Operation(summary = "批量新增数据资产")
    @PreAuthorize("@ss.hasPermi('da:asset:add')") // Can also configure da:asset:batchAdd separately
    @Log(title = "log.op.title.da.asset", businessType = BusinessType.INSERT)
    @PostMapping("/batch")
    public CommonResult<List<Long>> batchAdd(@Valid @RequestBody List<DaAssetSaveReqVO> daAssetList) {
        Long userId = getUserId();
        String nickName = getNickName();
        Date now = DateUtil.date();

        for (DaAssetSaveReqVO daAsset : daAssetList) {
            daAsset.setCreatorId(userId);
            daAsset.setCreateBy(nickName);
            daAsset.setCreateTime(now);
        }

        return CommonResult.success(daAssetService.createDaAssetBatchNew(daAssetList));
    }

    @Operation(summary = "修改数据资产")
    @PreAuthorize("@ss.hasPermi('da:asset:edit')")
    @Log(title = "log.op.title.da.asset", businessType = BusinessType.UPDATE)
    @PutMapping
    public CommonResult<Integer> edit(@Valid @RequestBody DaAssetSaveReqVO daAsset) {
        mutations.edit(daAsset);
        daAsset.setUpdatorId(getUserId());
        daAsset.setUpdateBy(getNickName());
        daAsset.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(daAssetService.updateDaAssetNew(daAsset));
    }

    @Operation(summary = "删除数据资产")
    @PreAuthorize("@ss.hasPermi('da:asset:remove')")
    @Log(title = "log.op.title.da.asset", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ID}")
    public CommonResult<Integer> remove(@PathVariable Long ID) {
        mutations.asset(ID,"da:asset:remove");
        return CommonResult.toAjax(daAssetService.removeDaAsset(ID));
    }

    @Log(title = "log.op.title.da.discovery.task.trigger", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('da:asset:edit')")
    @PutMapping("/startDaDiscoveryTask")
    public AjaxResult startDaDiscoveryTask(@Valid @RequestBody DaAssetSaveReqVO daAsset) {
        mutations.asset(daAsset.getId(),"da:asset:edit");
        readAccess.preview(daAsset.getId(),new java.util.HashMap<>());
        return daAssetService.startDaAssetDatasourceTask(daAsset.getId());
    }

    @PreAuthorize("@ss.hasPermi('da:asset:query')")
    @GetMapping("/listRelRule")
    public CommonResult<List<DaAssetColumnRelRuleVO>> listRelRule(@RequestParam Long id, @RequestParam String type) {
        resourceAccess.requireAsset(id);
        readAccess.asset(id,"da:asset:query");
        List<DaAssetColumnRelRuleVO> vos = daAssetService.listRelRule(id, type);
        return CommonResult.success(vos);
    }

    @PreAuthorize("@ss.hasPermi('da:asset:query')")
    @GetMapping("/listRelRule/v2")
    public CommonResult<List<DaAssetColumnRelRuleVO>> listRelRule(@RequestParam Long datasourceId,
                                                                  @RequestParam String tableName,
                                                                  @RequestParam String type) {
        resourceAccess.requireTable(datasourceId,tableName);
        readAccess.physicalRules(datasourceId,tableName);
        List<DaAssetColumnRelRuleVO> vos = daAssetService.listRelRule(datasourceId, tableName, type);
        return CommonResult.success(vos);
    }


    @PreAuthorize("@ss.hasPermi('da:asset:query')")
    @GetMapping("/dataLineage/{id}")
    public CommonResult<LineageDTO> dataLineage(@PathVariable Long id) {
        resourceAccess.requireAsset(id);
        readAccess.asset(id,"da:asset:query");
        return CommonResult.success(daAssetService.dataLineage(id));
    }

    @GetMapping("/{id}/references")
    @PreAuthorize("@ss.hasPermi('da:asset:query')")
    public CommonResult<?> references(@PathVariable Long id){readAccess.asset(id,"da:asset:query");return CommonResult.success(referenceGuard.references(id));}

    @Operation(summary = "获取树类目数据（多个数据组合而来）")
    @PreAuthorize("@ss.hasPermi('da:asset:list')")
    @GetMapping("/getTreeData")
    public CommonResult<List<TreeData>> getTree() {
        return CommonResult.success(daAssetService.getTreeData());
    }
}
