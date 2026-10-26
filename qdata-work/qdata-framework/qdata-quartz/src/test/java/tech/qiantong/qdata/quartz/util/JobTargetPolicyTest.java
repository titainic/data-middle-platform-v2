package tech.qiantong.qdata.quartz.util;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.quartz.domain.SysJob;
import tech.qiantong.qdata.quartz.task.RyTask;
import static org.junit.jupiter.api.Assertions.*;

class JobTargetPolicyTest {
    @Test void acceptsExistingBusinessEntryPointsAndExactClassNames() {
        for (String target : new String[]{"ryTask.ryNoParams()", "dppQuartzJob.dataIntegration(42L)", "dppQuartzJob.dataDevelopment(42L)", "qualityTaskExecutorJob.runExecuteTask(42L)", "mcTaskExecutorJob.runExecuteTask(42L)", "daDatasourceServiceImpl.detectTableSchemaUpdates(42L)", "tech.qiantong.qdata.quartz.task.RyTask.ryParams('fixture')"})
            assertTrue(ScheduleUtils.whiteList(target), target);
    }

    @Test void rejectsArbitraryClassesMethodsAndPackageNamesHiddenInArguments() {
        for (String target : new String[]{"java.lang.Runtime.getRuntime()", "otherBean.run('tech.qiantong')", "ryTask.getClass()", "ryTask.wait()", "tech.qiantong.evil.Task.run()", "ryTask.ryNoParams()suffix", "", null}) {
            assertFalse(ScheduleUtils.whiteList(target), String.valueOf(target));
            var job = new SysJob(); job.setInvokeTarget(target);
            assertThrows(ServiceException.class, () -> JobInvokeUtil.invokeMethod(job));
        }
    }

    @Test void verifiesActualBeanTypeIncludingSpringProxies() {
        JobTargetPolicy.requireBeanClass("ryTask", new RyTask());
        var proxy = new ProxyFactory(new RyTask()); proxy.setProxyTargetClass(true);
        JobTargetPolicy.requireBeanClass("ryTask", proxy.getProxy());
        assertThrows(ServiceException.class, () -> JobTargetPolicy.requireBeanClass("ryTask", new Object()));
    }

    @Test void registeredClassCanStillExecuteWithoutSpringOrBusinessServices() throws Exception {
        var job = new SysJob(); job.setInvokeTarget("tech.qiantong.qdata.quartz.task.RyTask.ryNoParams()");
        JobInvokeUtil.invokeMethod(job);
    }
}
