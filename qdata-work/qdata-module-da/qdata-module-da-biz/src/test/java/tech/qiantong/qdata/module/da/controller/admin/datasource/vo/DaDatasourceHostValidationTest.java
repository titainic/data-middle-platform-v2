package tech.qiantong.qdata.module.da.controller.admin.datasource.vo;

import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DaDatasourceHostValidationTest {
    private static String expression() throws Exception {
        return DaDatasourceSaveReqVO.class.getDeclaredField("ip").getAnnotation(Pattern.class).regexp();
    }
    @Test void internalSingleLabelHostnamesAreAccepted() throws Exception {
        String expression=expression();
        for(String host:new String[]{"bigdata-hdfs-4","localhost","hive1","a"})
            assertTrue(java.util.regex.Pattern.matches(expression,host),host);
        for(String host:new String[]{"-hive","hive-","123","bad_name","bad host","http://hive"})
            assertFalse(java.util.regex.Pattern.matches(expression,host),host);
    }
    @Test void existingIpAndQualifiedDomainFormsRemainAccepted() throws Exception {
        String expression=expression();
        assertTrue(java.util.regex.Pattern.matches(expression,"10.222.1.14"));
        assertTrue(java.util.regex.Pattern.matches(expression,"hive.cluster.local"));
    }
}
