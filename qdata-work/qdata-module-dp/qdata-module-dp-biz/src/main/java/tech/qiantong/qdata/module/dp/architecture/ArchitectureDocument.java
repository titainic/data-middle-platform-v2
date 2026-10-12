package tech.qiantong.qdata.module.dp.architecture;
import lombok.Data;
import java.util.*;

@Data
public class ArchitectureDocument {
 private Long id;
 private Long projectId;
 private boolean canEdit;
 private int revision;
 private String name;
 private List<Node> nodes=new ArrayList<>();
 private List<Edge> edges=new ArrayList<>();
 @Data public static class Node {
  private String id,label,kind,scope,layer,engine,zone,address,description;
  private String state="PLANNED";
  private String refType;
  private Long refId;
  private String deploymentId;
  private double x,z;
 }
 @Data public static class Edge {
  private String id,source,target,kind,label;
  private String state="PLANNED";
  private Long taskId;
 }
}
