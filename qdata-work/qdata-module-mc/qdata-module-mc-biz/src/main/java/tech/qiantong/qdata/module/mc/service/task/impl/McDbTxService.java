package tech.qiantong.qdata.module.mc.service.task.impl;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.McDbSaveReqVO;
import tech.qiantong.qdata.module.mc.service.metadata.IMcDbService;

import jakarta.annotation.Resource;

@Service
public class McDbTxService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Resource
    @Lazy
    private IMcDbService mcDbApiService;

    /**
     * Library-level metadata: independent transaction, commit immediately
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Long createDbAndCommit(McDbSaveReqVO dbScope) {
        McExecutionFence.verify(jdbc);
        Long id=mcDbApiService.createMcDb(dbScope);
        McExecutionFence.verifyOwner();return id;
    }
}
