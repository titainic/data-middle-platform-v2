package tech.qiantong.qdata.module.mc.controller.admin.metadata;

import cn.hutool.core.date.DateUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.BatchDeleteCheck;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.core.page.PageParam;
import tech.qiantong.qdata.common.core.page.PageResult;
import tech.qiantong.qdata.common.enums.BusinessType;
import tech.qiantong.qdata.common.utils.object.BeanUtils;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnPageReqVO;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnRespVO;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McColumnSaveReqVO;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.ToggleStatusVO;
import tech.qiantong.qdata.module.mc.convert.metadata.McColumnConvert;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McColumnDO;
import tech.qiantong.qdata.module.mc.service.metadata.IMcColumnService;
import tech.qiantong.qdata.security.annotation.BizDataScope;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.Arrays;
import java.util.List;

/**
 * Metadata field informationController
 *
 * @author qdata
 * @date 2026-02-11
 */
@Tag(name = "元数据字段信息")
@RestController
@RequestMapping("/mc/column")
@Validated
public class McColumnController extends BaseController {
    @Resource
    private IMcColumnService mcColumnService;
    @Resource private tech.qiantong.qdata.module.mc.service.metadata.MetadataResourceAccess resources;
    private void scope(McColumnPageReqVO request){request.setAccessibleTableIds(resources.tableIds("list"));}
    private void authorize(McColumnSaveReqVO request){resources.columnRequest(request);}

    @Operation(summary = "查询元数据字段信息列表")
    @BizDataScope(code = "mc_metadata_list", userField = "businessLeader", deptField = "responsibleDept")
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:list')")
    public CommonResult<PageResult<McColumnRespVO>> list(McColumnPageReqVO mcColumn) {
        scope(mcColumn);
        PageResult<McColumnDO> page = mcColumnService.getMcColumnPage(mcColumn);
        PageResult<McColumnRespVO> result=BeanUtils.toBean(page,McColumnRespVO.class);result.setRows(result.getRows().stream().map(value->resources.present(value,"list")).toList());return CommonResult.success(result);
    }

    @Operation(summary = "查询字段元数据列表")
    @BizDataScope(code = "mc_metadata_list", userField = "businessLeader", deptField = "responsibleDept")
    @GetMapping("/getMdColumnList")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:list')")
    public CommonResult<List<McColumnRespVO>> getMdColumnList(McColumnPageReqVO mdColumn) {
        scope(mdColumn);
        List<McColumnDO> page = mcColumnService.getMdColumnList(mdColumn);
        List<McColumnRespVO> result=BeanUtils.toBean(page,McColumnRespVO.class);return CommonResult.success(result.stream().map(value->resources.present(value,"list")).toList());
    }

    @Operation(summary = "导出元数据字段信息列表")
    @BizDataScope(code = "mc_metadata_list", userField = "businessLeader", deptField = "responsibleDept")
    @Log(title = "log.op.title.mc.column", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:export')")
    public void export(HttpServletResponse response, McColumnPageReqVO exportReqVO) {
        exportReqVO.setAccessibleTableIds(resources.tableIds("export"));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<McColumnDO> list = (List<McColumnDO>) mcColumnService.getMcColumnPage(exportReqVO).getRows();
        ExcelUtil<McColumnRespVO> util = new ExcelUtil<>(McColumnRespVO.class);
        util.exportExcel(response, McColumnConvert.INSTANCE.convertToRespVOList(list).stream().map(value->resources.present(value,"export")).toList(), "Application Management Data");
    }

    @Operation(summary = "获取元数据字段信息详细信息")
    @GetMapping(value = "/{id:\\d+}")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:query')")
    public CommonResult<McColumnRespVO> getInfo(@PathVariable("id") Long id) {
        resources.column(id,"query");
        McColumnRespVO respVO = mcColumnService.getMcColumnById(id);
        return CommonResult.success(resources.present(respVO,"query"));
    }

    @Operation(summary = "新增元数据字段信息")
    @Log(title = "log.op.title.mc.column", businessType = BusinessType.INSERT)
    @PostMapping
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:add')")
    public CommonResult<Integer> add(@Valid @RequestBody @NotEmpty List<@Valid McColumnSaveReqVO> mdColumn) {
        for (McColumnSaveReqVO saveReqVO : mdColumn) {
            authorize(saveReqVO);
            saveReqVO.validate();
            saveReqVO.setCreatorId(getUserId());
            saveReqVO.setCreateBy(getNickName());
            saveReqVO.setCreateTime(DateUtil.date());
        }
        return CommonResult.toAjax(mcColumnService.createMdColumn(mdColumn));
    }

    @Operation(summary = "暂存字段元数据信息")
    @Log(title = "log.op.title.mc.column.draft", businessType = BusinessType.INSERT)
    @PostMapping("draft")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:add')")
    public CommonResult<Integer> draft(@RequestBody @NotEmpty List<McColumnSaveReqVO> saveReqVO) {
        saveReqVO.forEach(this::authorize);
        return CommonResult.toAjax(mcColumnService.saveDraft(saveReqVO));
    }


    @Operation(summary = "修改元数据字段信息")
    @Log(title = "log.op.title.mc.column", businessType = BusinessType.UPDATE)
    @PutMapping
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:edit')")
    public CommonResult<Integer> edit(@Valid @RequestBody McColumnSaveReqVO mcColumn) {
        authorize(mcColumn);
        mcColumn.setUpdatorId(getUserId());
        mcColumn.setUpdateBy(getNickName());
        mcColumn.setUpdateTime(DateUtil.date());
        mcColumn.validate();
        return CommonResult.toAjax(mcColumnService.updateMcColumn(mcColumn));
    }

    @Operation(summary = "停启用字段元数据")
    @Log(title = "log.op.title.mc.column.toggle", businessType = BusinessType.UPDATE)
    @PostMapping("toggle")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:edit')")
    public CommonResult<Integer> toggle(@Valid @RequestBody ToggleStatusVO param) {
        resources.column(param.getId(),"edit");
        return CommonResult.toAjax(mcColumnService.toggle(param.getId(), param.getStatus()));
    }


    @Operation(summary = "删除元数据字段信息")
    @Log(title = "log.op.title.mc.column", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:remove')")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)resources.column(id,"remove");
        return CommonResult.toAjax(mcColumnService.removeMcColumn(Arrays.asList(ids)));
    }

    @Operation(summary = "批量删除检查字段元数据")
    @GetMapping("/batchDeleteCheck/{ids}")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:remove')")
    public CommonResult<BatchDeleteCheck<Long>> batchDeleteCheck(@PathVariable Long[] ids) {
        for(Long id:ids)resources.column(id,"remove");
        BatchDeleteCheck<Long> result = mcColumnService.batchDeleteCheck(Arrays.asList(ids));
        return CommonResult.success(result);
    }

}
