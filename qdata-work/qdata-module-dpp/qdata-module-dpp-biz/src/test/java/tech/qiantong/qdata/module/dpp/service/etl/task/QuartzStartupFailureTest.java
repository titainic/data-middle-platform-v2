package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuartzStartupFailureTest {
    @Test void developmentStartupExceptionReachesTheSchedulerInsteadOfFalseSuccess() {
        var service=mock(IDppEtlTaskService.class);var failure=new IllegalStateException("missing required category");
        doThrow(failure).when(service).startDppEtlTaskDataDevelopment(39L);
        assertSame(failure,assertThrows(IllegalStateException.class,()->new DppQuartzJob(service).dataDevelopment(39L)));
    }
    @Test void integrationFailureAlsoReachesTheScheduler() {
        var service=mock(IDppEtlTaskService.class);var failure=new IllegalStateException("executor unavailable");
        doThrow(failure).when(service).startDppEtlTaskDataIntegration(34L);
        assertSame(failure,assertThrows(IllegalStateException.class,()->new DppQuartzJob(service).dataIntegration(34L)));
    }
}
