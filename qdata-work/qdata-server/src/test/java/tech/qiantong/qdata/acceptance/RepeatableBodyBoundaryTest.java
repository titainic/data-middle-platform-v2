package tech.qiantong.qdata.acceptance;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.qiantong.qdata.common.filter.RepeatedlyRequestWrapper;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RepeatableBodyBoundaryTest {
    @Test void jsonBodyRemainsReadableWithCorrectStreamState() throws Exception {
        byte[] body = "{\n  \"value\": 1\n}".getBytes(StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", "/dpp/etlTask/createProcessDefinitionEx");
        request.setContentType("application/json");
        request.setContent(body);
        var wrapped = new RepeatedlyRequestWrapper(request, new MockHttpServletResponse());
        var stream = wrapped.getInputStream();
        assertTrue(stream.isReady());
        assertFalse(stream.isFinished());
        assertEquals(body.length, stream.available());
        assertArrayEquals(body, stream.readAllBytes());
        assertTrue(stream.isFinished());
        assertEquals(0, stream.available());
        assertArrayEquals(body, wrapped.getInputStream().readAllBytes());
    }

    @Test void interruptedUploadDoesNotBecomePartialJson() {
        var request = new MockHttpServletRequest("POST", "/dpp/etlTask/updateProcessDefinition") {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    private boolean firstRead = true;
                    @Override public int read() throws IOException {
                        if (firstRead) {
                            firstRead = false;
                            return '{';
                        }
                        throw new IOException("client disconnected");
                    }
                    @Override public boolean isFinished() { return false; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener readListener) {}
                };
            }
        };
        request.setContentType("application/json");
        assertThrows(IOException.class, () -> new RepeatedlyRequestWrapper(request, new MockHttpServletResponse()));
    }

    @Test void prematureEndOfKnownLengthIsRejected() {
        var request = new MockHttpServletRequest("POST", "/dpp/etlTask/updateProcessDefinition") {
            @Override public long getContentLengthLong() { return 10; }
        };
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(EOFException.class, () -> new RepeatedlyRequestWrapper(request, new MockHttpServletResponse()));
    }
}
