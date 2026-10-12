package tech.qiantong.qdata.quartz.util;

import org.junit.jupiter.api.Test;
import org.quartz.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tech.qiantong.qdata.common.constant.ScheduleConstants;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import tech.qiantong.qdata.quartz.domain.*;
import tech.qiantong.qdata.quartz.service.IQuartzJobLogService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuartzLogRegressionTest {
    private JobExecutionContext context(QuartzJob job) {
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        data.put(ScheduleConstants.TASK_PROPERTIES, job);
        when(context.getMergedJobDataMap()).thenReturn(data);
        return context;
    }

    private void logs(IQuartzJobLogService service) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("quartzJobLogService", service);
        org.springframework.context.support.StaticMessageSource messages = new org.springframework.context.support.StaticMessageSource();
        messages.addMessage("log.task.duration", Locale.US, "{0}: {1} ms");
        messages.addMessage("log.task.execution.error", Locale.US, "Task execution failed");
        beans.registerSingleton("messageSource", messages);
        new SpringUtils().postProcessBeanFactory(beans);
    }

    @Test void blankLegacyNamesStillProduceUsableLogs() throws Exception {
        List<SysJobLog> saved = new ArrayList<>();
        logs(saved::add);
        AbstractQuartzJob runner = new AbstractQuartzJob() {
            protected void doExecute(JobExecutionContext c, SysJob j) { }
        };
        for (String name : Arrays.asList(null, "", "  ", "Hive SQL")) {
            QuartzJob job = new QuartzJob(); job.setJobId(8L); job.setJobName(name);
            runner.execute(context(job));
        }
        assertEquals(4, saved.size());
        for (int i = 0; i < 3; i++) assertEquals("QuartzJob-8", saved.get(i).getJobName());
        assertEquals("Hive SQL", saved.get(3).getJobName());
        assertTrue(saved.stream().allMatch(l -> "0".equals(l.getStatus())));
    }

    @Test void businessFailureIsLoggedExactlyOnce() throws Exception {
        List<SysJobLog> saved = new ArrayList<>(); logs(saved::add);
        AbstractQuartzJob runner = new AbstractQuartzJob() {
            protected void doExecute(JobExecutionContext c, SysJob j) { throw new IllegalStateException("business failure"); }
        };
        QuartzJob job = new QuartzJob(); job.setJobId(9L);
        runner.execute(context(job));
        assertEquals(1, saved.size());
        assertEquals("1", saved.get(0).getStatus());
        assertTrue(saved.get(0).getExceptionInfo().contains("business failure"));
    }

    @Test void logFailureDoesNotRepeatCleanupOrHideBusinessError() {
        int[] calls = {0};
        RuntimeException databaseError = new IllegalStateException("database unavailable");
        RuntimeException businessError = new IllegalStateException("business failure");
        logs(l -> { calls[0]++; throw databaseError; });
        AbstractQuartzJob runner = new AbstractQuartzJob() {
            protected void doExecute(JobExecutionContext c, SysJob j) { throw businessError; }
        };
        QuartzJob job = new QuartzJob(); job.setJobId(9L);
        JobExecutionException error = assertThrows(JobExecutionException.class, () -> runner.execute(context(job)));
        assertEquals(1, calls[0]);
        assertSame(databaseError, error.getCause());
        assertArrayEquals(new Throwable[]{businessError}, databaseError.getSuppressed());
    }
}
