package tech.qiantong.qdata.module.dp.warehouse;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.utils.SecurityUtils;

@RestController
@RequestMapping("/dp/warehouse")
@RequiredArgsConstructor
public class WarehouseController {
    private final WarehouseService service;
    private final WarehouseSpaceService spaces;
    private final WarehouseOperationAccess operations;
    @GetMapping("/context") @PreAuthorize("@ss.hasPermi('dp:model:list')")
    public CommonResult<?> context() {
        return CommonResult.success(java.util.Map.of("metadataTimeZone", "UTC", "businessTimeZone", "+08:00", "spaces", operations.visibleSpaces("dp:model:list"), "engines", operations.visibleEngines("dp:model:list"),
                "projects", operations.projects("dp:model:list"), "canRegisterEngine", operations.canManage(),"legacyCapabilities",operations.legacyCapabilities(),"canReadLogs",!operations.projects("dp:model:query").isEmpty(),"canListDatasources",!operations.projects("da:dataSource:list").isEmpty()));
    }
    @GetMapping("/spaces/{id}/capabilities")
    public CommonResult<?> capabilities(@PathVariable Long id){return CommonResult.success(operations.capabilities(id));}
    @PostMapping("/engines") @PreAuthorize("@ss.hasPermi('dp:model:edit')")
    public CommonResult<?> engine(@RequestBody WarehouseSpaceService.Engine engine) {
        operations.engine(engine);
        return CommonResult.success(spaces.registerEngine(engine));
    }
    @PostMapping("/spaces") @PreAuthorize("@ss.hasPermi('dp:model:add') and @ss.hasPermi('dp:model:edit')")
    public CommonResult<?> space(@RequestBody WarehouseSpaceService.Space space) {
        operations.manageSpace(space);
        return CommonResult.success(spaces.createSpace(space));
    }
    @GetMapping("/spaces/{id}/inspect") @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> inspect(@PathVariable Long id) {operations.space(id,"dp:model:query");return CommonResult.success(spaces.inspect(id));}
    @GetMapping("/models") @PreAuthorize("@ss.hasPermi('dp:model:list')")
    public CommonResult<?> models() { return CommonResult.success(operations.visibleModels("dp:model:list")); }
    @GetMapping("/datasources") @PreAuthorize("@ss.hasPermi('da:dataSource:list')")
    public CommonResult<?> datasources() {return CommonResult.success(operations.datasources());}
    @PostMapping("/validate") @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> validate(@RequestBody WarehouseDefinition definition) {operations.definition(definition,"dp:model:query");return CommonResult.success(service.validateWithData(definition));}
    @PostMapping("/validate/encoded") @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> validateEncoded(@RequestBody WarehouseTransport.EncodedDefinition request) {
        var definition=WarehouseTransport.decode(request);operations.definition(definition,"dp:model:query");
        return CommonResult.success(service.validateWithData(definition));
    }
    @PostMapping("/models") @PreAuthorize("@ss.hasPermi('dp:model:add') and @ss.hasPermi('dp:model:edit')")
    public CommonResult<?> save(@RequestBody WarehouseDefinition definition) {operations.definition(definition,"dp:model:add","dp:model:edit");return CommonResult.success(service.save(definition,SecurityUtils.getUsername(),SecurityUtils.getUserId()));}
    @PostMapping("/models/encoded") @PreAuthorize("@ss.hasPermi('dp:model:add') and @ss.hasPermi('dp:model:edit')")
    public CommonResult<?> saveEncoded(@RequestBody WarehouseTransport.EncodedDefinition request) {
        var definition=WarehouseTransport.decode(request);operations.definition(definition,"dp:model:add","dp:model:edit");
        return CommonResult.success(service.save(definition,SecurityUtils.getUsername(),SecurityUtils.getUserId()));
    }
    @PostMapping("/models/{id}/execute") @PreAuthorize("@ss.hasPermi('dp:model:edit')")
    public CommonResult<?> execute(@PathVariable long id,@RequestParam String mode) {operations.model(id,"dp:model:edit");return CommonResult.success(service.start(id,mode,SecurityUtils.getUsername()));}
    @GetMapping("/logs") @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> logs(@RequestParam(required=false) Long modelId) {if(modelId!=null)operations.model(modelId,"dp:model:query");return CommonResult.success(WarehouseApiTime.metadata(service.logs(modelId,operations.visibleModels("dp:model:query").stream().map(WarehouseDefinition::getId).toList())));}
    @GetMapping("/models/{id}/data") @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> data(@PathVariable long id) {operations.model(id,"dp:model:query");return CommonResult.success(tech.qiantong.qdata.common.database.core.BrowserQueryValues.convert(service.data(id)));}
}
