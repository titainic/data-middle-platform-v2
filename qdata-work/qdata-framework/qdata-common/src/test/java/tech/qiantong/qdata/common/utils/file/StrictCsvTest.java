package tech.qiantong.qdata.common.utils.file;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StrictCsvTest {
    InputStream bytes(String value){return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));}
    @Test void validationScansEveryRowAndRetainsLogicalRecordCount(){StringBuilder text=new StringBuilder("id,value\n");for(int i=0;i<100000;i++)text.append(i).append(",001\n");var result=StrictCsv.read(bytes(text.toString()),List.of("id"),null);assertEquals(100000,result.rows());assertEquals(List.of("id","value"),result.columns());}
    @Test void malformedRecordAfterManyValidRowsIsRejected(){assertThrows(IllegalArgumentException.class,()->StrictCsv.read(bytes("id,value\n001,7.10\n002,8.0,EXTRA\n"),List.of("id"),null));}
    @Test void requiredColumnCannotBeSilentlySkipped(){assertThrows(IllegalArgumentException.class,()->StrictCsv.read(bytes("id,value\n001,7.10\n"),List.of("missing"),null));}
    @Test void invalidBytesAreRejectedBeyondTheHeader(){byte[] prefix="id,value\n001,".getBytes(StandardCharsets.UTF_8);byte[] input=Arrays.copyOf(prefix,prefix.length+1);input[prefix.length]=(byte)0xff;assertThrows(IllegalArgumentException.class,()->StrictCsv.read(new ByteArrayInputStream(input),List.of(),null));}
    @Test void callbackReceivesExactTextIncludingLeadingZero(){var rows=new ArrayList<List<String>>();var result=StrictCsv.read(bytes("id,value\n001,7.10\n"),List.of("ID"),(names,values)->rows.add(values));assertEquals(1,result.rows());assertEquals(List.of("001","7.10"),rows.get(0));}

    @Test void declaredCapacityStopsWithoutSilentlyTruncating(){assertThrows(IllegalArgumentException.class,()->StrictCsv.read(bytes("id,value\n1,a\n2,b\n"),List.of(),null,1));}
}
