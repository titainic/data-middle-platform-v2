package tech.qiantong.qdata.common.utils;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.database.dialect.HiveDialect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class HiveImportRegressionTest {
    @TempDir Path directory;

    @Test
    void paginationUsesAHiveCompatibleAliasForExistingLimitAndOffset() {
        HiveDialect dialect = new HiveDialect();
        assertEquals("SELECT * FROM ( SELECT id FROM observations LIMIT 5 ) qdata_page LIMIT 2 OFFSET 1",
                dialect.buildPaginationSql("SELECT id FROM observations LIMIT 5;", 1, 2));
        assertEquals("SELECT id FROM observations LIMIT 2",
                dialect.buildPaginationSql("SELECT id FROM observations;", 0, 2));
    }

    @Test
    void excelImportHandlesMissingRowsAndUtf8() throws Exception {
        Path excel = directory.resolve("input.xlsx");
        Path csv = directory.resolve("output.csv");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("note");
            var data = sheet.createRow(2); // Missing physical row between header and data.
            data.createCell(0).setCellValue(1);
            data.createCell(1).setCellValue("中文,引号\"内容");
            try (var stream = Files.newOutputStream(excel)) { workbook.write(stream); }
        }
        assertThrows(tech.qiantong.qdata.common.exception.ServiceException.class,
                () -> ExcelToCsvUtil.convertExcelToCsv(excel.toString(), csv.toString(), 1, 1));
        assertEquals(List.of("id", "note"), ExcelToCsvUtil.convertExcelToCsv(excel.toString(), csv.toString(), 1, 2));
        assertEquals(List.of("id,note", "1,\"中文,引号\"\"内容\""), Files.readAllLines(csv));
    }

    private DbColumn column(String name, String type) {
        DbColumn column = new DbColumn(); column.setColName(name); column.setDataType(type); return column;
    }

    @Test
    @SuppressWarnings("unchecked")
    void hiveDescribeReturnsPartitionColumnExactlyOnce() throws Exception {
        // DESCRIBE's visible shape for a partitioned table includes a repeated partition section.
        List<DbColumn> raw = List.of(column("id", "int"), column("dt", "string"),
                column("", ""), column("# Partition Information", ""),
                column("# col_name", "data_type"), column("dt", "string"));
        Method method = HiveDialect.class.getMethod("normalizeColumns", List.class);
        List<DbColumn> normalized = (List<DbColumn>) method.invoke(null, raw);
        assertEquals(List.of("id", "dt"), normalized.stream().map(DbColumn::getColName).toList());
        assertEquals(2, normalized.get(1).getColPosition());
    }
}
