package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlNodeInstanceMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION", matches="true")
class NodeStatusCacheIntegrationTest {
    interface CachedStatus {
        @Select("SELECT status FROM DPP_ETL_NODE_INSTANCE WHERE id=#{id}")
        String read(@Param("id") long id);
    }

    @Test void executionVerificationReadsThroughAnExistingMyBatisSessionCache() {
        String url = System.getenv("QDATA_NAMESPACE_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:3307/qdata_namespace_acceptance_[a-zA-Z0-9_]+(?:\\?.*)?"));
        var source = new DriverManagerDataSource(url, "root", System.getenv("QDATA_NAMESPACE_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE IF NOT EXISTS DPP_ETL_NODE_INSTANCE(id BIGINT PRIMARY KEY,status VARCHAR(16),del_flag TINYINT DEFAULT 0)");
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO DPP_ETL_NODE_INSTANCE(id,status,del_flag) VALUES (?,'1',0)", id);
        var configuration = new Configuration(new Environment("namespace-status-test", new JdbcTransactionFactory(), source));
        configuration.addMapper(CachedStatus.class);
        configuration.addMapper(DppEtlNodeInstanceMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var cached = session.getMapper(CachedStatus.class);
            assertEquals("1", cached.read(id));
            jdbc.update("UPDATE DPP_ETL_NODE_INSTANCE SET status='7' WHERE id=?", id);
            assertEquals("1", cached.read(id), "The existing session still contains the earlier running state");
            assertEquals("7", session.getMapper(DppEtlNodeInstanceMapper.class).selectStatusForExecution(id));
            jdbc.update("UPDATE DPP_ETL_NODE_INSTANCE SET status='6' WHERE id=?", id);
            assertEquals("6", session.getMapper(DppEtlNodeInstanceMapper.class).selectStatusForExecution(id));
        }
    }
}
