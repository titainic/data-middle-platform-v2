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
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.*;
import tech.qiantong.qdata.module.mc.convert.metadata.McDbConvert;
import tech.qiantong.qdata.module.mc.dal.dataobject.metadata.McDbDO;
import tech.qiantong.qdata.module.mc.service.metadata.IMcDbService;
import tech.qiantong.qdata.security.annotation.BizDataScope;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;

/**
 * DatabaseController
 *
 * @author qdata
 * @date 2026-02-11
 */
@Tag(name = "数据库")
@RestController
@RequestMapping("/mc/db")
@Validated
public class McDbController extends BaseController {
    @Resource
    private IMcDbService mcDbService;
    @Resource private tech.qiantong.qdata.module.mc.service.metadata.MetadataResourceAccess resources;
    private void scope(McDbPageReqVO request){request.setAccessibleDatabaseIds(resources.databaseIds("list"));}
    private void authorize(McDbSaveReqVO request){resources.databaseRequest(request);}

    @Operation(summary = "查询数据库列表")
    @BizDataScope(code = "mc_metadata_list", userField = "businessLeader", deptField = "responsibleDept")
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:list')")
    public CommonResult<PageResult<McDbRespVO>> list(McDbPageReqVO mcDb) {
        scope(mcDb);
        PageResult<McDbDO> page = mcDbService.getMcDbPage(mcDb);
        PageResult<McDbRespVO> result=BeanUtils.toBean(page,McDbRespVO.class);result.setRows(result.getRows().stream().map(value->resources.present(value,"list")).toList());return CommonResult.success(result);
    }

    @Operation(summary = "查询库元数据列表")
    @BizDataScope(code = "mc_metadata_list", userField = "businessLeader", deptField = "responsibleDept")
    @GetMapping("/getMcDbList")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:list')")
    public CommonResult<List<McDbRespVO>> getMcDbList(McDbPageReqVO McDb) {
        scope(McDb);
        List<McDbDO> page = mcDbService.getMcDbList(McDb);
        List<McDbRespVO> result=BeanUtils.toBean(page,McDbRespVO.class);return CommonResult.success(result.stream().map(value->resources.present(value,"list")).toList());
    }


    @Operation(summary = "导出数据库列表")
    @BizDataScope(code = "mc_metadata_list", userField = "businessLeader", deptField = "responsibleDept")
    @Log(title = "log.op.title.mc.db", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:export')")
    public void export(HttpServletResponse response, McDbPageReqVO exportReqVO) {
        exportReqVO.setAccessibleDatabaseIds(resources.databaseIds("export"));
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<McDbDO> list = (List<McDbDO>) mcDbService.getMcDbPage(exportReqVO).getRows();
        ExcelUtil<McDbRespVO> util = new ExcelUtil<>(McDbRespVO.class);
        util.exportExcel(response, McDbConvert.INSTANCE.convertToRespVOList(list).stream().map(value->resources.present(value,"export")).toList(), "Application Management Data");
    }

    @Operation(summary = "获取数据库详细信息")
    @GetMapping(value = "/{id:\\d+}")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:query')")
    public CommonResult<McDbRespVO> getInfo(@PathVariable("id") Long id) {
        resources.database(id,"query");
        McDbRespVO respVO = mcDbService.getMcDbById(id);
        return CommonResult.success(resources.present(respVO,"query"));
    }

    @Operation(summary = "新增数据库")
    @Log(title = "log.op.title.mc.db", businessType = BusinessType.INSERT)
    @PostMapping
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:add')")
    public CommonResult<Long> add(@Valid @RequestBody McDbSaveReqVO mcDb) {
        authorize(mcDb);
        mcDb.setCreatorId(getUserId());
        mcDb.setCreateBy(getNickName());
        mcDb.setCreateTime(DateUtil.date());
        return CommonResult.toAjax(mcDbService.createMcDb(mcDb));
    }

    @Operation(summary = "修改数据库")
    @Log(title = "log.op.title.mc.db", businessType = BusinessType.UPDATE)
    @PutMapping
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:edit')")
    public CommonResult<Integer> edit(@Valid @RequestBody McDbSaveReqVO mcDb) {
        authorize(mcDb);
        mcDb.setUpdatorId(getUserId());
        mcDb.setUpdateBy(getNickName());
        mcDb.setUpdateTime(DateUtil.date());
        return CommonResult.toAjax(mcDbService.updateMcDb(mcDb));
    }

    @Operation(summary = "停启用库元数据")
    @Log(title = "log.op.title.mc.db.toggle", businessType = BusinessType.UPDATE)
    @PostMapping("toggle")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:edit')")
    public CommonResult<Integer> toggle(@Valid @RequestBody ToggleStatusVO param) {
        resources.database(param.getId(),"edit");
        return CommonResult.toAjax(mcDbService.toggle(param.getId(), param.getStatus()));
    }


    @Operation(summary = "停启用库元数据")
    @Log(title = "log.op.title.mc.db.toggle", businessType = BusinessType.UPDATE)
    @PostMapping("/editPortalVisible")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:edit')")
    public CommonResult<Integer> editPortalVisible(@Valid @RequestBody McDbSaveReqVO param) {
        resources.database(param.getId(),"edit");
        return CommonResult.toAjax(mcDbService.editPortalVisible(param.getId(), param.getPortalVisible()));
    }




    @Operation(summary = "删除数据库")
    @Log(title = "log.op.title.mc.db", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:remove')")
    public CommonResult<Integer> remove(@PathVariable Long[] ids) {
        for(Long id:ids)resources.database(id,"remove");
        return CommonResult.toAjax(mcDbService.removeMcDb(Arrays.asList(ids)));
    }


    @Operation(summary = "批量删除检查库元数据")
    @GetMapping("/batchDeleteCheck/{ids}")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:remove')")
    public CommonResult<BatchDeleteCheck<Long>> batchDeleteCheck(@PathVariable Long[] ids) {
        for(Long id:ids)resources.database(id,"remove");
        BatchDeleteCheck<Long> result = mcDbService.batchDeleteCheck(Arrays.asList(ids));
        return CommonResult.success(result);
    }


    @Operation(summary = "查询库元数据列表")
    @GetMapping("/selectMetaSearchPage")
    @PreAuthorize("@ss.hasPermi('mc:metadata:table:list')")
    public CommonResult<PageResult<McMetaSearchRespDTO>> selectMetaSearchPage(McMetaSearchRespDTO mdMetaSearchRespDTO) {
        mdMetaSearchRespDTO.setAccessibleDatabaseIds(resources.databaseIds("list"));mdMetaSearchRespDTO.setAccessibleTableIds(resources.tableIds("list"));
        PageResult<McMetaSearchRespDTO> page = mcDbService.selectMetaSearchPage(mdMetaSearchRespDTO);
        for(var row:page.getRows()){row.setMdDbDO(resources.present(row.getMdDbDO(),"list"));row.setMdTableRespVO(resources.present(row.getMdTableRespVO(),"list"));row.setMdColumnDO(resources.present(row.getMdColumnDO(),"list"));}
        return CommonResult.success(page);
    }


}
