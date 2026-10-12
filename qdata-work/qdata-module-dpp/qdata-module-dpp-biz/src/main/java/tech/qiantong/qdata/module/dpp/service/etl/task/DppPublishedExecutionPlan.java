package tech.qiantong.qdata.module.dpp.service.etl.task;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import java.util.*;

/** Reads a published graph by task code/version, never by mutable current-node joins. */
@Service
@RequiredArgsConstructor
public class DppPublishedExecutionPlan {
    private final JdbcTemplate jdbc;
    public record Key(String code, long version) {
        public Key { if(code==null || code.isBlank() || version<1)throw new IllegalArgumentException("节点版本标识无效"); }
    }
    public record Edge(Key source,Key target) { }

    @Transactional(readOnly=true)
    public List<DppEtlNodeRespVO> load(String taskCode,String projectCode,Long version) {
        if(version==null || version<1)throw new IllegalArgumentException("任务缺少已发布版本，请重新发布后执行");
        List<Edge> edges=new ArrayList<>();List<DppEtlNodeRespVO> nodes=new ArrayList<>();
        jdbc.query("SELECT r.pre_node_code,r.pre_node_version,r.post_node_code,r.post_node_version,n.id,n.code,n.version,n.name,n.parameters,n.component_type FROM dpp_etl_task_node_rel_log r LEFT JOIN dpp_etl_node_log n ON n.code=r.post_node_code AND n.version=r.post_node_version AND n.project_code=r.project_code AND n.del_flag='0' WHERE r.task_code=? AND r.project_code=? AND r.task_version=? AND r.del_flag='0' ORDER BY r.id",rs->{
            String code=rs.getString("code");long nodeVersion=rs.getLong("version");
            if(code==null || !code.equals(rs.getString("post_node_code")) || nodeVersion!=rs.getLong("post_node_version"))
                throw new IllegalArgumentException("已发布节点快照缺失或版本不符，请重新发布任务");
            var node=new DppEtlNodeRespVO();node.setId(rs.getLong("id"));node.setCode(code);node.setVersion(nodeVersion);node.setName(rs.getString("name"));node.setParameters(rs.getString("parameters"));node.setComponentType(rs.getString("component_type"));nodes.add(node);
            String pre=rs.getString("pre_node_code");
            edges.add(new Edge(pre==null || pre.isBlank() || "0".equals(pre)?null:new Key(pre,rs.getLong("pre_node_version")),new Key(code,nodeVersion)));
        },taskCode,projectCode,version);
        if(nodes.isEmpty())throw new IllegalArgumentException("没有完整的已发布任务快照，请保存并重新发布任务");
        return order(nodes,edges);
    }

    static List<DppEtlNodeRespVO> order(List<DppEtlNodeRespVO> input,List<Edge> edges) {
        Map<Key,DppEtlNodeRespVO> nodes=new LinkedHashMap<>();
        for(var node:input) {
            Key key=new Key(node.getCode(),node.getVersion());var old=nodes.putIfAbsent(key,node);
            if(old!=null && (!Objects.equals(old.getParameters(),node.getParameters()) || !Objects.equals(old.getComponentType(),node.getComponentType())))
                throw new IllegalArgumentException("同一已发布节点版本存在不同内容，请重新发布为新版本");
        }
        Map<Key,Set<Key>> parents=new LinkedHashMap<>();nodes.keySet().forEach(k->parents.put(k,new LinkedHashSet<>()));
        for(var edge:edges) {
            if(!nodes.containsKey(edge.target()) || (edge.source()!=null && !nodes.containsKey(edge.source())))
                throw new IllegalArgumentException("依赖引用的节点版本不在发布快照中");
            if(edge.source()!=null)parents.get(edge.target()).add(edge.source());
        }
        List<DppEtlNodeRespVO> sorted=new ArrayList<>();Set<Key> done=new HashSet<>();
        while(done.size()<nodes.size()) {
            Key ready=parents.entrySet().stream().filter(e->!done.contains(e.getKey()) && done.containsAll(e.getValue()))
                    .map(Map.Entry::getKey).min(Comparator.comparing(Key::code).thenComparingLong(Key::version)).orElseThrow(()->new IllegalArgumentException("已发布任务存在循环依赖，未执行任何节点"));
            done.add(ready);sorted.add(nodes.get(ready));
        }
        return sorted;
    }
}
