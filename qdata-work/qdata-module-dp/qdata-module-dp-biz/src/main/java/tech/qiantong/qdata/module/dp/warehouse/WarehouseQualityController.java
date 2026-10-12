package tech.qiantong.qdata.module.dp.warehouse;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.annotation.Log;
import tech.qiantong.qdata.common.enums.BusinessType;

@RestController
@RequestMapping("/dp/warehouse/quality")
@RequiredArgsConstructor
public class WarehouseQualityController {
    private final WarehouseQualityService quality;
    private final WarehouseOperationAccess operations;
    @GetMapping("/templates") @PreAuthorize("@ss.hasPermi('da:qualityTask:info')")
    public CommonResult<?> templates(@RequestParam long spaceId){operations.space(spaceId,"da:qualityTask:info");return CommonResult.success(WarehouseApiTime.metadata(quality.templates(spaceId)));}
    @GetMapping("/policies") @PreAuthorize("@ss.hasPermi('da:qualityTask:info')")
    public CommonResult<?> policies(@RequestParam long spaceId){operations.space(spaceId,"da:qualityTask:info");return CommonResult.success(WarehouseApiTime.metadata(quality.policies(spaceId)));}
    @PostMapping("/policies") @PreAuthorize("@ss.hasPermi('da:qualityTask:add') and @ss.hasPermi('dp:model:edit')")
    @Log(title="新建数仓质量策略版本",businessType=BusinessType.INSERT)
    public CommonResult<?> createPolicy(@RequestBody WarehouseQualityService.PolicyRequest request){operations.space(request.getSpaceId(),"da:qualityTask:add","dp:model:edit");return CommonResult.success(WarehouseApiTime.metadata(quality.createPolicy(request)));}
    @Data public static class AssessmentRequest {private String batchId;private Long policyId;private boolean newAttempt;}
    @PostMapping("/assessments") @PreAuthorize("@ss.hasPermi('da:qualityTask:add') and @ss.hasPermi('dp:model:edit')")
    @Log(title="准备固定版本质量评估",businessType=BusinessType.INSERT)
    public CommonResult<?> create(@RequestBody AssessmentRequest request){operations.batch(request.getBatchId(),"da:qualityTask:add","dp:model:edit");return CommonResult.success(WarehouseApiTime.metadata(quality.createAssessment(request.getBatchId(),request.getPolicyId(),request.isNewAttempt())));}
    @GetMapping("/assessments") @PreAuthorize("@ss.hasPermi('da:qualityTask:info')")
    public CommonResult<?> list(@RequestParam String batchId){operations.batch(batchId,"da:qualityTask:info");return CommonResult.success(WarehouseApiTime.metadata(quality.assessments(batchId)));}
    @GetMapping("/assessments/{id}") @PreAuthorize("@ss.hasPermi('da:qualityTask:info')")
    public CommonResult<?> get(@PathVariable String id){operations.assessment(id,"da:qualityTask:info");return CommonResult.success(WarehouseApiTime.metadata(quality.get(id)));}
    @PostMapping("/assessments/{id}/start") @PreAuthorize("@ss.hasPermi('da:qualityTask:once')")
    @Log(title="执行固定版本质量评估",businessType=BusinessType.UPDATE)
    public CommonResult<?> start(@PathVariable String id){operations.assessment(id,"da:qualityTask:once");return CommonResult.success(WarehouseApiTime.metadata(quality.start(id)));}
}
