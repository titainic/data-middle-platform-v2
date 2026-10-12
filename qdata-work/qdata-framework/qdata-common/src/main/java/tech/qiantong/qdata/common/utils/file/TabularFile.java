package tech.qiantong.qdata.common.utils.file;

import org.apache.poi.ss.usermodel.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** File-asset contract: one-based header row and first data row; CSV uses its first record as header. */
public final class TabularFile {
    private TabularFile() {}
    public record Snapshot(List<String> columns, List<Map<String,Object>> rows) {}

    public static Snapshot read(Path path, Integer headerRow, Integer dataRow) {
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if(name.endsWith(".csv")) return csv(path);
            if(name.endsWith(".xls") || name.endsWith(".xlsx")) return excel(path,headerRow,dataRow);
            throw new IllegalArgumentException("文件预览仅支持CSV、XLS和XLSX");
        } catch (IOException | org.apache.poi.EncryptedDocumentException failure) {
            throw new IllegalArgumentException("文件内容无法解析，请检查UTF-8编码、引号及表格格式",failure);
        }
    }
    private static Snapshot csv(Path path) throws IOException {
        List<Map<String,Object>> rows=new ArrayList<>();
        var summary=StrictCsv.read(Files.newInputStream(path),List.of(),(names,values)->{
            Map<String,Object> row=new LinkedHashMap<>();for(int c=0;c<names.size();c++)row.put(names.get(c),values.get(c));rows.add(row);
        });
        return new Snapshot(summary.columns(),List.copyOf(rows));
    }
    private static Snapshot excel(Path path,Integer headerRow,Integer dataRow) throws IOException {
        int header=headerRow==null?1:headerRow;int start=dataRow==null?header+1:dataRow;
        if(header<1 || start<=header)throw new IllegalArgumentException("Excel数据开始行必须位于表头行之后");
        try(var input=Files.newInputStream(path);Workbook book=WorkbookFactory.create(input)) {
            if(book.getNumberOfSheets()==0)throw new IllegalArgumentException("Excel没有工作表");
            Sheet sheet=book.getSheetAt(0);Row first=sheet.getRow(header-1);
            if(first==null || first.getLastCellNum()<1)throw new IllegalArgumentException("Excel表头行为空");
            DataFormatter formatter=new DataFormatter(Locale.ROOT);List<String> names=new ArrayList<>();
            for(int c=0;c<first.getLastCellNum();c++)names.add(formatter.formatCellValue(first.getCell(c)));
            names=headers(names);List<Map<String,Object>> rows=new ArrayList<>();
            for(int r=start-1;r<=sheet.getLastRowNum();r++) {
                Row source=sheet.getRow(r);if(source==null || source.getLastCellNum()<1)continue;
                for(int c=names.size();c<source.getLastCellNum();c++)if(!formatter.formatCellValue(source.getCell(c)).isEmpty())
                    throw new IllegalArgumentException("Excel第"+(r+1)+"行包含表头以外的数据列");
                Map<String,Object> row=new LinkedHashMap<>();for(int c=0;c<names.size();c++)row.put(names.get(c),formatter.formatCellValue(source.getCell(c)));rows.add(row);
            }
            return new Snapshot(List.copyOf(names),List.copyOf(rows));
        }
    }
    private static List<String> headers(List<String> raw) {
        if(raw.isEmpty())throw new IllegalArgumentException("文件缺少表头");List<String> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String value:raw){String name=value.trim();if(name.isEmpty())throw new IllegalArgumentException("表头名称不能为空");if(!seen.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("表头名称不能重复");result.add(name);}return result;
    }
}
