package tech.qiantong.qdata.module.dp.warehouse;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseApiTimeTest {
    final LocalDateTime utc=LocalDateTime.parse("2026-09-21T15:47:27.406518");
    @Test void metadataCarriesAnExplicitUtcOffsetWithoutLosingMicroseconds(){
        var result=(Map<?,?>)WarehouseApiTime.metadata(Map.of("started_at",utc));assertEquals("2026-09-21T15:47:27.406518Z",result.get("started_at"));
    }
    @Test void nestedStepsAreCopiedWhileFrozenJsonAndInputObjectsStayUnchanged(){
        String frozen="{\"created_at\":\"2024-01-01T01:00:00\"}";var step=new LinkedHashMap<String,Object>();step.put("finished_at",utc);var input=Map.of("steps",List.of(step),"definition_json",frozen);
        var result=(Map<?,?>)WarehouseApiTime.metadata(input);var mapped=(Map<?,?>)((List<?>)result.get("steps")).get(0);
        assertEquals("2026-09-21T15:47:27.406518Z",mapped.get("finished_at"));assertEquals(utc,step.get("finished_at"));assertEquals(frozen,result.get("definition_json"));assertNotSame(step,mapped);
    }
    @Test void businessDatesAndHiveTimestampsAreNotRelabelled(){
        var date=LocalDate.of(2024,2,29);var timestamp=java.sql.Timestamp.valueOf("2024-02-29 23:59:59");
        var result=(Map<?,?>)WarehouseApiTime.metadata(Map.of("window_start",date,"event_time",utc,"created_at",timestamp));
        assertSame(date,result.get("window_start"));assertSame(utc,result.get("event_time"));assertSame(timestamp,result.get("created_at"));
    }
    @Test void intakeUsesItsOwnVerifiedLocalClock(){
        var result=(Map<?,?>)WarehouseApiTime.intake(Map.of("end_time",LocalDateTime.parse("2026-09-21T23:47:27.406518"),"created_at",utc));
        assertEquals("2026-09-21T23:47:27.406518+08:00",result.get("end_time"));assertSame(utc,result.get("created_at"));
    }
    @Test void alreadyOffsetValuesAreNotShiftedAgain(){
        Object once=WarehouseApiTime.metadata(Map.of("created_at",utc));assertEquals(once,WarehouseApiTime.metadata(once));
    }
    @Test void unfinishedTimesRemainNull(){
        Map<String,Object> input=new LinkedHashMap<>();input.put("started_at",utc);input.put("finished_at",null);
        var result=(Map<?,?>)WarehouseApiTime.metadata(input);assertTrue(result.containsKey("finished_at"));assertNull(result.get("finished_at"));
    }
    @Test void logControllerConvertsSystemTimesButDataPreviewDoesNot(){
        var service=mock(WarehouseService.class);var operations=mock(WarehouseOperationAccess.class);var controller=new WarehouseController(service,mock(WarehouseSpaceService.class),operations);
        when(operations.visibleModels("dp:model:query")).thenReturn(List.of());when(service.logs(null,List.of())).thenReturn(List.of(Map.of("started_at",utc)));
        var logs=(List<?>)controller.logs(null).getData();assertEquals("2026-09-21T15:47:27.406518Z",((Map<?,?>)logs.get(0)).get("started_at"));
        var preview=List.<Map<String,Object>>of(Map.of("created_at",utc));when(service.data(42)).thenReturn(preview);var returned=(List<?>)controller.data(42).getData();assertEquals(preview,returned);assertSame(utc,((Map<?,?>)returned.get(0)).get("created_at"));
    }
    @Test void batchAndIntakeControllerUseDifferentClockContracts(){
        var registry=mock(WarehouseBatchRegistry.class);var executor=mock(WarehouseBatchExecutor.class);var controller=new WarehouseBatchController(registry,executor,mock(WarehouseSpaceService.class),mock(WarehouseCatalogService.class),mock(WarehouseOperationAccess.class));
        when(registry.get("fixture")).thenReturn(Map.of("space_id",1L,"started_at",utc));
        assertEquals("2026-09-21T15:47:27.406518Z",((Map<?,?>)controller.get("fixture").getData()).get("started_at"));verify(executor).reconcile(1L);
        when(registry.inputCandidates(1L)).thenReturn(List.of(Map.of("end_time",utc.plusHours(8))));
        var candidates=(List<?>)controller.inputs(1).getData();assertEquals("2026-09-21T23:47:27.406518+08:00",((Map<?,?>)candidates.get(0)).get("end_time"));
    }
}
