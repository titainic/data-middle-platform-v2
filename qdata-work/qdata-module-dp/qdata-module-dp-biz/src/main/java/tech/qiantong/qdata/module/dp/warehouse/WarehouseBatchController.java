package tech.qiantong.qdata.module.dp.warehouse;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.core.domain.CommonResult;

@RestController
@RequestMapping("/dp/warehouse/batches")
@RequiredArgsConstructor
public class WarehouseBatchController {
    private final WarehouseBatchRegistry registry;
    private final WarehouseBatchExecutor executor;
    private final WarehouseSpaceService spaces;
    private final WarehouseCatalogService catalog;
    private final WarehouseOperationAccess operations;
    @GetMapping("/{id}/catalog") @PreAuthorize("@ss.hasPermi('dp:model:query') and @ss.hasPermi('da:asset:list')")
    public CommonResult<?> catalog(@PathVariable String id){operations.batch(id,"dp:model:query","da:asset:list");return CommonResult.success(WarehouseApiTime.metadata(catalog.catalog(id)));}
    @PostMapping("/{id}/catalog") @PreAuthorize("@ss.hasPermi('dp:model:edit') and @ss.hasPermi('da:asset:add')")
    @tech.qiantong.qdata.common.annotation.Log(title="数仓批次关联治理目录", businessType=tech.qiantong.qdata.common.enums.BusinessType.UPDATE)
    public CommonResult<?> linkCatalog(@PathVariable String id){operations.batch(id,"dp:model:edit","da:asset:add");return CommonResult.success(WarehouseApiTime.metadata(catalog.link(id)));}
    @GetMapping @PreAuthorize("@ss.hasPermi('dp:model:list')")
    public CommonResult<?> list(@RequestParam long spaceId){operations.space(spaceId,"dp:model:list");executor.reconcile(spaceId);return CommonResult.success(WarehouseApiTime.metadata(registry.list(spaceId)));}
    @GetMapping("/inputs") @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> inputs(@RequestParam long spaceId){operations.space(spaceId,"dp:model:query");return CommonResult.success(WarehouseApiTime.intake(registry.inputCandidates(spaceId)));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> get(@PathVariable String id){operations.batch(id,"dp:model:query");var row=registry.get(id);executor.reconcile(((Number)row.get("space_id")).longValue());return CommonResult.success(WarehouseApiTime.metadata(registry.get(id)));}
    @PostMapping @PreAuthorize("@ss.hasPermi('dp:model:add') and @ss.hasPermi('dp:model:edit')")
    public CommonResult<?> create(@RequestBody WarehouseBatchRegistry.Request request) throws Exception{operations.space(request.getSpaceId(),"dp:model:add","dp:model:edit");return CommonResult.success(WarehouseApiTime.metadata(registry.create(request)));}
    @PostMapping("/{id}/start") @PreAuthorize("@ss.hasPermi('dp:model:edit')")
    public CommonResult<?> start(@PathVariable String id){operations.batch(id,"dp:model:edit");return CommonResult.success(WarehouseApiTime.metadata(registry.start(id)));}
}
