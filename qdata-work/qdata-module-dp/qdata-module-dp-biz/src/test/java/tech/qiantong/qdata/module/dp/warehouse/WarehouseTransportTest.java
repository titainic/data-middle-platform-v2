package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.utils.AesEncryptUtil;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseTransportTest {
    @Test void unicodeAndSqlSurviveTheExistingPlatformCodec() throws Exception {
        var source = new WarehouseDefinition(); source.setName("水质明细"); source.setSpaceId(1L);
        source.setDatabaseName("dwd_iot_db"); source.setTableName("water_quality");
        source.setSelectSql("SELECT ph_value FROM ods_iot_db.water_quality WHERE ph_value < 14");
        var request = new WarehouseTransport.EncodedDefinition();
        request.setPayload(AesEncryptUtil.encrypt(JSON.toJSONString(source)));
        var decoded = WarehouseTransport.decode(request);
        assertEquals(source.getName(), decoded.getName()); assertEquals(source.getSelectSql(), decoded.getSelectSql());
        assertEquals(source.getDatabaseName(), decoded.getDatabaseName()); assertEquals(source.getSpaceId(), decoded.getSpaceId());
    }
    @Test void malformedOrOversizedPayloadsAreRejected() {
        var request = new WarehouseTransport.EncodedDefinition(); request.setPayload("not-a-model-payload");
        assertThrows(IllegalArgumentException.class, () -> WarehouseTransport.decode(request));
        request.setPayload("x".repeat(2*1024*1024+1));
        assertThrows(IllegalArgumentException.class, () -> WarehouseTransport.decode(request));
    }
    @Test void decodedNullCannotReachModelOperations() throws Exception {
        var request = new WarehouseTransport.EncodedDefinition(); request.setPayload(AesEncryptUtil.encrypt("null"));
        assertThrows(IllegalArgumentException.class, () -> WarehouseTransport.decode(request));
    }
}
