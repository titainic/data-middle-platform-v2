package tech.qiantong.qdata.module.dp.warehouse;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseQualityVerdictTest {
    private Map<String,Object> row(long id,long total,long hits){return Map.of("evaluate_id",String.valueOf(id),"total",total,"problem_total",hits);}
    @Test void oneCriticalHitBlocksEvenWhenTheOverallScoreWouldBeHigh() {
        var verdict=WarehouseQualityVerdict.evaluate(List.of(row(1,5000,1),row(2,5000,0)),Set.of(1L,2L),Set.of(1L),5000);
        assertFalse(verdict.passed());assertEquals(1,verdict.blockingHits());
    }
    @Test void advisoryHitsRemainVisibleWithoutBypassingCriticalRules() {
        var verdict=WarehouseQualityVerdict.evaluate(List.of(row(1,5000,0),row(2,5000,100)),Set.of(1L,2L),Set.of(1L),5000);
        assertTrue(verdict.passed());assertEquals(100,verdict.ruleHits());
    }
    @Test void missingDuplicateAndEmptyChecksCannotPass() {
        assertFalse(WarehouseQualityVerdict.evaluate(List.of(row(1,5000,0)),Set.of(1L,2L),Set.of(1L),5000).passed());
        assertFalse(WarehouseQualityVerdict.evaluate(List.of(row(1,5000,0),row(1,5000,0)),Set.of(1L),Set.of(1L),5000).passed());
        assertFalse(WarehouseQualityVerdict.evaluate(List.of(row(1,0,0)),Set.of(1L),Set.of(1L),5000).passed());
        assertFalse(WarehouseQualityVerdict.evaluate(List.of(row(1,2,3)),Set.of(1L),Set.of(1L),2).passed());
    }
}
