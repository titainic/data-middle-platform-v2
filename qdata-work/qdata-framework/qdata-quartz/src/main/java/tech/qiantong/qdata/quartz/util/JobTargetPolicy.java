package tech.qiantong.qdata.quartz.util;

import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import tech.qiantong.qdata.common.exception.ServiceException;

/** 以实际业务入口登记任务；禁止包名子串、参数内伪造包名及任意 bean 方法。 */
final class JobTargetPolicy {
    private static final Logger log = LoggerFactory.getLogger(JobTargetPolicy.class);
    private record Target(String className, Set<String> methods) {}
    private static final Map<String, Target> TARGETS = Map.of(
            "ryTask", new Target("tech.qiantong.qdata.quartz.task.RyTask", Set.of("ryNoParams", "ryParams", "ryMultipleParams")),
            "dppQuartzJob", new Target("tech.qiantong.qdata.module.dpp.service.etl.task.DppQuartzJob", Set.of("dataIntegration", "dataDevelopment")),
            "qualityTaskExecutorJob", new Target("tech.qiantong.qdata.module.dpp.controller.admin.job.QualityTaskExecutorJob", Set.of("runExecuteTask")),
            "mcTaskExecutorJob", new Target("tech.qiantong.qdata.module.mc.controller.admin.job.McTaskExecutorJob", Set.of("runExecuteTask")),
            "daDatasourceServiceImpl", new Target("tech.qiantong.qdata.module.da.service.datasource.impl.DaDatasourceServiceImpl", Set.of("detectTableSchemaUpdates")));

    static boolean allowed(String invokeTarget) {
        if (invokeTarget == null || !invokeTarget.matches("[\\w.$]+\\.[\\w$]+\\(.*\\)")) return false;
        String bean = JobInvokeUtil.getBeanName(invokeTarget), method = JobInvokeUtil.getMethodName(invokeTarget);
        Target target = target(bean);
        return target != null && target.methods().contains(method);
    }

    static void requireAllowed(String invokeTarget) {
        if (!allowed(invokeTarget)) {
            log.warn("拒绝Quartz任务：调用类或方法未登记");
            throw new ServiceException("Quartz调用目标未登记到允许列表");
        }
    }

    static void requireBeanClass(String name, Object bean) {
        Target target = target(name);
        if (target == null || !target.className().equals(AopUtils.getTargetClass(bean).getName()))
            throw new ServiceException("Quartz任务bean实际类型与登记不一致");
    }

    private static Target target(String name) {
        Target byAlias = TARGETS.get(name);
        return byAlias != null ? byAlias : TARGETS.values().stream().filter(t -> t.className().equals(name)).findFirst().orElse(null);
    }
}
