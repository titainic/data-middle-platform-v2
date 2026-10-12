package tech.qiantong.qdata.module.dp.warehouse;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.enums.BusinessType;
import java.util.List;

@RestController
@RequestMapping("/dp/warehouse/serving")
@RequiredArgsConstructor
public class WarehouseServingController {
    private final WarehouseServingService serving;
    private final WarehouseOperationAccess operations;
    private final tech.qiantong.qdata.module.att.api.client.ClientManagementAccess clients;
    @GetMapping @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> overview(@RequestParam long spaceId){operations.space(spaceId,"dp:model:query");return CommonResult.success(WarehouseApiTime.metadata(serving.overview(spaceId)));}
    @Data public static class TargetRequest {private long spaceId;private long datasourceId;private String database;private int replicas=3;}
    @PostMapping("/target") @PreAuthorize("@ss.hasPermi('dp:model:edit') and @ss.hasPermi('da:dataSource:edit')")
    @Log(title="登记数仓服务目标",businessType=BusinessType.INSERT)
    public CommonResult<?> target(@RequestBody TargetRequest request){operations.space(request.getSpaceId(),"dp:model:edit","da:dataSource:edit");return CommonResult.success(WarehouseApiTime.metadata(serving.configure(request.getSpaceId(),request.getDatasourceId(),request.getDatabase(),request.getReplicas())));}
    @Data public static class SyncRequest {private String batchId;private String assessmentId;private int contractVersion=1;private boolean newAttempt;}
    @PostMapping("/releases") @PreAuthorize("@ss.hasPermi('dp:model:edit') and @ss.hasPermi('ds:api:add')")
    @Log(title="同步数仓服务版本",businessType=BusinessType.INSERT)
    public CommonResult<?> synchronize(@RequestBody SyncRequest request){operations.batch(request.getBatchId(),"dp:model:edit","ds:api:add");return CommonResult.success(WarehouseApiTime.metadata(serving.synchronize(request.getBatchId(),request.getAssessmentId(),request.getContractVersion(),request.isNewAttempt())));}
    @GetMapping("/releases/{id}") @PreAuthorize("@ss.hasPermi('dp:model:query')")
    public CommonResult<?> release(@PathVariable String id){operations.release(id,"dp:model:query");return CommonResult.success(WarehouseApiTime.metadata(serving.get(id)));}
    @PostMapping("/releases/{id}/publish") @PreAuthorize("@ss.hasPermi('dp:model:edit') and @ss.hasPermi('ds:api:edit')")
    @Log(title="发布数仓服务清单",businessType=BusinessType.UPDATE)
    public CommonResult<?> publish(@PathVariable String id){operations.release(id,"dp:model:edit","ds:api:edit");return CommonResult.success(WarehouseApiTime.metadata(serving.publish(id)));}
    @Data public static class ScopeRequest {private long spaceId;private long clientId;private List<String> locations=List.of();private List<String> columns=List.of();private List<String> maskedColumns=List.of();}
    @GetMapping("/scope") @PreAuthorize("@ss.hasPermi('att:client:query')")
    public CommonResult<?> scope(@RequestParam long spaceId,@RequestParam long clientId){operations.space(spaceId);clients.authorizeClient(clientId,"query");var result=new java.util.LinkedHashMap<String,Object>(serving.scope(spaceId,clientId));boolean editable=false;try{operations.space(spaceId,"ds:api:edit");clients.authorizeClient(clientId,"edit");editable=true;}catch(org.springframework.security.access.AccessDeniedException denied){}result.put("canEdit",editable);return CommonResult.success(WarehouseApiTime.metadata(result));}
    @PutMapping("/scope") @PreAuthorize("@ss.hasPermi('att:client:edit') and @ss.hasPermi('ds:api:edit')")
    @Log(title="设置应用数仓地点范围",businessType=BusinessType.UPDATE)
    public CommonResult<?> scope(@RequestBody ScopeRequest request){operations.space(request.getSpaceId(),"ds:api:edit");clients.authorizeClient(request.getClientId(),"edit");return CommonResult.success(WarehouseApiTime.metadata(serving.scope(request.getSpaceId(),request.getClientId(),request.getLocations(),request.getColumns(),request.getMaskedColumns())));}
}
