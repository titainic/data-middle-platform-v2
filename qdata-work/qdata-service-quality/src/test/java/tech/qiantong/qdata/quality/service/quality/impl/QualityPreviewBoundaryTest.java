package tech.qiantong.qdata.quality.service.quality.impl;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.quality.controller.quality.vo.QualityRuleQueryReqDTO;
import static org.junit.jupiter.api.Assertions.*;

class QualityPreviewBoundaryTest {
    @Test void previewRejectsAnotherTableAndWriteStatements(){var property=new DbQueryProperty("Hive","test","hadoop","",10000,"qdata_test",null);assertThrows(IllegalArgumentException.class,()->QualityTaskExecutorServiceImpl.verifyPreviewSql(property,"dwd_iot_db.water_quality","SELECT * FROM other_project.water_quality"));assertThrows(IllegalArgumentException.class,()->QualityTaskExecutorServiceImpl.verifyPreviewSql(property,"dwd_iot_db.water_quality","DELETE FROM dwd_iot_db.water_quality"));}
    @Test void declaredReadIsAllowedWithoutAnyConnection(){var property=new DbQueryProperty("Hive","test","hadoop","",10000,"qdata_test",null);assertDoesNotThrow(()->QualityTaskExecutorServiceImpl.verifyPreviewSql(property,"dwd_iot_db.water_quality","SELECT * FROM dwd_iot_db.water_quality WHERE ph_value<0"));}
    @Test void previewPaginationIsBoundedAndIgnoresForgedOffsets(){var request=new QualityRuleQueryReqDTO();request.setPageNum(2);request.setPageSize(10);request.setOffset(-100);request.setLimit(999999);QualityTaskExecutorServiceImpl.validatePreviewPage(request);assertEquals(10,request.getOffset());assertEquals(10,request.getLimit());request.setPageSize(201);assertThrows(IllegalArgumentException.class,()->QualityTaskExecutorServiceImpl.validatePreviewPage(request));}
}
