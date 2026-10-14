package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.constant.ScheduleConstants;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskInstanceDO;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlTaskMapper;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEtlTaskServiceImpl;
import tech.qiantong.qdata.quartz.scheduler.ISchedulerAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalTaskSubmissionRegressionTest {
    private final DppEtlTaskServiceImpl service = new DppEtlTaskServiceImpl();
    private final ISchedulerAdapter scheduler = mock(ISchedulerAdapter.class);

    private void setup() {
        var mapper = mock(DppEtlTaskMapper.class);
        ReflectionTestUtils.setField(service, "dppEtlTaskMapper", mapper);
        ReflectionTestUtils.setField(service, "schedulerAdapter", scheduler);
        var task = new DppEtlTaskDO();
        task.setId(31L); task.setStatus("1"); task.setScheduler(ScheduleConstants.QUARTZ); task.setQuartzId(42L);
        when(mapper.selectById(31L)).thenReturn(task);
    }

    @Test void acceptedTriggerReportsSubmissionAndDoesNotClaimExecutionSuccess() throws Exception {
        setup();
        var response = service.startDppEtlTask(31L);
        assertEquals(200, response.get("code"));
        assertEquals("Task submitted; check the task instance for the execution result", response.get("msg"));
        verify(scheduler).trigger(argThat(command -> Long.valueOf(42L).equals(command.getId())));
    }

    @Test void rejectedTriggerReturnsAnErrorThroughTheExistingExceptionHandler() throws Exception {
        setup();
        doThrow(new SchedulerException("scheduler unavailable")).when(scheduler).trigger(any());
        assertThrows(ServiceException.class, () -> service.startDppEtlTask(31L));
    }

    @Test void asynchronousExecutionSuspendsTheInheritedServiceTransaction() throws Exception {
        setup();
        var runner = mock(DppEtlTaskDataIntegrationRunner.class);
        ReflectionTestUtils.setField(service, "dppEtlTaskDataIntegrationRunner", runner);
        var instance = new DppEtlTaskInstanceDO(); instance.setId(101L); instance.setStartTime(new Date());
        when(runner.createLocalDataXTaskInstance(any())).thenReturn(instance);
        var failure = new IllegalStateException("expected DataX failure");
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "The DataX process and its failure status must run outside the caller transaction");
            throw failure;
        }).when(runner).startDppEtlTaskDataIntegration(any(), any(), any());

        // A mocked JDBC connection lets Spring exercise real transaction suspension without external storage.
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(source);
        var factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var proxy = (IDppEtlTaskService) factory.getProxy();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(failure, assertThrows(IllegalStateException.class, () -> proxy.startDppEtlTaskDataIntegration(31L)));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "The caller transaction is resumed");
        });
        verify(connection).commit();
        verify(connection, never()).rollback();
    }
}
