package tech.qiantong.qdata.module.mc.controller.admin.task;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.qiantong.qdata.common.core.controller.BaseController;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.exception.enums.GlobalErrorCodeConstants;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.module.mc.service.task.IMcTaskService;

import jakarta.annotation.Resource;

/**
 * Collection task executorController
 * Used for DolphinScheduler callback to perform collection tasks
 *
 * @author qdata
 * @date 2026-05-11
 */
@Tag(name = "采集任务执行器")
@RestController
@RequestMapping("/mc/taskExecutor")
@Validated
public class McTaskExecutorController extends BaseController {
    @Resource private tech.qiantong.qdata.module.mc.service.task.CollectionResourceAccess resources;

    @Resource
    private IMcTaskService mcTaskService;

    /**
     * DolphinScheduler callback to perform collection tasks
     *
     * @param id task ID
     * @return execution result
     */
    @PutMapping("/runExecuteTask/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public CommonResult<String> runExecuteTask(@PathVariable("id") Long id) {
        resources.task(id,"execute");
        try {
            if(!mcTaskService.runDaDiscoveryTask(id))return CommonResult.error(GlobalErrorCodeConstants.ERROR.getCode(), "采集执行失败，请查看任务实例与日志");
            return CommonResult.success(MessageUtils.message("mc.task.execute.success", id));
        } catch (NumberFormatException e) {
            return CommonResult.error( GlobalErrorCodeConstants.ERROR.getCode(), MessageUtils.message("mc.task.execute.id.format.error", id));
        } catch (Exception e) {
            return CommonResult.error( GlobalErrorCodeConstants.ERROR.getCode(), MessageUtils.message("mc.task.execute.fail", e.getMessage()));
        }
    }
}
