package tech.qiantong.qdata.common.utils.file;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TabularFileTest {
    @TempDir Path directory;
    Path csv(String text)throws Exception{Path p=directory.resolve("water.csv");Files.writeString(p,text);return p;}
    @Test void quotedNewlineCommaAndEscapedQuotesRemainOneRecord()throws Exception{var data=TabularFile.read(csv("sensor_id,note,value\nwater_1,\"水质,\"\"复核\"\"\n次日\",7.10\n"),1,2);assertEquals(List.of("sensor_id","note","value"),data.columns());assertEquals(1,data.rows().size());assertEquals("水质,\"复核\"\n次日",data.rows().get(0).get("note"));assertEquals("7.10",data.rows().get(0).get("value"));}
    @Test void utf8BomDoesNotBecomePartOfHeader()throws Exception{assertEquals("sensor_id",TabularFile.read(csv("\ufeffsensor_id,value\n空气_1,12\n"),1,2).columns().get(0));}
    @Test void valuesRetainSignPrecisionWhitespaceAndCrossMonthDates()throws Exception{var data=TabularFile.read(csv("sample_time,value,note\n2026-08-31T23:59:59+08:00,-0.000,  water  \n2026-09-01T00:00:00+08:00,0.005,soil\n"),1,2);assertEquals("-0.000",data.rows().get(0).get("value"));assertEquals("  water  ",data.rows().get(0).get("note"));assertEquals("2026-09-01T00:00:00+08:00",data.rows().get(1).get("sample_time"));}
    @Test void headerOnlyIsAValidEmptyDataset()throws Exception{var data=TabularFile.read(csv("sensor_id,value\n"),1,2);assertEquals(2,data.columns().size());assertTrue(data.rows().isEmpty());}
    @ParameterizedTest @ValueSource(strings={"sensor_id,value\nwater\n","sensor_id,value\nwater,1,extra\n","sensor_id,value\nwater,\"unterminated\n","sensor_id,SENSOR_ID\n1,2\n","sensor_id,\n1,2\n",""}) void malformedInputFailsExplicitly(String content)throws Exception{Path file=csv(content);assertThrows(IllegalArgumentException.class,()->TabularFile.read(file,1,2));}
    @Test void invalidUtf8IsRejected()throws Exception{Path p=directory.resolve("soil.csv");Files.write(p,new byte[]{(byte)0xff,(byte)0xfe,0x61});assertThrows(IllegalArgumentException.class,()->TabularFile.read(p,1,2));}
    @ParameterizedTest @ValueSource(strings={"xls","xlsx"}) void excelUsesExplicitHeaderAndFirstDataRows(String extension)throws Exception{
        Path file=directory.resolve("air."+extension);try(Workbook book=extension.equals("xls")?new HSSFWorkbook():new XSSFWorkbook()){
            var sheet=book.createSheet();sheet.createRow(0).createCell(0).setCellValue("Air measurements");var header=sheet.createRow(1);header.createCell(0).setCellValue("sensor_id");header.createCell(1).setCellValue("sample_time");header.createCell(2).setCellValue("value");sheet.createRow(2).createCell(0).setCellValue("skip note");var a=sheet.createRow(3);a.createCell(0).setCellValue("air_1");a.createCell(1).setCellValue("2026-08-31 23:59:59");a.createCell(2).setCellValue("12.005");var b=sheet.createRow(5);b.createCell(0).setCellValue("soil_2");b.createCell(2).setCellValue("0");try(var out=Files.newOutputStream(file)){book.write(out);}
        }
        var data=TabularFile.read(file,2,4);assertEquals(2,data.rows().size());assertEquals("air_1",data.rows().get(0).get("sensor_id"));assertEquals("",data.rows().get(1).get("sample_time"));assertEquals("12.005",data.rows().get(0).get("value"));
    }
    @Test void excelDataCannotStartBeforeHeader()throws Exception{Path file=directory.resolve("invalid.xlsx");Files.write(file,new byte[0]);assertThrows(IllegalArgumentException.class,()->TabularFile.read(file,2,2));}
    @Test void previewAndSchemaUseTheSameCsvRecords()throws Exception{Path file=csv("sensor_id,note\nwater,\"a\nb\"\nair,x\n");var page=FileDataReaderUtil.previewAssetFile(file.toString(),2L,1L,1,2,null);assertEquals(2L,page.get("total"));assertEquals("air",((List<Map<String,Object>>)page.get("tableData")).get(0).get("sensor_id"));}
}
