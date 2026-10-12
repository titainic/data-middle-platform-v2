package tech.qiantong.qdata.module.ds.controller.admin.api;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.utils.AesEncryptUtil;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import tech.qiantong.qdata.module.ds.dal.mapper.api.DsApiMapper;
import tech.qiantong.qdata.module.ds.service.api.impl.DsApiServiceImpl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class HiveApiRegressionTest {
    @Test void plaintextSqlIsNotDecrypted() {
        assertEquals("SELECT id, amount FROM qdata_test.orders",ReflectionTestUtils.invokeMethod(DsApiController.class,"normalizeTestingSql"," SELECT id, amount FROM qdata_test.orders "));
    }
    @Test void legacyEncryptedSqlStillWorks() throws Exception {
        String sql="SELECT id FROM qdata_test.orders";
        assertEquals(sql,ReflectionTestUtils.invokeMethod(DsApiController.class,"normalizeTestingSql",AesEncryptUtil.encrypt(sql)));
    }
    @Test void optionalForwardApiIdIsNullBeforeInsert() {
        var service=new DsApiServiceImpl();var mapper=mock(DsApiMapper.class);
        ReflectionTestUtils.setField(service,"baseMapper",mapper);
        var api=new DsApiDO();api.setName("Hive JDBC test");api.setApiId("");api.setStatus("0");
        when(mapper.insert(any(DsApiDO.class))).thenAnswer(inv->{DsApiDO row=inv.getArgument(0);assertNull(row.getApiId());row.setId(13L);return 1;});
        ReflectionTestUtils.invokeMethod(service,"dataApiDaoInsert",api);
        verify(mapper).insert(api);
        assertEquals(13L,api.getId());
    }
}
