package tech.qiantong.qdata.module.dp.service.dataElem.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataElementCompatibilityTest {
    @Test void stringAndIntegerAliasesRemainCompatible() {
        assertTrue(DataElementCompatibility.compatible("VARCHAR","STRING"));
        assertTrue(DataElementCompatibility.compatible("INTEGER","INT"));
    }
    @Test void knownPrecisionAndDifferentTypesCannotBeSilentlyAssociated() {
        assertFalse(DataElementCompatibility.compatible("DECIMAL(8,4)","DECIMAL(8,2)"));
        assertFalse(DataElementCompatibility.compatible("INT","VARCHAR"));
        assertFalse(DataElementCompatibility.compatible("INT","BIGINT"));
        assertTrue(DataElementCompatibility.compatible("DECIMAL","DECIMAL(8,4)"));
    }
}
