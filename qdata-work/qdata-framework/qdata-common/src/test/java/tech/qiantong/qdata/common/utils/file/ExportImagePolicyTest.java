package tech.qiantong.qdata.common.utils.file;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import tech.qiantong.qdata.common.annotation.Excel;
import tech.qiantong.qdata.common.config.AniviaConfig;
import tech.qiantong.qdata.common.utils.poi.ExcelUtil;
import static org.junit.jupiter.api.Assertions.*;

class ExportImagePolicyTest {
    @TempDir Path temporary;

    static class ImageRow {
        @Excel(name="图片", cellType=Excel.ColumnType.IMAGE) public String picture;
    }

    @Test void blockedExternalImageDoesNotBreakWorkbookOrOtherCells() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet("fixture").createRow(0);
            var imageCell = row.createCell(0);
            var attr = ImageRow.class.getDeclaredField("picture").getAnnotation(Excel.class);
            new ExcelUtil<>(ImageRow.class).setCellVo("http://127.0.0.1:1/a.png", attr, imageCell);
            row.createCell(1).setCellValue("保留业务内容");
            assertTrue(workbook.getAllPictures().isEmpty());
            assertEquals("保留业务内容", row.getCell(1).getStringCellValue());
            try (var bytes = new java.io.ByteArrayOutputStream()) {
                workbook.write(bytes);
                assertTrue(bytes.size() > 0);
            }
        }
    }

    @Test void onlyReadsFilesInsideManagedUploadDirectory() throws Exception {
        String previous = AniviaConfig.getProfile();
        try {
            Path uploads = Files.createDirectory(temporary.resolve("uploads"));
            new AniviaConfig().setProfile(uploads.toString());
            byte[] image = {1,2,3}; Files.write(uploads.resolve("picture.png"), image);
            Path outside = Files.write(temporary.resolve("outside.png"), new byte[]{4,5});
            Files.createSymbolicLink(uploads.resolve("escape.png"), outside);
            assertArrayEquals(image, ImageUtils.getImage("/profile/picture.png"));
            for (String value : new String[]{"http://127.0.0.1:1/a.png", "https://unregistered.invalid/a.png", "file:///etc/hosts", "//unregistered.invalid/a", "/profile/../outside.png", "/profile/escape.png", "/profile/%2e%2e/outside.png", outside.toString(), null})
                assertNull(ImageUtils.getImage(value), String.valueOf(value));
        } finally { new AniviaConfig().setProfile(previous); }
    }
}
