package tech.qiantong.qdata.common.utils.file;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Streaming CSV validation: encoding, complete records, unique headers and exact row widths. */
public final class StrictCsv {
    private StrictCsv() {}
    public record Summary(List<String> columns,long rows) {}
    public static Summary read(InputStream input,List<String> required,BiConsumer<List<String>,List<String>> consumer) {
        return read(input,required,consumer,Long.MAX_VALUE);
    }
    public static Summary read(InputStream input,List<String> required,BiConsumer<List<String>,List<String>> consumer,long maxRows) {
        if(maxRows<0)throw new IllegalArgumentException("CSV记录容量配置无效");
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(var reader=new PushbackReader(new BufferedReader(new InputStreamReader(input,decoder)),1)){
            int first=reader.read();if(first!=-1 && first!=0xfeff)reader.unread(first);
            try(CSVParser parser=CSVFormat.DEFAULT.withIgnoreEmptyLines(true).parse(reader)){
                var records=parser.iterator();if(!records.hasNext())throw new IllegalArgumentException("CSV缺少表头");
                List<String> names=new ArrayList<>();Set<String> seen=new HashSet<>();
                for(String raw:records.next()){
                    String name=raw.trim();if(name.isEmpty())throw new IllegalArgumentException("CSV表头名称不能为空");
                    if(!seen.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("CSV表头名称不能重复");names.add(name);
                }
                names=List.copyOf(names);
                if(required!=null)for(String name:required)if(name==null || !seen.contains(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("CSV缺少已配置字段："+name);
                long count=0;
                while(records.hasNext()){
                    var record=records.next();
                    if(record.size()!=names.size())throw new IllegalArgumentException("CSV第"+record.getRecordNumber()+"条记录的字段数量与表头不一致");
                    if(count>=maxRows)throw new IllegalArgumentException("CSV记录数超过已声明容量："+maxRows);
                    if(consumer!=null){List<String> values=new ArrayList<>(record.size());record.forEach(values::add);consumer.accept(names,values);}count++;
                }
                return new Summary(names,count);
            }
        }catch(IOException | IllegalStateException failure){throw new IllegalArgumentException("CSV无法解析，请检查UTF-8编码、引号与完整记录",failure);}
    }
}
