package tech.qiantong.qdata.quality.controller.health;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** A small readiness response for the standalone quality service. */
@RestController
@RequiredArgsConstructor
public class QualityHealthController {
    private final JdbcTemplate jdbc;

    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class))) {
                return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
            }
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (RuntimeException unavailable) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
        }
    }
}
