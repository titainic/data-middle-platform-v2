package tech.qiantong.qdata.module.da.service.asset;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HiveMetadataAssetLinkerTest {
    @Test void acceptsExistingVarcharMetadataForeignKeysWithoutReplacingThem() {
        assertEquals(1804L, HiveMetadataAssetLinker.metadataId("1804"));
        assertEquals(1804L, HiveMetadataAssetLinker.metadataId(1804L));
        assertNull(HiveMetadataAssetLinker.metadataId(null));
        assertThrows(NumberFormatException.class, () -> HiveMetadataAssetLinker.metadataId("unknown"));
    }
    private Map<String,Object> column(String name, String type) { return Map.of("column_name", name, "column_type", type); }
    @Test void metadataOrderAndCaseDoNotRequireReplacingAssetColumns() {
        assertDoesNotThrow(() -> HiveMetadataAssetLinker.requireSameSchema(
                List.of(column("event_time", "timestamp"), column("ph", "decimal(4, 2)")),
                List.of(column("PH", "DECIMAL(4,2)"), column("event_time", "TIMESTAMP"))));
    }
    @Test void lossOfPrecisionOrChangedFieldIdentityBlocksAssociation() {
        assertThrows(IllegalArgumentException.class, () -> HiveMetadataAssetLinker.requireSameSchema(
                List.of(column("ph", "DECIMAL(4,2)")), List.of(column("ph", "DECIMAL(4,1)"))));
        assertThrows(IllegalArgumentException.class, () -> HiveMetadataAssetLinker.requireSameSchema(
                List.of(column("ph", "DECIMAL(4,2)")), List.of(column("other", "DECIMAL(4,2)"))));
    }
    @Test void missingOrAmbiguousColumnsDoNotProduceAPartialAssociation() {
        assertThrows(IllegalArgumentException.class, () -> HiveMetadataAssetLinker.requireSameSchema(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> HiveMetadataAssetLinker.requireSameSchema(
                List.of(column("ph", "STRING"), column("PH", "STRING")), List.of(column("ph", "STRING"))));
    }
}
