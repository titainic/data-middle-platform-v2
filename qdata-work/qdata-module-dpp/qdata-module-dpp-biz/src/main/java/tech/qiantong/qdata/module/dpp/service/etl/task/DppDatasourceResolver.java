package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import java.util.Map;

/** Resolves saved node references against the current datasource configuration. */
@Component
public class DppDatasourceResolver {
    private final IDaDatasourceApiService datasources;
    private final DppHiveWritePolicy writePolicy;

    public DppDatasourceResolver(IDaDatasourceApiService datasources,DppHiveWritePolicy writePolicy) {
        this.datasources = datasources;
        this.writePolicy = writePolicy;
    }

    public JSONObject current(JSONObject saved) {
        if (saved == null) return null;
        Long id = saved.getLong("datasourceId");
        if (id == null) id = saved.getLong("id");
        if (id == null) return saved;
        var source = datasources.getDatasourceById(id);
        if (source == null) {
            throw new ServiceException("dpp.error.data.development.node.datasource.missing",
                    "The referenced datasource no longer exists.");
        }
        JSONObject current = new JSONObject(saved);
        current.put("datasourceId", id);
        current.put("datasourceName", source.getDatasourceName());
        current.put("datasourceType", source.getDatasourceType());
        current.put("ip", source.getIp());
        current.put("port", source.getPort());
        current.put("datasourceConfig", source.getDatasourceConfig());
        return current;
    }

    public Map<String, Object> refreshNodeParameters(Map<String, Object> parameters) {
        JSONObject resolved = new JSONObject(parameters);
        for (String key : new String[]{"datasources", "readerDatasource", "writerDatasource"}) {
            JSONObject saved = resolved.getJSONObject(key);
            if (saved != null) resolved.put(key, current(saved));
        }
        writePolicy.integration(resolved);
        return resolved;
    }
}
