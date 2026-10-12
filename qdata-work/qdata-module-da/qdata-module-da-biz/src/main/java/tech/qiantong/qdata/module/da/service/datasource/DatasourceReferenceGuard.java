package tech.qiantong.qdata.module.da.service.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Preserve connection identities used by current definitions and retained execution history. */
@Service
@RequiredArgsConstructor
public class DatasourceReferenceGuard {
    private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> DIRECT=List.of(
        "da_asset","da_asset_operate_apply","da_asset_operate_log","da_discovery_task",
        "dp_model","dp_model_materialized","dp_warehouse_engine","dp_warehouse_serving_target",
        "dpp_onl_desform","dpp_quality_task_obj","mc_db","mc_table","mc_table_log",
        "mc_column","mc_column_log","mc_task","ai_chat_conversation");
    private static final Map<String,String> DOCUMENTS=Map.ofEntries(
        Map.entry("ds_api","config_json"),Map.entry("dpp_etl_task","draft_json"),
        Map.entry("dpp_etl_node","parameters"),Map.entry("dpp_etl_node_log","parameters"),
        Map.entry("dpp_quality_execution_snapshot","definition_json"),
        Map.entry("dp_warehouse_quality_assessment_task","planned_json"),
        Map.entry("dp_warehouse_batch_submission","source_json"),
        Map.entry("dp_warehouse_run","definition_json"),
        Map.entry("dp_warehouse_batch_step","definition_json"),
        Map.entry("dp_architecture_document","graph_json"));
    private static final Set<String> SOURCE_KEYS=Set.of("datasourceid","sourceid","targetdatasourceid","sourcedatasourceid","datasource");

    public Map<String,Long> references(Long id) {
        if(id==null || id<=0)throw new IllegalArgumentException("请选择有效数据连接");
        Map<String,Long> counts=new TreeMap<>();
        // No del_flag filter: historical logs and retired definitions still identify this connection.
        for(String table:DIRECT){long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE datasource_id=?",Long.class,id);if(count>0)counts.put(table,count);}
        for(var document:DOCUMENTS.entrySet()){
            long[] count={0};String column=document.getValue();
            jdbc.query("SELECT "+column+" FROM "+document.getKey()+" WHERE "+column+" IS NOT NULL",(org.springframework.jdbc.core.RowCallbackHandler)rs->{
                String text=rs.getString(1);if(text==null || text.isBlank())return;
                try{if(references(JSON.readTree(text),id))count[0]++;}
                catch(Exception invalid){throw new IllegalArgumentException("引用定义无法解析，不能确认连接可删除："+document.getKey());}
            });
            if(count[0]>0)counts.put(document.getKey(),count[0]);
        }
        return counts;
    }
    public void requireRemovable(Collection<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.stream().anyMatch(id->id==null || id<=0))throw new IllegalArgumentException("请选择有效数据连接");
        for(Long id:new LinkedHashSet<>(ids))if(!references(id).isEmpty())throw new IllegalArgumentException("连接 "+id+" 被业务定义或执行历史引用，不能删除；请保留连接及关联记录");
    }
    static boolean references(JsonNode node,long id) {
        if(node==null || node.isNull())return false;
        if(node.isArray()){for(JsonNode child:node)if(references(child,id))return true;return false;}
        if(node.isTextual()){
            String text=node.textValue().trim();
            // DPP draft nodes can carry their parameters as a JSON string.
            if(text.startsWith("{") || text.startsWith("["))try{return references(JSON.readTree(text),id);}catch(Exception ignored){throw new IllegalArgumentException("嵌套引用定义无法解析");}
            return false;
        }
        if(!node.isObject())return false;
        if("datasource".equalsIgnoreCase(node.path("refType").asText()) && sameId(node.get("refId"),id))return true;
        var fields=node.fields();
        while(fields.hasNext()){
            var field=fields.next();String key=field.getKey().replace("_","").toLowerCase(Locale.ROOT);
            if(SOURCE_KEYS.contains(key) && sameId(field.getValue(),id))return true;
            if(field.getValue().isContainerNode() || Set.of("parameters","taskparams","draftjson","configjson").contains(key))if(references(field.getValue(),id))return true;
        }
        return false;
    }
    private static boolean sameId(JsonNode value,long id){return value!=null && (value.isIntegralNumber() ? value.canConvertToLong() && value.longValue()==id : value.isTextual() && Long.toString(id).equals(value.textValue().trim()));}
}
