package tech.qiantong.qdata.module.dpp.controller.admin.etl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.annotation.Anonymous;
import tech.qiantong.qdata.module.dpp.service.etl.task.WarehouseNativeDolphinService;

/** A one-step capability, scoped to one frozen batch node, used by DS Workers only. */
@RestController
@RequiredArgsConstructor
public class WarehouseDolphinCallbackController {
    private final WarehouseNativeDolphinService nativeDolphin;

    @Anonymous
    @PostMapping("/dp/warehouse/ds-internal/{batchId}/{ordinal}")
    public ResponseEntity<String> execute(@PathVariable String batchId, @PathVariable int ordinal, @RequestBody String body) {
        try {
            if (body == null || body.length() > 128) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("INVALID");
            JSONObject params = JSON.parseObject(body);
            if (params == null || params.size() != 1 || !(params.get("token") instanceof String token))
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("INVALID");
            nativeDolphin.executeNode(batchId, ordinal, token);
            return ResponseEntity.ok("OK");
        }
        catch (SecurityException | IllegalArgumentException | JSONException invalid) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body("INVALID"); }
        catch (Exception failed) { return ResponseEntity.status(HttpStatus.CONFLICT).body("FAILED"); }
    }
}
