package tech.qiantong.qdata.module.da.service.asset.impl;
import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.da.service.assetColumn.impl.DaAssetColumnServiceImpl;
import tech.qiantong.qdata.module.da.dal.mapper.assetColumn.DaAssetColumnMapper;
import tech.qiantong.qdata.module.da.dal.dataobject.assetColumn.DaAssetColumnDO;
import tech.qiantong.qdata.module.dp.api.service.model.IDpModelApiService;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AssetColumnStandardLookupTest {
 @Test void fieldDetailsLoadOnlyThatAssetsFieldStandards() throws Exception {
  var service=new DaAssetColumnServiceImpl();var mapper=mock(DaAssetColumnMapper.class);var models=mock(IDpModelApiService.class);
  var f=DaAssetColumnServiceImpl.class.getDeclaredField("daAssetColumnMapper");f.setAccessible(true);f.set(service,mapper);
  f=DaAssetColumnServiceImpl.class.getDeclaredField("iDpModelApiService");f.setAccessible(true);f.set(service,models);
  var column=new DaAssetColumnDO();column.setId(1001L);column.setAssetId(52L);when(mapper.selectById(1001L)).thenReturn(column);when(models.getDpDataElemListByAssetIdAndColumnId(52L,1001L)).thenReturn(Set.of(42L));
  assertEquals(Set.of(42L),service.getDaAssetColumnById(1001L).getElementId());verify(models).getDpDataElemListByAssetIdAndColumnId(52L,1001L);verify(models,never()).getDpDataElemListByAssetIdApi(any());
 }
}
