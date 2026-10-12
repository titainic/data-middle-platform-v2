package tech.qiantong.qdata.common.utils.poi;

import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.annotation.Excel;
import tech.qiantong.qdata.common.exception.UtilException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExcelExportFailureTest {
    private ExcelUtil<Object> unavailableFonts() {
        return new ExcelUtil<>(Object.class) {
            @Override public void init(List<Object> rows, String sheet, String title, Excel.Type type) {
                throw new IllegalStateException("Fontconfig head is null");
            }
        };
    }

    @Test void initializationFailureLeavesResponseAvailableForJsonError() {
        var response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class,
                () -> unavailableFonts().exportExcel(response, List.of(), "quality"));
        assertNull(response.getContentType());
        assertFalse(response.isCommitted());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test void templateInitializationFailureAlsoPreservesErrorResponse() {
        var response = new MockHttpServletResponse();
        assertThrows(IllegalStateException.class,
                () -> unavailableFonts().importTemplateExcel(response, "quality"));
        assertNull(response.getContentType());
        assertFalse(response.isCommitted());
    }

    @Test void writeFailurePropagatesAndDiscardsUncommittedPartialFile() throws Exception {
        var response = new MockHttpServletResponse();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.getOutputStream().write(new byte[]{'P', 'K'});
        Workbook workbook = mock(Workbook.class);
        doThrow(new IOException("simulated output failure")).when(workbook).write(any(OutputStream.class));
        ExcelUtil<Object> util = new ExcelUtil<>(Object.class) {
            @Override public void writeSheet() { }
        };
        ReflectionTestUtils.setField(util, "wb", workbook);
        assertThrows(UtilException.class, () -> util.exportExcel(response));
        assertEquals("application/json", response.getContentType());
        assertEquals(0, response.getContentAsByteArray().length);
        verify(workbook).close();
    }

    @Test void committedWriteFailureStillPropagatesWithoutClaimingJsonSuccess() throws Exception {
        var response = new MockHttpServletResponse();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.flushBuffer();
        Workbook workbook = mock(Workbook.class);
        doThrow(new IOException("simulated disconnected client")).when(workbook).write(any(OutputStream.class));
        ExcelUtil<Object> util = new ExcelUtil<>(Object.class) {
            @Override public void writeSheet() { }
        };
        ReflectionTestUtils.setField(util, "wb", workbook);
        assertThrows(UtilException.class, () -> util.exportExcel(response));
        assertTrue(response.getContentType().contains("spreadsheetml"));
        verify(workbook).close();
    }
}
