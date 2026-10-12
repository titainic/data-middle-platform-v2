package tech.qiantong.qdata.quality.controller.health;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityHealthControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final QualityHealthController controller = new QualityHealthController(jdbc);

    @Test void healthyDatabaseReturnsOnlyUp() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        var response = controller.health();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals(1, response.getBody().size());
    }

    @Test void unavailableDatabaseReturnsServiceUnavailableWithoutDetails() {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("private connection details"));
        var response = controller.health();
        assertEquals(503, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals(1, response.getBody().size());
    }
}
