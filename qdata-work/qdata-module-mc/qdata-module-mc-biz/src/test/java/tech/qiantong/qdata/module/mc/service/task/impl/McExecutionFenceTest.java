package tech.qiantong.qdata.module.mc.service.task.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.redis.service.OwnedRedisLease;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class McExecutionFenceTest {
    @AfterEach void clear(){McExecutionFence.close();}
    @Test void replacementInstancePreventsTheOldAttemptFromWriting() {
        var jdbc=mock(JdbcTemplate.class);var lease=mock(OwnedRedisLease.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(8L))).thenReturn(17L);
        McExecutionFence.open(8,16,lease);var transactions=new McTableTxService();ReflectionTestUtils.setField(transactions,"jdbc",jdbc);
        var writes=new AtomicInteger();assertThrows(IllegalStateException.class,()->transactions.runInTransaction(writes::incrementAndGet));assertEquals(0,writes.get());
    }
    @Test void lostLeaseAfterTheOperationDoesNotConfirmTheTransaction() {
        var jdbc=mock(JdbcTemplate.class);var lease=mock(OwnedRedisLease.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(8L))).thenReturn(16L);
        doNothing().doThrow(new IllegalStateException("Owner lost")).when(lease).requireOwner();
        McExecutionFence.open(8,16,lease);var transactions=new McTableTxService();ReflectionTestUtils.setField(transactions,"jdbc",jdbc);
        assertThrows(IllegalStateException.class,()->transactions.runInTransaction(()->"pending commit"));
    }
    @Test void nestedMetadataOperationsJoinTheExistingTransaction() throws Exception {
        var method=McTableTxService.class.getMethod("runInTransaction",Supplier.class);
        assertEquals(Propagation.REQUIRED,method.getAnnotation(Transactional.class).propagation());
    }
}
