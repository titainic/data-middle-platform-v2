package tech.qiantong.qdata.common.database;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.dialect.HiveDialect;
import tech.qiantong.qdata.common.database.query.AbstractDbQueryFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HivePreviewCountRegressionTest {
    private final DbQueryProperty source=new DbQueryProperty("Hive","example","hadoop","",10000,"qdata_test",null);
    private AbstractDbQueryFactory query(){var query=mock(AbstractDbQueryFactory.class,CALLS_REAL_METHODS);query.setDbDialect(new HiveDialect());doReturn(7).when(query).countNew(anyString(),anyMap());return query;}
    @Test void filterIsAppendedAfterQualifyingTheTable(){var query=query();assertEquals(7,query.countNew("dwd_iot_db.water_quality",source,"ph_value >= 0"));verify(query).countNew(eq("`dwd_iot_db`.`water_quality` where ph_value >= 0"),anyMap());}
    @Test void unfilteredLegacyNameStillUsesDefaultDatabase(){var query=query();assertEquals(7,query.countNew("water_quality",source,null));verify(query).countNew(eq("`qdata_test`.`water_quality`"),anyMap());}
    @Test void malformedTableCannotHideInsideCountFilter(){var query=query();assertThrows(IllegalArgumentException.class,()->query.countNew("dwd_iot_db.water_quality; SELECT 1",source,"1=1"));verify(query,never()).countNew(anyString(),anyMap());}
}
