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

package tech.qiantong.qdata.module.dpp.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tech.qiantong.qdata.common.core.domain.BaseEntity;
import tech.qiantong.qdata.common.utils.JSONUtils;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import java.util.List;
import java.util.Map;

/**
 * New Data Integration Request VO
 *
 * @author qdata
 * @date 2025-02-19
 */
@Schema(description = "新数据集成请求 VO")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DppEtlNewNodeSaveReqVO extends BaseEntity {

    /** Full task writes; release, scheduling and copy requests use the default group. */
    public interface TaskSave extends Default {}

    /** Category Code */
    @Parameter(name = "catCode", description = "类目编码")
    @Size(max = 32, message = "类目编码长度不能超过32个字符", groups = TaskSave.class)
    private String catCode;
    private Long catId;

    /** Person in Charge */
    @Parameter(name = "personCharge", description = "责任人")
    @NotBlank(message = "责任人不能为空", groups = TaskSave.class)
    @Size(max = 32, message = "责任人长度不能超过32个字符", groups = TaskSave.class)
    private String personCharge;

    /** Contact Number */
    @Parameter(name = "catCode", description = "联系电话")
    @Size(max = 32, message = "联系电话长度不能超过32个字符", groups = TaskSave.class)
    private String contactNumber;

    @Parameter(name = "projectCode", description = "项目编码", required = true)
    @NotNull(message = "项目编码不能为空")
    private Long projectCode;

    @Schema(description = "任务类型", example = "")
    @Size(max = 256, message = "任务类型长度不能超过256个字符")
    @Size(max = 10, message = "任务类型长度不能超过10个字符", groups = TaskSave.class)
    private String type;

    @Schema(description = "项目id", example = "")
    @NotNull(message = "项目ID不能为空", groups = TaskSave.class)
    private Long projectId;

    @Parameter(name = "name", description = "名称", required = true)
    @NotBlank(message = "任务名称不能为空", groups = TaskSave.class)
    @Size(max = 128, message = "任务名称长度不能超过128个字符", groups = TaskSave.class)
    private String name;

    @Parameter(name = "description", description = "描述", required = true)
    @Size(max = 255, message = "描述长度不能超过256个字符")
    private String description;

    @Parameter(name = "globalParams", description = "全局参数", required = false)
    private String globalParams = "[]";  // Default value

    @Parameter(name = "locations", description = "位置参数", required = false)
    private List<Map<String,Object>> locations;

    @Parameter(name = "timeout", description = "超时时间", required = false)
    private Long timeout = 0L;  // Default value

    @Parameter(name = "taskRelationJson", description = "任务关系的 JSON", required = true)
    private String taskRelationJson;

    @Parameter(name = "taskDefinitionList", description = "任务定义的 JSON", required = true)
    private String taskDefinitionList;

    @Parameter(name = "otherParamsJson", description = "其他参数的 JSON", required = false)
    private String otherParamsJson;

    @Parameter(name = "executionType", description = "执行类型", required = false)
    @NotBlank(message = "执行策略不能为空", groups = TaskSave.class)
    @Size(max = 32, message = "执行策略长度不能超过32个字符", groups = TaskSave.class)
    private String executionType;

    // Online status 0:Offline, 1:Online
    private String releaseState;
    // Online status 0:Offline, 1:Online
    private String schedulerState;
    private String status;
    private String code;
    @NotBlank(message = "调度表达式不能为空", groups = TaskSave.class)
    @Size(max = 255, message = "调度表达式长度不能超过255个字符", groups = TaskSave.class)
    private String crontab;
    private String id;
    private Integer version;

    @Schema(description = "调度器", example = "DOLPHINSCHEDULER")
    @Size(max = 32, message = "调度器长度不能超过32个字符", groups = TaskSave.class)
    private String scheduler;

    @Schema(description = "执行器", example = "SPARK")
    @Size(max = 32, message = "执行器长度不能超过32个字符", groups = TaskSave.class)
    private String actuator;

    /**
     * taskType (SPARK, FINK)
     * taskPriority - Task Priority
     * workerGroup - Group
     * failRetryTimes - Fail Retry Count
     * delayTime - Delay Execution Time
     * failRetryInterval - Fail Retry Interval
     *
     *
     * SPARK:
     * driverCores - Driver Cores
     * driverMemory - Driver Memory
     * numExecutors - Number of Executors
     * executorMemory - Executor Memory
     * executorCores - Executor Cores
     * yarnQueue    -----Yarn Queue
     *
     *
     * FINK:
     * jobManagerMemory----JobManager Memory
     * taskManagerMemory------TaskManager Memory
     * slot-----Slot Count
     * taskManager-----TaskManager Count
     * parallelism - Parallelism
     * yarnQueue    -----Yarn Queue
     */
    @Schema(description = "草稿任务配置信息", example = "")
    private String draftJson;

    public DppEtlNewNodeSaveReqVO(DppEtlTaskUpdateQueryRespVO src) {
        if (src == null) {
            return;
        }

        this.catCode = src.getCatCode();
        this.catId = src.getCatId();
        this.personCharge = src.getPersonCharge();
        this.contactNumber = src.getContactNumber();
        this.projectCode = JSONUtils.convertToLong(src.getProjectCode());
        this.projectId = src.getProjectId();
        this.type = src.getType();
        this.name = src.getName();
        this.description = src.getDescription();
        this.locations = src.getLocations();
        this.timeout = src.getTimeout();
        this.executionType = src.getExecutionType();
        this.crontab = src.getCrontab();
        this.draftJson = src.getDraftJson();

        // Fields that need JSON conversion
        if (src.getTaskRelationJson() != null) {
            this.taskRelationJson = JSONUtils.toJson(src.getTaskRelationJson());
        }
        if (src.getTaskDefinitionList() != null) {
            this.taskDefinitionList = JSONUtils.toJson(src.getTaskDefinitionList());
        }

        // Fixed to offline
        this.releaseState = "0";
        this.schedulerState = "0";
        this.status = "0";
    }
}
