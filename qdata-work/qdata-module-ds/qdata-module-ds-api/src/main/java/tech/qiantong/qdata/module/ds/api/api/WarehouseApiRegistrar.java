package tech.qiantong.qdata.module.ds.api.api;

import java.util.List;
import java.util.Map;

public interface WarehouseApiRegistrar {
    long register(String name,String path,int version,long datasourceId,String database,String table,List<Map<String,Object>> columns);
    void activate(long apiId);
}
