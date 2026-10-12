package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import java.util.*;
import java.util.function.ToLongFunction;

/** Called inside the task-save transaction; old published versions remain untouched. */
@Component
@RequiredArgsConstructor
public class QuartzDefinitionVersioning {
    private final JdbcTemplate jdbc;
    public void advance(DppEtlNewNodeSaveReqVO request,DppEtlTaskDO task) {
        Long current=jdbc.queryForObject("SELECT version FROM dpp_etl_task WHERE id=? AND del_flag='0' FOR UPDATE",Long.class,task.getId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task_instance WHERE task_id=? AND status IN ('0','1','2','12') AND del_flag='0'",Long.class,task.getId())>0)
            throw new IllegalArgumentException("任务仍有排队或执行中的实例，请等待完成再编辑");
        prepare(request,current,code->{
            List<Long> versions=jdbc.queryForList("SELECT version FROM dpp_etl_node_log WHERE code=? ORDER BY version DESC LIMIT 1 FOR UPDATE",Long.class,code);
            return versions.isEmpty()?0:versions.get(0);
        });
        request.setCode(task.getCode());
    }

    static void prepare(DppEtlNewNodeSaveReqVO request,Long current,ToLongFunction<String> nodeVersion) {
        if(current==null || current<1 || request.getVersion()==null || request.getVersion().longValue()!=current)
            throw new IllegalArgumentException("任务版本已变化或缺少版本，请刷新后重试");
        JSONArray nodes=JSON.parseArray(request.getTaskDefinitionList()),relations=JSON.parseArray(request.getTaskRelationJson());
        if(nodes==null || nodes.isEmpty() || relations==null || relations.isEmpty())throw new IllegalArgumentException("请配置完整的任务节点和依赖");
        Map<String,Long> versions=new LinkedHashMap<>();
        for(int i=0;i<nodes.size();i++) {
            JSONObject node=nodes.getJSONObject(i);String code=node.getString("code");
            if(code==null || code.isBlank() || versions.containsKey(code))throw new IllegalArgumentException("节点编码缺失或重复");
            long version=Math.addExact(nodeVersion.applyAsLong(code),1);versions.put(code,version);node.put("version",version);
        }
        for(int i=0;i<relations.size();i++) {
            JSONObject edge=relations.getJSONObject(i);
            for(String side:List.of("pre","post")) {
                String code=edge.getString(side+"TaskCode");
                if("pre".equals(side) && (code==null || "0".equals(code)))edge.put(side+"TaskVersion",0);
                else {
                    if(!versions.containsKey(code))throw new IllegalArgumentException("依赖引用了当前任务之外的节点");
                    edge.put(side+"TaskVersion",versions.get(code));
                }
            }
        }
        List<tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO> graphNodes=new ArrayList<>();
        versions.forEach((code,v)->{var n=new tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO();n.setCode(code);n.setVersion(v);graphNodes.add(n);});
        List<DppPublishedExecutionPlan.Edge> graphEdges=new ArrayList<>();Set<String> targets=new HashSet<>();
        for(int i=0;i<relations.size();i++) {
            JSONObject edge=relations.getJSONObject(i);String pre=edge.getString("preTaskCode"),post=edge.getString("postTaskCode");targets.add(post);
            graphEdges.add(new DppPublishedExecutionPlan.Edge(pre==null || "0".equals(pre)?null:new DppPublishedExecutionPlan.Key(pre,versions.get(pre)),new DppPublishedExecutionPlan.Key(post,versions.get(post))));
        }
        if(!targets.equals(versions.keySet()))throw new IllegalArgumentException("每个节点必须有完整的发布关系，起点使用根节点关系");
        DppPublishedExecutionPlan.order(graphNodes,graphEdges);
        request.setVersion(Math.toIntExact(Math.addExact(current,1)));
        request.setTaskDefinitionList(nodes.toJSONString());request.setTaskRelationJson(relations.toJSONString());
    }
}
