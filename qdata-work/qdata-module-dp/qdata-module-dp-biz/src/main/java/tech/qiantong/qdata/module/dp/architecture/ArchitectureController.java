package tech.qiantong.qdata.module.dp.architecture;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.dp.warehouse.WarehouseService;
import java.sql.Statement;
import java.util.*;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

@RestController
@RequestMapping("/dp/architecture")
@RequiredArgsConstructor
public class ArchitectureController {
 private final JdbcTemplate jdbc;
 private final ArchitectureResourceAccess access;
 @GetMapping("/projects") @PreAuthorize("@ss.hasPermi('dp:model:list')")
 public CommonResult<?> projects(){return CommonResult.success(access.projects());}
 @GetMapping("/documents") @PreAuthorize("@ss.hasPermi('dp:model:list')")
 public CommonResult<?> list(){return CommonResult.success(access.documents());}
 @GetMapping("/documents/{id}") @PreAuthorize("@ss.hasPermi('dp:model:query')")
 public CommonResult<?> get(@PathVariable long id){
  var row=access.document(id,"query");var d=JSON.parseObject((String)row.get("graph_json"),ArchitectureDocument.class);
  d.setId(id);d.setRevision(((Number)row.get("revision")).intValue());d.setProjectId(row.get("project_id")==null?null:((Number)row.get("project_id")).longValue());d.setCanEdit(access.canEdit(d.getProjectId()));return CommonResult.success(d);
 }
 @GetMapping("/catalog") @PreAuthorize("@ss.hasPermi('dp:model:list')")
 public CommonResult<?> catalog(@RequestParam Long projectId){return CommonResult.success(access.catalog(projectId));}
 @PostMapping("/validate") @PreAuthorize("@ss.hasPermi('dp:model:query')")
 public CommonResult<?> validate(@RequestBody ArchitectureDocument d){access.prepare(d,"query");validateReferences(d);return CommonResult.success("架构与逐层连线校验通过；图形配置不会直接执行基础设施或数据任务");}
 @org.springframework.transaction.annotation.Transactional
 @PostMapping("/documents") @PreAuthorize("@ss.hasPermi('dp:model:edit')")
 public CommonResult<?> save(@RequestBody ArchitectureDocument d){
  access.prepare(d,"edit");validateReferences(d);String user=SecurityUtils.getUsername();int prior=d.getRevision();java.sql.Timestamp now=java.sql.Timestamp.from(java.time.Instant.now());
  if(d.getId()==null){
   d.setRevision(1);GeneratedKeyHolder key=new GeneratedKeyHolder();
   jdbc.update(conn->{var s=conn.prepareStatement("INSERT INTO dp_architecture_document(name,revision,graph_json,create_by,update_by,project_id,creator_id,create_time,update_time) VALUES (?,1,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);s.setString(1,d.getName());s.setString(2,JSON.toJSONString(d));s.setString(3,user);s.setString(4,user);s.setLong(5,d.getProjectId());s.setLong(6,SecurityUtils.getUserId());s.setTimestamp(7,now);s.setTimestamp(8,now);return s;},key);
   d.setId(key.getKey().longValue());jdbc.update("UPDATE dp_architecture_document SET graph_json=?,update_time=? WHERE id=?",JSON.toJSONString(d),now,d.getId());
  }else{
   d.setRevision(prior+1);int changed=jdbc.update("UPDATE dp_architecture_document SET name=?,revision=?,graph_json=?,update_by=?,update_time=? WHERE id=? AND revision=? AND project_id=?",d.getName(),d.getRevision(),JSON.toJSONString(d),user,now,d.getId(),prior,d.getProjectId());
   require(changed==1,"架构已被其他用户修改，请刷新版本后重试；当前编辑内容仍保留在页面");
  }
  d.setCanEdit(access.canEdit(d.getProjectId()));return CommonResult.success(d);
 }
 private void validateReferences(ArchitectureDocument d){
  ArchitectureRules.validate(d);
  var models=access.models(d.getProjectId());
  for(var n:d.getNodes()) {
   if(n.getRefId()==null){require(!"REGISTERED".equals(n.getState()),"已登记节点必须关联平台资源；手工节点请选择规划或待确认："+n.getLabel());continue;}
   require(Set.of("MODEL","DATASOURCE","API").contains(Objects.toString(n.getRefType(),"")),"资源关联类型无效");
   if("DATASOURCE".equals(n.getRefType()))access.datasource(d.getProjectId(),n.getRefId());
   if("API".equals(n.getRefType()))access.api(d.getProjectId(),n.getRefId());
   if("MODEL".equals(n.getRefType())) {
    var model=models.stream().filter(m->Objects.equals(m.getId(),n.getRefId())).findFirst().orElseThrow(()->new org.springframework.security.access.AccessDeniedException("请选择当前项目有权查看的五层模型"));
    require("DATA".equals(n.getScope()) && model.getLayer().equals(n.getLayer()) && "Hive".equals(n.getEngine()),"模型节点分层/引擎必须与实际模型一致");
   }
  }
  for(var e:d.getEdges()) if("REGISTERED".equals(e.getState()) && !"NETWORK".equals(e.getKind())) {
   var a=d.getNodes().stream().filter(n->n.getId().equals(e.getSource())).findFirst().orElseThrow();
   var b=d.getNodes().stream().filter(n->n.getId().equals(e.getTarget())).findFirst().orElseThrow();
   if("MODEL".equals(a.getRefType()) && "MODEL".equals(b.getRefType())) {
    var from=models.stream().filter(m->Objects.equals(m.getId(),a.getRefId())).findFirst().orElseThrow();
    var to=models.stream().filter(m->Objects.equals(m.getId(),b.getRefId())).findFirst().orElseThrow();
    boolean matches="DIMENSION".equals(e.getKind()) ? from.getDimensions().stream().anyMatch(rel->Objects.equals(rel.getModelId(),to.getId())) : to.getUpstreamIds().contains(from.getId());
    require(matches,"此连线与已登记模型血缘不一致，请标记为规划："+a.getLabel()+" → "+b.getLabel());
   }
  }
  for(var e:d.getEdges())if(e.getTaskId()!=null)access.task(d.getProjectId(),e.getTaskId());
 }
}
