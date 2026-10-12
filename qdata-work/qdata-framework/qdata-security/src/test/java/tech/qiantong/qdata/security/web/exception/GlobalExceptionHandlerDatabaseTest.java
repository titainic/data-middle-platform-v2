package tech.qiantong.qdata.security.web.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerDatabaseTest {

    @Test
    void recoverableConnectionFailureReturnsSafeServiceUnavailable() {
        var response = new MockHttpServletResponse();
        var result = new GlobalExceptionHandler().handleDatabaseUnavailable(
                new RecoverableDataAccessException("jdbc:mysql://host/db?password=do-not-expose"),
                new MockHttpServletRequest("POST", "/login"), response);

        assertEquals(503, response.getStatus());
        assertEquals(503, result.get("code"));
        assertFalse(String.valueOf(result.get("msg")).contains("do-not-expose"));
    }

    @Test
    void missingDatabaseConnectionReturnsServiceUnavailable() {
        var response = new MockHttpServletResponse();
        var result = new GlobalExceptionHandler().handleDatabaseUnavailable(
                new DataAccessResourceFailureException("connection refused"),
                new MockHttpServletRequest("GET", "/getInfo"), response);

        assertEquals(503, response.getStatus());
        assertEquals(503, result.get("code"));
    }
}
