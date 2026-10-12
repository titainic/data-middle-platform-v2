package tech.qiantong.qdata.common.database.core;

import com.alibaba.fastjson2.JSON;
import java.util.LinkedHashMap;
import java.util.List;

/** Metadata and quality viewers need connection labels, not connection credentials. */
public final class ConnectionDisplayConfig {
    private ConnectionDisplayConfig() { }
    public static String sanitize(String configuration) {
        if (configuration == null || configuration.isBlank()) return "{}";
        try {
            var parsed=JSON.parseObject(configuration);var visible=new LinkedHashMap<String,Object>();
            for(String key:List.of("dbname","dbName","username","sid","schema","serviceName",
                    "keyId","bucket","endpoint","domain")) {
                Object value=parsed.get(key);
                if(value instanceof String || value instanceof Number || value instanceof Boolean)visible.put(key,value);
            }
            return JSON.toJSONString(visible);
        } catch(RuntimeException invalid) { return "{}"; }
    }
}
