package tech.qiantong.qdata.module.dp.architecture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureRulesTest {
 @Test void allowsFiveHiveLayersThenDorisApi(){var d=doc();String[] layers={"SOURCE","ODS","DWD","DWS","DWT","ADS","DORIS","API","APP"};for(int i=0;i<layers.length;i++){d.getNodes().add(node("n"+i,layers[i],"DATA"));if(i>0)d.getEdges().add(edge("n"+(i-1),"n"+i,"PROCESS"));}assertDoesNotThrow(()->ArchitectureRules.validate(d));}
 @Test void rejectsLayerSkipping(){var d=doc();d.getNodes().add(node("a","ODS","DATA"));d.getNodes().add(node("b","DWS","DATA"));d.getEdges().add(edge("a","b","PROCESS"));assertThrows(IllegalArgumentException.class,()->ArchitectureRules.validate(d));}
 @Test void rejectsDorisInHiveWarehouse(){var d=doc();var n=node("a","DWD","DATA");n.setEngine("Doris");d.getNodes().add(n);assertThrows(IllegalArgumentException.class,()->ArchitectureRules.validate(d));}
 @Test void allowsThemeRelationship(){var d=doc();d.getNodes().add(node("a","DWT","DATA"));d.getNodes().add(node("b","DWT","DATA"));d.getEdges().add(edge("a","b","DIMENSION"));assertDoesNotThrow(()->ArchitectureRules.validate(d));}
 @Test void rejectsInvalidDeploymentReference(){var d=doc();var n=node("a","ODS","DATA");n.setDeploymentId("missing");d.getNodes().add(n);assertThrows(IllegalArgumentException.class,()->ArchitectureRules.validate(d));}
 @Test void rejectsDanglingEdge(){var d=doc();d.getNodes().add(node("a","ODS","DATA"));d.getEdges().add(edge("a","missing","PROCESS"));assertThrows(IllegalArgumentException.class,()->ArchitectureRules.validate(d));}
 @Test void rejectsDuplicateNodes(){var d=doc();d.getNodes().add(node("a","ODS","DATA"));d.getNodes().add(node("a","DWD","DATA"));assertThrows(IllegalArgumentException.class,()->ArchitectureRules.validate(d));}
 @Test void rejectsNonFinitePosition(){var d=doc();var n=node("a","ODS","DATA");n.setX(Double.NaN);d.getNodes().add(n);assertThrows(IllegalArgumentException.class,()->ArchitectureRules.validate(d));}
 @Test void rejectsCrossViewEdge(){var d=doc();d.getNodes().add(node("a","","DEPLOYMENT"));d.getNodes().add(node("b","ODS","DATA"));d.getEdges().add(edge("a","b","NETWORK"));assertThrows(IllegalArgumentException.class,()->ArchitectureRules.validate(d));}
 private ArchitectureDocument doc(){var d=new ArchitectureDocument();d.setName("架构验收");return d;}
 private ArchitectureDocument.Node node(String id,String layer,String scope){var n=new ArchitectureDocument.Node();n.setId(id);n.setLabel(id);n.setKind("TABLE");n.setScope(scope);n.setLayer(layer);n.setEngine("Hive");return n;}
 private ArchitectureDocument.Edge edge(String from,String to,String kind){var e=new ArchitectureDocument.Edge();e.setId(from+"-"+to);e.setSource(from);e.setTarget(to);e.setKind(kind);return e;}
}
