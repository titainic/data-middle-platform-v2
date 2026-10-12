package tech.qiantong.qdata.module.dpp.api.warehouse;

import java.util.List;
import java.util.Map;

/** Internal module boundary. These DTOs contain references and contracts, never credentials. */
public interface WarehouseTaskGateway {
    record InputReceipt(long instanceId,long taskId,int taskVersion,long projectId,long datasourceId,
                        String outputTable,String originalFile,String fileSha256,long fileBytes,long fileRows) { }
    record NodeSpec(int ordinal,long modelId,String name,String sqlPreview,long datasourceId,
                    String databaseName,List<Integer> dependencies) { }
    record TaskSpec(String batchId,String name,long projectId,String projectCode,String scheduler,String computeEngine,List<NodeSpec> nodes) { }
    record CreatedTask(long taskId,int version,Map<Integer,String> nodeCodes) { }
    record InputColumn(String name,List<String> csvColumns,List<String> conditionColumns,List<Map<String,Object>> steps) { }
    record InputLineage(long instanceId,long taskId,int taskVersion,long projectId,long datasourceId,
                        String outputTable,List<InputColumn> columns) { }
    InputReceipt receipt(long taskInstanceId);
    default InputLineage inputLineage(long taskInstanceId) {throw new UnsupportedOperationException("接入字段血缘尚未提供");}
    CreatedTask create(TaskSpec specification);
    void start(long taskId);
    default void startViaDolphin(long taskId, String batchId, String projectCode) {
        throw new UnsupportedOperationException("DolphinScheduler 数仓触发器尚未配置");
    }
}
