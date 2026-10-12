package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseInputLineageTest {
    DppEtlNodeRespVO node(String code,String component,Map<String,Object> options){var node=new DppEtlNodeRespVO();node.setCode(code);node.setVersion(1L);node.setComponentType(component);Map<String,Object> params=new LinkedHashMap<>(options);params.put("componentType",component);params.put("taskType","SPARK");node.setParameters(JSON.toJSONString(params));return node;}
    DppEtlNodeRespVO reader(){return node("r","4",Map.of("file","/profile/must-not-leak.csv","columns",List.of(Map.of("columnName","id","type","string"),Map.of("columnName","event_time","type","string"),Map.of("columnName","data_date","type","string"))));}
    DppEtlNodeRespVO derive(){return node("d","39",Map.of("fieldDerivationName","data_date","fieldDerivationType","FIELD_DERIVE_CONCAT","tableFields",List.of(Map.of("columnName","event_time")),"delimiter","","fieldDerivationPrefix","","fieldDerivationSuffix",""));}
    DppEtlNodeRespVO writer(){return node("w","91",Map.of("writeModeType",2,"columns",List.of("id","event_time","data_date"),"target_columns",List.of("id","event_time","data_date"),"writerDatasource",Map.of("password","must-not-leak")));}
    @Test void publishedDateDerivationKeepsItsCsvOriginAndSafeSteps(){
        var clean=node("c","31",Map.of("tableFields",List.of(Map.of("ruleType","DATE_FORMAT_STD","ruleConfig",JSON.toJSONString(Map.of("columns",List.of("data_date"),"targetFormat","yyyy-MM-dd","inputFormats",List.of("yyyy-MM-dd HH:mm:ss")))))));
        var result=WarehouseInputLineage.build(List.of(reader(),derive(),clean,writer()),Map.of("d",Set.of("r"),"c",Set.of("d"),"w",Set.of("c")));
        var date=result.columns().stream().filter(c->c.name().equals("data_date")).findFirst().orElseThrow();assertEquals(List.of("event_time"),date.csvColumns());assertEquals(3,date.steps().size());assertFalse(JSON.toJSONString(result.columns()).contains("must-not-leak"));
    }
    @Test void unusedBranchDoesNotChangeTheActualWritersOrigin(){var result=WarehouseInputLineage.build(List.of(reader(),derive(),writer()),Map.of("d",Set.of("r"),"w",Set.of("r")));assertEquals(List.of("data_date"),result.columns().get(2).csvColumns());}
    @Test void mergedParentsAreRejectedInsteadOfGuessed(){assertThrows(IllegalArgumentException.class,()->WarehouseInputLineage.build(List.of(reader(),derive(),writer()),Map.of("d",Set.of("r"),"w",Set.of("r","d"))));}
    @Test void duplicateCodesWithDifferentVersionsAreNotSilentlyCollapsed(){var duplicate=reader();duplicate.setVersion(2L);assertThrows(IllegalArgumentException.class,()->WarehouseInputLineage.build(List.of(reader(),duplicate,writer()),Map.of("w",Set.of("r"))));}
    @Test void unsupportedDerivationDoesNotPretendToBeAConcat(){var derive=node("d","39",Map.of("fieldDerivationName","data_date","fieldDerivationType","FIELD_DERIVE_SEQUENCE"));assertThrows(IllegalArgumentException.class,()->WarehouseInputLineage.build(List.of(reader(),derive,writer()),Map.of("d",Set.of("r"),"w",Set.of("d"))));}
    @Test void mutableCleaningRuleReferencesCannotClaimAFrozenDefinition(){var clean=node("c","31",Map.of("mainArgs",Map.of("cleanRuleList",List.of(1))));assertThrows(IllegalArgumentException.class,()->WarehouseInputLineage.build(List.of(reader(),clean,writer()),Map.of("c",Set.of("r"),"w",Set.of("c"))));}
    @Test void filteringAddsConditionColumnsToEverySurvivingField(){var clean=node("c","31",Map.of("where","ID IS NOT NULL"));var result=WarehouseInputLineage.build(List.of(reader(),clean,writer()),Map.of("c",Set.of("r"),"w",Set.of("c")));assertTrue(result.columns().stream().allMatch(c->c.conditionColumns().equals(List.of("id"))));}
    @Test void missingPublishedAncestorFailsClearly(){assertThrows(IllegalArgumentException.class,()->WarehouseInputLineage.build(List.of(reader(),writer()),Map.of("w",Set.of("missing"))));}
}
