package tech.qiantong.qdata.module.dpp.controller.admin.etl;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tech.qiantong.qdata.module.dpp.service.etl.task.WarehouseNativeDolphinService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WarehouseDolphinCallbackControllerTest {
    private static final String BATCH = "b5291988-2bfc-41ee-abf8-55532ef66497";

    @Test void failedOrUnauthorizedNodeNeverLooksSuccessfulToDolphinScheduler() {
        var service = mock(WarehouseNativeDolphinService.class);
        var controller = new WarehouseDolphinCallbackController(service);
        doThrow(new SecurityException()).when(service).executeNode(BATCH, 0, "invalid");
        doThrow(new IllegalStateException()).when(service).executeNode(BATCH, 1, "failed");
        assertEquals(HttpStatus.NOT_FOUND, controller.execute(BATCH, 0, "{\"token\":\"invalid\"}").getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.execute(BATCH, 1, "{\"token\":\"failed\"}").getStatusCode());
        assertEquals(HttpStatus.OK, controller.execute(BATCH, 2, "{\"token\":\"valid\"}").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.execute(BATCH, 2, "{\"wrong\":\"valid\"}").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.execute(BATCH, 2, "not-json").getStatusCode());
    }
}
