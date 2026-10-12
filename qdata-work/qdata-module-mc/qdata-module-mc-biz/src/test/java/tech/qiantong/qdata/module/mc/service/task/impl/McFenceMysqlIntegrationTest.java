package tech.qiantong.qdata.module.mc.service.task.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tech.qiantong.qdata.redis.service.OwnedRedisLease;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="QDATA_MC_FENCE_URL",matches=".+")
class McFenceMysqlIntegrationTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource() {
            String url=System.getenv("QDATA_MC_FENCE_URL");
            if(!url.split("\\?")[0].matches("jdbc:mysql://127\\.0\\.0\\.1:3307/qdata_metadata_fence_[a-z0-9_]+"))throw new IllegalArgumentException("Only the isolated metadata fixture is allowed");
            return new DriverManagerDataSource(url,"root",System.getenv("QDATA_NAMESPACE_PASSWORD"));
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source){return new JdbcTemplate(source);}
        @Bean PlatformTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean McTableTxService transactions(){return new McTableTxService();}
    }
    @Test @Timeout(15) void nestedFailureRollsBackTheWholeMetadataOperationWithoutSelfDeadlock() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var jdbc=context.getBean(JdbcTemplate.class);var tx=context.getBean(McTableTxService.class);
            String before=jdbc.queryForObject("SELECT remark FROM mc_task WHERE id=8",String.class);
            jdbc.update("UPDATE mc_task SET active_instance_id=16 WHERE id=8");
            McExecutionFence.open(8,16,mock(OwnedRedisLease.class));
            try {
                assertThrows(IllegalStateException.class,()->tx.runInTransaction(()->{
                    jdbc.update("UPDATE mc_task SET remark='uncommitted-fence-test' WHERE id=8");
                    return tx.runInTransaction(()->{throw new IllegalStateException("Abort nested metadata change");});
                }));
                assertEquals(before,jdbc.queryForObject("SELECT remark FROM mc_task WHERE id=8",String.class));
            } finally {McExecutionFence.close();jdbc.update("UPDATE mc_task SET active_instance_id=NULL WHERE id=8 AND active_instance_id=16");}
        }
    }
}
