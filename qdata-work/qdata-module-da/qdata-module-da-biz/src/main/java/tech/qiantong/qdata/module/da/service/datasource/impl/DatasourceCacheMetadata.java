package tech.qiantong.qdata.module.da.service.datasource.impl;

import com.alibaba.fastjson2.JSON;
import tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Secretless canary format for a future Worker credential-resolution contract.
 * The legacy Redis hash remains untouched while Workers still depend on it.
 */
final class DatasourceCacheMetadata {
    static final String REDIS_HASH = "datasource-meta-v1";

    private DatasourceCacheMetadata() { }

    static String serialize(DaDatasourceDO source) {
        Objects.requireNonNull(source, "source");
        Long id = Objects.requireNonNull(source.getId(), "datasource ID");
        if (id <= 0) {
            throw new IllegalArgumentException("Invalid datasource ID");
        }
        // Whitelist fields only. Never traverse datasourceConfig or simplify(),
        // which decrypts a usable password and retains the original config.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("datasourceId", id);
        metadata.put("datasourceType", source.getDatasourceType());
        metadata.put("host", source.getIp());
        metadata.put("port", source.getPort());
        metadata.put("credentialRef", "datasource:" + id);
        return JSON.toJSONString(metadata);
    }
}
