package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamSaveReqVO;
import java.util.*;

/** Maintains parameter identity and a valid tree under an API-scoped transaction lock. */
@Service
@RequiredArgsConstructor
public class ApiParameterGraph {
    private final JdbcTemplate jdbc;
    private final AssetChildReferences references;
    private static final Map<String,String> FIELDS=Map.of("name","name","type","type","columnType","column_type","requestFlag","request_flag","defaultValue","default_value","exampleValue","example_value","description","description","remark","remark");
    public record Node(DaAssetApiParamSaveReqVO request,Node parent,Map<String,Object> before,boolean changed){}
    public record Plan(List<Node> nodes,List<Long> removed){}
    public void lock(Long api){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("参数更新必须位于事务内");
        if(api==null || jdbc.queryForList("SELECT id FROM da_asset_api WHERE id=? AND del_flag='0' FOR UPDATE",Long.class,api).size()!=1)throw new AccessDeniedException("参数所属API不存在");
    }
    private Map<Long,Map<String,Object>> current(Long api){Map<Long,Map<String,Object>> result=new LinkedHashMap<>();for(var row:jdbc.queryForList("SELECT * FROM da_asset_api_param FORCE INDEX(idx_da_api_param_scope) WHERE api_id=? AND del_flag='0' FOR UPDATE",api))result.put(number(row.get("id")),row);return result;}
    private Map<String,Object> locate(Long id){if(id==null)throw new IllegalArgumentException("请选择参数");var rows=jdbc.queryForList("SELECT * FROM da_asset_api_param WHERE id=? AND del_flag='0'",id);if(rows.size()!=1)throw new AccessDeniedException("参数不存在");return rows.get(0);}
    public void prepareCreate(DaAssetApiParamSaveReqVO request){
        if(request.getId()!=null)throw new IllegalArgumentException("新增参数不能指定已有ID");lock(request.getApiId());var rows=current(request.getApiId());defaults(request,null);attributes(request);parent(request,rows);siblings(request,rows);references.parameterChange(request.getApiId(),null);
    }
    public void prepareUpdate(DaAssetApiParamSaveReqVO request){
        Long api=number(locate(request.getId()).get("api_id"));lock(api);var rows=current(api);var old=rows.get(request.getId());if(old==null)throw new AccessDeniedException("参数已变更，请刷新");
        if(request.getApiId()!=null && !request.getApiId().equals(api))throw new AccessDeniedException("参数不能迁移到其他API");request.setApiId(api);defaults(request,old);attributes(request);parent(request,rows);siblings(request,rows);references.parameterChange(api,request);
    }
    public int archive(Collection<Long> ids){
        if(ids.isEmpty())return 0;if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("参数归档必须位于事务内");
        List<Object> args=new ArrayList<>();args.add(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());args.add(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());args.add(new java.sql.Timestamp(System.currentTimeMillis()));args.addAll(ids);
        return jdbc.update("UPDATE da_asset_api_param SET del_flag='1',updater_id=?,update_by=?,update_time=? WHERE del_flag='0' AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray());
    }
    public void prepareRemove(Collection<Long> ids){
        Map<Long,Long> owners=new HashMap<>();for(Long id:ids)owners.put(id,number(locate(id).get("api_id")));new TreeSet<>(owners.values()).forEach(this::lock);
        Set<Long> selected=new HashSet<>(ids);for(Long api:new HashSet<>(owners.values())){var rows=current(api);references.parameterChange(api,null);for(var row:rows.values())if(selected.contains(number(row.get("parent_id"))) && !selected.contains(number(row.get("id"))))throw new IllegalArgumentException("不能遗留未处理的子参数");}
    }
    public void prepareBatchUpdate(List<DaAssetApiParamSaveReqVO> requests){
        Map<Long,Long> apis=new HashMap<>();Set<Long> ids=new HashSet<>();for(var r:requests){if(!ids.add(r.getId()))throw new IllegalArgumentException("参数ID重复");apis.put(r.getId(),number(locate(r.getId()).get("api_id")));}
        new TreeSet<>(apis.values()).forEach(this::lock);Map<Long,Map<Long,Map<String,Object>>> views=new HashMap<>();for(Long api:new HashSet<>(apis.values()))views.put(api,current(api));
        for(var r:requests){Long api=apis.get(r.getId());var before=views.get(api).get(r.getId());if(before==null || r.getApiId()!=null && !r.getApiId().equals(api))throw new AccessDeniedException("参数不属于当前API");r.setApiId(api);defaults(r,before);attributes(r);}
        Map<Long,Map<Long,Map<String,Object>>> proposed=new HashMap<>();for(var api:views.entrySet()){Map<Long,Map<String,Object>> rows=new HashMap<>();for(var row:api.getValue().entrySet())rows.put(row.getKey(),new HashMap<>(row.getValue()));proposed.put(api.getKey(),rows);}
        for(var r:requests){var row=proposed.get(r.getApiId()).get(r.getId());var bean=new BeanWrapperImpl(r);for(var f:FIELDS.entrySet())row.put(f.getValue(),bean.getPropertyValue(f.getKey()));row.put("parent_id",r.getParentId());}
        for(var r:requests){parent(r,proposed.get(r.getApiId()));siblings(r,proposed.get(r.getApiId()));references.parameterChange(r.getApiId(),r);}
    }
    public Plan plan(List<DaAssetApiParamSaveReqVO> incoming,Long api){return plan(incoming,api,null,false);}
    public Plan plan(List<DaAssetApiParamSaveReqVO> incoming,Long api,String expectedVersion){return plan(incoming,api,expectedVersion,true);}
    private Plan plan(List<DaAssetApiParamSaveReqVO> incoming,Long api,String expectedVersion,boolean checkVersion){
        lock(api);var old=current(api);for(var row:old.values()){var probe=new DaAssetApiParamSaveReqVO();probe.setId(number(row.get("id")));probe.setApiId(api);probe.setParentId(number(row.get("parent_id")));ancestor(probe,old);}
        if(checkVersion && ((expectedVersion==null && !old.isEmpty()) || expectedVersion!=null && !expectedVersion.equals(fingerprint(old.values()))))throw new tech.qiantong.qdata.common.exception.ServiceException("API参数已变更，请刷新后重试",409);
        List<Node> nodes=new ArrayList<>();Set<Long> retained=new HashSet<>();Set<DaAssetApiParamSaveReqVO> objects=Collections.newSetFromMap(new IdentityHashMap<>());
        walk(incoming==null?List.of():incoming,null,api,old,nodes,retained,objects,0);
        List<Long> removed=old.keySet().stream().filter(id->!retained.contains(id)).toList();if(!removed.isEmpty())references.parameterChange(api,null);
        for(Node node:nodes){if(node.before()==null)references.parameterChange(api,null);else if(node.changed())references.parameterChange(api,node.request());}
        return new Plan(nodes,removed);
    }
    private void walk(List<DaAssetApiParamSaveReqVO> rows,Node parent,Long api,Map<Long,Map<String,Object>> old,List<Node> nodes,Set<Long> retained,Set<DaAssetApiParamSaveReqVO> objects,int depth){
        if(depth>64)throw new IllegalArgumentException("参数嵌套不能超过64层");Set<String> names=new HashSet<>();
        for(var request:rows){
            if(nodes.size()>=10000)throw new IllegalArgumentException("每个API最多支持10000个参数节点");
            if(request==null || !objects.add(request))throw new IllegalArgumentException("参数节点不能重复或循环引用");
            var before=request.getId()==null?null:old.get(request.getId());if(request.getId()!=null && (before==null || !retained.add(request.getId())))throw new AccessDeniedException("参数ID不属于当前API或重复出现");
            if(request.getApiId()!=null && !request.getApiId().equals(api))throw new AccessDeniedException("参数不属于当前API");
            Long expected=parent==null?Long.valueOf(0):parent.request().getId();Long supplied=request.getParentId();
            if(supplied!=null && (supplied<0 || supplied>0 && !supplied.equals(expected)))throw new IllegalArgumentException("父参数ID与树结构不一致");
            request.setApiId(api);defaults(request,before);request.setParentId(expected);attributes(request);
            if(parent!=null && (!Objects.equals(parent.request().getType(),request.getType()) || !container(parent.request())))throw new IllegalArgumentException("子参数须与对象/数组父参数保持同一类型分组");
            if(!names.add(key(request)))throw new IllegalArgumentException("同一层不能重复定义同名参数");
            boolean changed=before==null || parent!=null && parent.before()==null || !Objects.equals(root(number(before.get("parent_id"))),root(expected));
            var bean=new BeanWrapperImpl(request);if(before!=null)for(var field:FIELDS.entrySet())changed|=!Objects.equals(bean.getPropertyValue(field.getKey()),before.get(field.getValue()));
            Node node=new Node(request,parent,before,changed);nodes.add(node);
            var children=request.getDaAssetApiParamList();if(children!=null && !children.isEmpty()){
                if("3".equals(request.getType()) || !container(request))throw new IllegalArgumentException("仅对象/数组参数可以包含下级参数");
                walk(children,node,api,old,nodes,retained,objects,depth+1);
            }
        }
    }
    public String version(Long api){return fingerprint(jdbc.queryForList("SELECT * FROM da_asset_api_param WHERE api_id=? AND del_flag='0'",api));}
    public static String versionOfTree(List<tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamRespVO> tree){
        List<Map<String,Object>> rows=new ArrayList<>();var pending=new ArrayDeque<tech.qiantong.qdata.module.da.controller.admin.assetchild.api.vo.DaAssetApiParamRespVO>();if(tree!=null)pending.addAll(tree);Set<Long> seen=new HashSet<>();
        while(!pending.isEmpty()){var item=pending.removeFirst();if(item.getId()==null || !seen.add(item.getId()))throw new IllegalArgumentException("参数树包含重复节点");var row=new HashMap<String,Object>();row.put("id",item.getId());row.put("api_id",item.getApiId());row.put("parent_id",item.getParentId());row.put("valid_flag",item.getValidFlag());var bean=new BeanWrapperImpl(item);for(var field:FIELDS.entrySet())row.put(field.getValue(),bean.getPropertyValue(field.getKey()));rows.add(row);if(item.getDaAssetApiParamList()!=null)pending.addAll(item.getDaAssetApiParamList());}
        return fingerprint(rows);
    }
    private static String fingerprint(Collection<Map<String,Object>> rows){
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");var ordered=new ArrayList<>(rows);ordered.sort(Comparator.comparing(row->number(row.get("id"))));
            List<String> keys=new ArrayList<>(FIELDS.values());keys.addAll(List.of("id","api_id","parent_id","valid_flag"));Collections.sort(keys);
            for(var row:ordered)for(String key:keys){Object value=row.get(key);if(key.equals("parent_id"))value=root(number(value))==null?0L:number(value);if(key.equals("valid_flag"))value=Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value))?"1":"0";String part=value==null?"-1:":String.valueOf(value).length()+":"+value;digest.update((key+"="+part+";").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            return "v1:"+java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private void defaults(DaAssetApiParamSaveReqVO request,Map<String,Object> old){
        var bean=new BeanWrapperImpl(request);if(old!=null){for(var field:FIELDS.entrySet())if(bean.getPropertyValue(field.getKey())==null)bean.setPropertyValue(field.getKey(),Objects.toString(old.get(field.getValue()),null));if(request.getParentId()==null)request.setParentId(number(old.get("parent_id")));}
        if(request.getRequestFlag()==null)request.setRequestFlag("0");
    }
    private void attributes(DaAssetApiParamSaveReqVO r){
        if(r.getName()==null || r.getName().isBlank() || r.getName().length()>128 || r.getColumnType()==null || r.getColumnType().isBlank())throw new IllegalArgumentException("参数名称及字段类型不能为空");
        if(!Set.of("1","2","3").contains(Objects.toString(r.getType(),"")) || !Set.of("0","1").contains(r.getRequestFlag()))throw new IllegalArgumentException("参数分组或必填标识无效");
        if(r.getParentId()!=null && r.getParentId()<0)throw new IllegalArgumentException("父参数ID不能为负数");
    }
    private void parent(DaAssetApiParamSaveReqVO r,Map<Long,Map<String,Object>> rows){
        ancestor(r,rows);Long parent=root(r.getParentId());if(parent!=null){var row=rows.get(parent);if(!Objects.equals(r.getType(),row.get("type")) || !Set.of("object","array").contains(Objects.toString(row.get("column_type"),"").toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("父参数必须为同组的对象或数组");}
    }
    private void ancestor(DaAssetApiParamSaveReqVO r,Map<Long,Map<String,Object>> rows){
        Set<Long> seen=new HashSet<>();Long current=root(r.getParentId());int depth=0;
        while(current!=null){if(Objects.equals(current,r.getId()) || !seen.add(current) || ++depth>64)throw new IllegalArgumentException("参数层级循环或过深");var row=rows.get(current);if(row==null)throw new AccessDeniedException("父参数不存在或不属于当前API");current=root(number(row.get("parent_id")));}
    }
    private void siblings(DaAssetApiParamSaveReqVO r,Map<Long,Map<String,Object>> rows){
        for(var row:rows.values())if(!Objects.equals(number(row.get("id")),r.getId()) && Objects.equals(root(number(row.get("parent_id"))),root(r.getParentId())) && Objects.equals(row.get("type"),r.getType())){
            String name=Objects.toString(row.get("name"),"");if("3".equals(r.getType())?name.equalsIgnoreCase(r.getName()):name.equals(r.getName()))throw new IllegalArgumentException("同一层不能重复定义同名参数");
        }
    }
    private static boolean container(DaAssetApiParamSaveReqVO r){return Set.of("object","array").contains(Objects.toString(r.getColumnType(),"").toLowerCase(Locale.ROOT));}
    private static String key(DaAssetApiParamSaveReqVO r){return r.getType()+":"+("3".equals(r.getType())?r.getName().toLowerCase(Locale.ROOT):r.getName());}
    private static Long number(Object value){return value==null?null:Long.valueOf(String.valueOf(value));}
    private static Long root(Long id){return id==null || id==0?null:id;}
}
