package tech.qiantong.qdata.module.dp.service.model;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import tech.qiantong.qdata.module.dp.api.dataElem.dto.DpDataElemAssetRelReqDTO;
import tech.qiantong.qdata.module.dp.dal.dataobject.dataElem.DpDataElemAssetRelDO;
import tech.qiantong.qdata.module.dp.service.dataElem.IDpDataElemAssetRelService;
import tech.qiantong.qdata.module.dp.service.model.impl.DpModelServiceImpl;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DataElementColumnScopeTest {
 private DpModelServiceImpl service(IDpDataElemAssetRelService relations) throws Exception {
  var s=new DpModelServiceImpl();var f=DpModelServiceImpl.class.getDeclaredField("iDpDataElemAssetRelService");f.setAccessible(true);f.set(s,relations);return s;
 }
 @Test void omittedBindingsDoNotDeleteExistingFieldStandards() throws Exception {
  var relations=mock(IDpDataElemAssetRelService.class);var dto=new DpDataElemAssetRelReqDTO();dto.setAssetId(52L);dto.setColumnId(1001L);
  assertTrue(service(relations).updateElementAssetRelation(dto));verifyNoInteractions(relations);
 }
 @Test void explicitFieldUpdateIsScopedToBothAssetAndColumn() throws Exception {
  var relations=mock(IDpDataElemAssetRelService.class);
  @SuppressWarnings("unchecked") LambdaUpdateChainWrapper<DpDataElemAssetRelDO> chain=mock(LambdaUpdateChainWrapper.class,RETURNS_SELF);
  when(relations.lambdaUpdate()).thenReturn(chain);when(chain.eq(any(),any())).thenReturn(chain);when(relations.save(any())).thenReturn(true);
  var dto=new DpDataElemAssetRelReqDTO();dto.setAssetId(52L);dto.setColumnId(1001L);dto.setColumnName("device_id");dto.setElementIds(Set.of(42L));
  assertTrue(service(relations).updateElementAssetRelation(dto));verify(chain).eq(any(),eq(52L));verify(chain).eq(any(),eq(1001L));verify(chain).remove();
  verify(relations).save(argThat(row->"52".equals(row.getAssetId()) && "1001".equals(row.getColumnId()) && "42".equals(row.getDataElemId())));
 }
 @Test void missingColumnCannotClearAnEntireAsset() throws Exception {
  var relations=mock(IDpDataElemAssetRelService.class);var dto=new DpDataElemAssetRelReqDTO();dto.setAssetId(52L);dto.setElementIds(Set.of());
  assertThrows(IllegalArgumentException.class,()->service(relations).updateElementAssetRelation(dto));verifyNoInteractions(relations);
 }
}
