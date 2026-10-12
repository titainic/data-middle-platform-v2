package tech.qiantong.qdata.acceptance;

import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;

/** Copies endpoint metadata only. Callers keep DataSourceFactory mocked and assert no transport calls. */
final class HiveEndpointFixture {
    private HiveEndpointFixture() {}
    static DaDatasourceDO source(JdbcTemplate jdbc,long id) {
        var row=jdbc.queryForMap("SELECT datasource_type,ip,port,datasource_config FROM da_datasource WHERE id=?",id);
        var source=new DaDatasourceDO();source.setId(id);source.setDatasourceType((String)row.get("datasource_type"));
        source.setIp((String)row.get("ip"));source.setPort(((Number)row.get("port")).longValue());source.setDatasourceConfig((String)row.get("datasource_config"));
        return source;
    }
}
