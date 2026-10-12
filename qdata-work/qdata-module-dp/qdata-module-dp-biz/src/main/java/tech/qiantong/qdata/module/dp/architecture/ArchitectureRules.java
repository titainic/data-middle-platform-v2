package tech.qiantong.qdata.module.dp.architecture;
import java.util.*;
import static tech.qiantong.qdata.module.dp.warehouse.WarehouseSql.require;

public final class ArchitectureRules {
 private ArchitectureRules(){}
 public static final List<String> LAYERS=List.of("SOURCE","ODS","DWD","DWS","DWT","ADS","DORIS","API","APP");
 public static void validate(ArchitectureDocument d) {
  require(d.getName()!=null && !d.getName().isBlank() && d.getName().length()<=128,"请填写架构名称（最多128字）");
  require(d.getNodes()!=null && d.getNodes().size()<=200 && d.getEdges()!=null && d.getEdges().size()<=500,"架构最多200个节点、500条连线");
  Map<String,ArchitectureDocument.Node> nodes=new HashMap<>();
  for(var n:d.getNodes()) {
   identifier(n.getId());require(nodes.put(n.getId(),n)==null,"节点ID重复："+n.getId());
   require(n.getLabel()!=null && !n.getLabel().isBlank() && n.getLabel().length()<=128,"节点名称不能为空或超过128字");
   require(Set.of("DEPLOYMENT","DATA").contains(n.getScope()),"请选择部署或数据视图");
   require(Set.of("HOST","SERVICE","DATABASE","TABLE","API","CLIENT").contains(n.getKind()),"不支持的节点类型");
   require(Set.of("PLANNED","REGISTERED","UNKNOWN").contains(n.getState()),"不支持的节点状态");
   require(Double.isFinite(n.getX()) && Double.isFinite(n.getZ()) && Math.abs(n.getX())<=200 && Math.abs(n.getZ())<=200,"节点坐标超出画布范围");
   if("DATA".equals(n.getScope())) {require(LAYERS.contains(n.getLayer()),"请选择数据阶段");if(List.of("ODS","DWD","DWS","DWT","ADS").contains(n.getLayer()))require("Hive".equalsIgnoreCase(n.getEngine()),"当前方案五层都在 Hive；Doris 查询副本请使用 DORIS 阶段");}
   require(n.getDescription()==null || n.getDescription().length()<=2000,"节点说明最多2000字");
   require(n.getAddress()==null || n.getAddress().length()<=256,"地址最多256字；不要填写凭据");
  }
  for(var n:d.getNodes()) if(n.getDeploymentId()!=null && !n.getDeploymentId().isBlank()) {
   var host=nodes.get(n.getDeploymentId());require("DATA".equals(n.getScope()) && host!=null && "DEPLOYMENT".equals(host.getScope()),"数据节点必须关联有效部署节点");
  }
  Set<String> ids=new HashSet<>(),pairs=new HashSet<>();Map<String,List<String>> processing=new HashMap<>();
  for(var e:d.getEdges()) {
   identifier(e.getId());require(ids.add(e.getId()),"连线ID重复");
   var a=nodes.get(e.getSource());var b=nodes.get(e.getTarget());require(a!=null && b!=null && a!=b,"连线端点无效或指向自身");
   require(a.getScope().equals(b.getScope()),"跨视图关联请使用节点的部署关联字段");
   require(pairs.add(e.getSource()+"|"+e.getTarget()+"|"+e.getKind()),"重复连线");
   require(Set.of("PLANNED","REGISTERED","UNKNOWN").contains(e.getState()),"连线状态无效");
   require(e.getLabel()==null || e.getLabel().length()<=128,"连线说明最多128字");
   if("DEPLOYMENT".equals(a.getScope()))require("NETWORK".equals(e.getKind()),"部署视图使用部署/网络连接");
   else if("DIMENSION".equals(e.getKind())) require("DWT".equals(a.getLayer()) && "DWT".equals(b.getLayer()),"星型关系只能连接 DWT 主题内节点");
   else {
    require("PROCESS".equals(e.getKind()),"数据视图使用加工/同步连线或星型关系");
    require(LAYERS.indexOf(b.getLayer())==LAYERS.indexOf(a.getLayer())+1,"禁止跨层连线："+a.getLayer()+" → "+b.getLayer()+"；请按业务源→ODS→DWD→DWS→DWT→ADS→Doris→API→应用连接");
    processing.computeIfAbsent(a.getId(),k->new ArrayList<>()).add(b.getId());
   }
  }
 }
 private static void identifier(String id) {require(id!=null && id.matches("[A-Za-z0-9_-]{1,100}"),"节点/连线 ID 只允许字母、数字、下划线和短横线");}
}
