package tech.qiantong.qdata.module.dpp.api.warehouse;

import java.util.List;
import java.util.Map;

/** Native quality tasks remain the execution and inspection entry for fixed assessments. */
public interface WarehouseQualityGateway {
    Map<String,Object> template(long taskId);
    record Prepared(long taskId,String plannedJson,Map<Long,Long> ruleIds) { }
    Prepared create(String name,long datasourceId,String table,long assetId,Map<String,Object> template);
    void start(long taskId);
    void pause(long taskId);
}
