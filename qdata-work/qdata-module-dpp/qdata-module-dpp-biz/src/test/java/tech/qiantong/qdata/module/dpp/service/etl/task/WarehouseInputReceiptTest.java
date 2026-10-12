package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseInputReceiptTest {
    @TempDir Path directory;
    @Test void countsCsvRecordsIncludingQuotedNewlinesWithoutCountingTheHeader() throws Exception {
        Path file=directory.resolve("input.csv");Files.writeString(file,"id,note\n1,\"first\nsecond\"\n2,\"quoted \"\"text\"\"\"\n",StandardCharsets.UTF_8);
        assertEquals(2,WarehouseTaskGatewayImpl.countCsv(file));
        String before=WarehouseTaskGatewayImpl.sha256(file);assertEquals(before,WarehouseTaskGatewayImpl.sha256(file));
        Files.writeString(file,"3,additional\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        assertEquals(3,WarehouseTaskGatewayImpl.countCsv(file));assertNotEquals(before,WarehouseTaskGatewayImpl.sha256(file));
    }
    @Test void headerOnlyCsvIsAnExplicitEmptyBatch() throws Exception {
        Path file=directory.resolve("empty.csv");Files.writeString(file,"id,note\n",StandardCharsets.UTF_8);assertEquals(0,WarehouseTaskGatewayImpl.countCsv(file));
    }

    @Test void malformedRowCannotReceiveAValidReceiptCount() throws Exception {Path file=directory.resolve("bad.csv");Files.writeString(file,"id,note\n001,ok,EXTRA\n");assertThrows(IllegalArgumentException.class,()->WarehouseTaskGatewayImpl.countCsv(file));}
    @Test void rawReaderRejectsImplicitBusinessTypeConversion(){var reader=com.alibaba.fastjson2.JSON.parseObject("{\"columns\":[{\"columnName\":\"device_id\",\"type\":\"int\"}]}");assertThrows(IllegalArgumentException.class,()->WarehouseTaskGatewayImpl.requireRawReader(reader));}
    @Test void originalStringReaderContractRemainsValid(){var reader=com.alibaba.fastjson2.JSON.parseObject("{\"columns\":[{\"columnName\":\"device_id\",\"type\":\"string\"},{\"columnName\":\"reading\",\"columnType\":\"STRING\"}]}");assertDoesNotThrow(()->WarehouseTaskGatewayImpl.requireRawReader(reader));}
}
