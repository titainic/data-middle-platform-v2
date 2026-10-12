package tech.qiantong.qdata.module.mc.service.task.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Service
public class McTableTxService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * Single table transaction:
     * - outer calls start a transaction; nested metadata operations join it
     * - Throw exception = rollback only current table
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T runInTransaction(Supplier<T> supplier) {
        McExecutionFence.verify(jdbc);
        T result=supplier.get();
        McExecutionFence.verifyOwner();
        return result;
    }
}
