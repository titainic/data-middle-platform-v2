package tech.qiantong.qdata.module.da.service.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatasourceReferenceGuardTest {
    boolean uses(String text,long id)throws Exception{return DatasourceReferenceGuard.references(new ObjectMapper().readTree(text),id);}
    @Test void nativeNodeNumericDatasource()throws Exception{assertTrue(uses("{\"datasourceId\":9}",9));}
    @Test void serviceSourceStringId()throws Exception{assertTrue(uses("{\"sourceId\":\"9\"}",9));}
    @Test void nestedSourceAndTarget()throws Exception{assertTrue(uses("{\"nodes\":[{\"reader\":{\"source_datasource_id\":9}}]}",9));assertTrue(uses("{\"writer\":{\"targetDatasourceId\":9}}",9));}
    @Test void legacyDatasourceKey()throws Exception{assertTrue(uses("{\"datasource\":9}",9));}
    @Test void serializedParametersAreExamined()throws Exception{assertTrue(uses("{\"parameters\":\"{\\\"datasourceId\\\":9}\"}",9));}
    @Test void architectureReferenceChecksType()throws Exception{assertTrue(uses("{\"refType\":\"datasource\",\"refId\":9}",9));assertFalse(uses("{\"refType\":\"model\",\"refId\":9}",9));}
    @Test void unrelatedFieldsAndNumbersAreNotReferences()throws Exception{assertFalse(uses("{\"id\":9,\"description\":\"datasourceId:9\",\"sourceId\":19,\"port\":9}",9));}
    @Test void fractionalAndOverflowNumbersDoNotTruncate()throws Exception{assertFalse(uses("{\"sourceId\":9.5}",9));assertFalse(uses("{\"sourceId\":18446744073709551625}",9));}
    @Test void nullAndEmptyDocumentsAreAllowed()throws Exception{assertFalse(uses("null",9));assertFalse(uses("{}",9));assertFalse(uses("[]",9));}
    @Test void malformedNestedParametersCannotSilentlyPass(){assertThrows(IllegalArgumentException.class,()->uses("{\"parameters\":\"{bad\"}",9));}
}
