package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import tech.qiantong.qdata.module.dpp.api.warehouse.WarehouseTaskGateway.InputColumn;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import tech.qiantong.qdata.common.database.core.ReadOnlySql;
import tech.qiantong.qdata.common.utils.SecretRedactor;
import java.util.*;

/** Read the actual ancestry of the fixed writer; never publish paths or connection parameters. */
final class WarehouseInputLineage {
    record Flow(JSONObject writer,List<InputColumn> columns) {}
    static Flow build(List<DppEtlNodeRespVO> nodes,Map<String,Set<String>> parents) {
        Map<String,DppEtlNodeRespVO> byCode=new LinkedHashMap<>();List<String> writers=new ArrayList<>();
        for(var node:nodes){require(byCode.putIfAbsent(node.getCode(),node)==null,"接入图中节点编码重复，无法唯一确认版本");if("91".equals(component(node)))writers.add(node.getCode());}
        require(writers.size()==1,"接入血缘需要唯一Hive输出");
        List<DppEtlNodeRespVO> chain=new ArrayList<>();Set<String> seen=new HashSet<>();String at=writers.get(0);
        while(at!=null){require(seen.add(at),"接入血缘包含循环");var node=byCode.get(at);require(node!=null,"接入节点快照缺失");chain.add(node);var input=parents.getOrDefault(at,Set.of());require(input.size()<=1,"接入血缘暂不支持多输入合流");at=input.isEmpty()?null:input.iterator().next();}
        Collections.reverse(chain);require("4".equals(component(chain.get(0))),"接入来源必须是CSV读取节点");
        Map<String,InputColumn> fields=new LinkedHashMap<>();JSONObject writer=null;
        for(var node:chain){
            var params=JSON.parseObject(node.getParameters());String component=component(node);
            require("SPARK".equalsIgnoreCase(params.getString("taskType")),"接入字段血缘当前需要固定的Spark节点配置");
            if("4".equals(component)){
                require(fields.isEmpty(),"不能串联多个CSV读取节点");WarehouseTaskGatewayImpl.requireRawReader(params);
                for(Object value:params.getJSONArray("columns")){var column=(JSONObject)value;String name=column.getString("columnName");require(name!=null&&!name.isBlank()&&!fields.containsKey(name),"CSV字段映射重复或为空");fields.put(name,new InputColumn(name,List.of(name),List.of(),List.of(step(node,"CSV_READ",Map.of("column",name,"declaredType","STRING")))));}
            }else if("39".equals(component)){
                require("data_date".equals(params.getString("fieldDerivationName")) && "FIELD_DERIVE_CONCAT".equals(params.getString("fieldDerivationType")),"未支持的原始接入派生规则，不能推断字段来源");
                List<String> selected=new ArrayList<>();Set<String> csv=new TreeSet<>(),conditions=new TreeSet<>();List<Map<String,Object>> steps=new ArrayList<>();
                require(params.getJSONArray("tableFields")!=null,"派生字段输入缺失");
                for(Object value:params.getJSONArray("tableFields")){String name=((JSONObject)value).getString("columnName");var origin=find(fields,name);require(origin!=null,"派生输入字段未登记");selected.add(name);csv.addAll(origin.csvColumns());conditions.addAll(origin.conditionColumns());steps.addAll(origin.steps());}
                require(!selected.isEmpty(),"派生字段输入不能为空");
                steps.add(step(node,"FIELD_DERIVE_CONCAT",Map.of("columns",selected,"delimiter",text(params,"delimiter"),"prefix",text(params,"fieldDerivationPrefix"),"suffix",text(params,"fieldDerivationSuffix"),"nullText","null")));
                fields.put("data_date",new InputColumn("data_date",List.copyOf(csv),List.copyOf(conditions),List.copyOf(steps)));
            }else if("31".equals(component)){
                JSONObject args=params.getJSONObject("mainArgs");require(args==null || args.getJSONArray("cleanRuleList")==null || args.getJSONArray("cleanRuleList").isEmpty(),"动态清洗规则未冻结，无法确认接入字段血缘");
                fields=filter(fields,params.getString("where"),node);
                var rules=params.getJSONArray("tableFields");
                if(rules!=null)for(Object value:rules){var rule=(JSONObject)value;var config=JSON.parseObject(rule.getString("ruleConfig"));
                    require("DATE_FORMAT_STD".equals(rule.getString("ruleType")) && config!=null && List.of("data_date").equals(config.getList("columns",String.class)),"原始接入包含未支持的字段清洗");
                    fields=filter(fields,rule.getString("whereClause"),node);var origin=find(fields,"data_date");require(origin!=null,"采集日期来源缺失");
                    Map<String,Object> safe=new LinkedHashMap<>();safe.put("column","data_date");safe.put("targetFormat",text(config,"targetFormat"));safe.put("inputFormats",config.getList("inputFormats",String.class)==null?List.of():config.getList("inputFormats",String.class));
                    List<Map<String,Object>> steps=new ArrayList<>(origin.steps());steps.add(step(node,"DATE_FORMAT_STD",safe));fields.put("data_date",new InputColumn("data_date",origin.csvColumns(),origin.conditionColumns(),List.copyOf(steps)));
                }
            }else if("91".equals(component)){
                require(Objects.equals(params.getJSONArray("columns"),params.getJSONArray("target_columns")),"原始输出字段映射已变更");require(params.getIntValue("writeModeType")==2,"接入来源不是追加输出");writer=params;
            }else throw new IllegalArgumentException("接入字段血缘包含未支持节点");
        }
        require(writer!=null,"Hive输出快照缺失");List<InputColumn> result=new ArrayList<>();
        for(String name:writer.getList("target_columns",String.class)){var field=find(fields,name);require(field!=null,"Hive输出字段缺少来源："+name);result.add(new InputColumn(name,field.csvColumns(),field.conditionColumns(),field.steps()));}
        return new Flow(writer,List.copyOf(result));
    }
    private static Map<String,InputColumn> filter(Map<String,InputColumn> fields,String where,DppEtlNodeRespVO node){
        if(where==null||where.isBlank())return fields;
        String sql="SELECT * FROM qdata_input WHERE "+where;
        require(ReadOnlySql.tables(sql,com.alibaba.druid.DbType.hive).equals(Set.of("qdata_input")),"接入条件引用了外部表");
        var select=(com.alibaba.druid.sql.ast.statement.SQLSelectStatement)com.alibaba.druid.sql.SQLUtils.parseSingleStatement(sql,com.alibaba.druid.DbType.hive);
        var query=(com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock)select.getSelect().getQuery();Set<String> columns=new TreeSet<>();
        require(query.getOrderBy()==null&&query.getGroupBy()==null&&query.getLimit()==null&&select.getSelect().getOrderBy()==null,"接入筛选只能包含字段条件");
        query.getWhere().accept(new com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter(){
            public boolean visit(com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr column){var origin=find(fields,column.getName());require(origin!=null,"接入条件字段未登记");columns.addAll(origin.csvColumns());return false;}
            public boolean visit(com.alibaba.druid.sql.ast.expr.SQLPropertyExpr column){throw new IllegalArgumentException("接入条件含未支持的限定字段");}
        });
        Map<String,InputColumn> result=new LinkedHashMap<>();fields.forEach((name,origin)->{Set<String> conditions=new TreeSet<>(origin.conditionColumns());conditions.addAll(columns);List<Map<String,Object>> steps=new ArrayList<>(origin.steps());steps.add(step(node,"ROW_FILTER",Map.of("condition",SecretRedactor.redact(where))));result.put(name,new InputColumn(name,origin.csvColumns(),List.copyOf(conditions),List.copyOf(steps)));});return result;
    }
    private static InputColumn find(Map<String,InputColumn> fields,String name){
        var matches=fields.values().stream().filter(field->field.name().equalsIgnoreCase(Objects.toString(name,""))).toList();require(matches.size()<=1,"输入字段存在大小写歧义");return matches.isEmpty()?null:matches.get(0);
    }
    private static Map<String,Object> step(DppEtlNodeRespVO node,String operation,Map<String,Object> config){return Map.of("nodeCode",node.getCode(),"nodeVersion",node.getVersion(),"operation",operation,"configuration",config);}
    private static String text(JSONObject json,String key){String value=json.getString(key);return value==null?"":SecretRedactor.redact(value);}
    private static String component(DppEtlNodeRespVO node){String code=JSON.parseObject(node.getParameters()).getString("componentType");return code==null?node.getComponentType():code;}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
