package tech.qiantong.qdata.module.dp.warehouse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.module.da.api.service.asset.IDaAssetApiOutService;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseCatalogGuardTest {
    @Test void failedOrUnauthorizedBatchCannotWriteAssetAssociations() {
        var batches=mock(WarehouseBatchRegistry.class);var jdbc=mock(JdbcTemplate.class);
        var transaction=mock(TransactionTemplate.class);var assets=mock(IDaAssetApiOutService.class);
        var service=new WarehouseCatalogService(batches,jdbc,transaction,assets);
        when(batches.get("failed")).thenReturn(Map.of("status","FAILED"));
        assertThrows(IllegalArgumentException.class,()->service.link("failed"));
        when(batches.get("other-project")).thenThrow(new IllegalArgumentException("No project access"));
        assertThrows(IllegalArgumentException.class,()->service.catalog("other-project"));
        verifyNoInteractions(jdbc,transaction,assets);
    }
    @Test void incompleteCollectionDoesNotStartPartialAssetLinking() {
        var batches=mock(WarehouseBatchRegistry.class);var jdbc=mock(JdbcTemplate.class);
        var transaction=mock(TransactionTemplate.class);var assets=mock(IDaAssetApiOutService.class);
        var service=spy(new WarehouseCatalogService(batches,jdbc,transaction,assets));
        when(batches.get("batch")).thenReturn(Map.of("status","SUCCESS"));
        doReturn(List.of(Map.of("metadataId",10L,"assetId",99L),Map.of("assetId",100L))).when(service).catalog("batch");
        when(transaction.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        assertThrows(IllegalArgumentException.class,()->service.link("batch"));
        verifyNoInteractions(assets);
    }
}
