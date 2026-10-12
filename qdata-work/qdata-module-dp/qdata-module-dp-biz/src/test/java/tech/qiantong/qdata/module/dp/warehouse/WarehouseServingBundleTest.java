package tech.qiantong.qdata.module.dp.warehouse;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.ds.api.api.WarehouseApiRegistrar;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WarehouseServingBundleTest {
    JdbcTemplate jdbc;WarehouseBatchRegistry batches;WarehouseQualityService quality;
    WarehouseServingService service;DataSourceFactory factory;WarehouseApiRegistrar apis;
    List<Map<String,Object>> expected,products;
    Map<String,Object> product(long dataset,long model,int version){return new HashMap<>(Map.of("dataset_id",dataset,"model_id",model,"contract_version",version,"status","VERIFIED","api_id",dataset+100));}
    @BeforeEach void setup(){
        jdbc=mock(JdbcTemplate.class);batches=mock(WarehouseBatchRegistry.class);quality=mock(WarehouseQualityService.class);factory=mock(DataSourceFactory.class);apis=mock(WarehouseApiRegistrar.class);
        service=spy(new WarehouseServingService(jdbc,mock(TransactionTemplate.class),mock(WarehouseSpaceService.class),batches,quality,mock(WarehouseService.class),mock(IDaDatasourceApiService.class),factory,apis,mock(IRedisService.class)));
        expected=new ArrayList<>();products=new ArrayList<>();for(long id=1;id<=4;id++){expected.add(new HashMap<>(Map.of("dataset_id",id,"model_id",id+10,"status","SUCCESS")));products.add(product(id,id+10,1));}
        doReturn(Map.of("space_id",1L,"contract_version",1,"status","VERIFIED","batch_id","batch","assessment_id","assessment")).when(service).get("release");
        when(batches.get("batch")).thenReturn(Map.of("space_id",1L,"status","SUCCESS"));when(quality.get("assessment")).thenReturn(Map.of("status","PASSED","batch_id","batch"));
        when(jdbc.queryForList(startsWith("SELECT r.*,p.api_id"),eq(1L),eq("release"))).thenAnswer(i->products);
        when(jdbc.queryForList(startsWith("SELECT dataset_id,model_id,status FROM dp_warehouse_batch_step"),eq("batch"))).thenAnswer(i->expected);
        when(jdbc.queryForList(startsWith("SELECT dataset_id FROM dp_warehouse_serving_product"),eq(Long.class),eq(1L),eq(1))).thenReturn(List.of(1L,2L,3L,4L));
        doThrow(new IllegalStateException("REACHED_DORIS_RECONCILIATION")).when(service).target(1L);
    }
    void refused(String message){assertTrue(assertThrows(IllegalArgumentException.class,()->service.publish("release")).getMessage().contains(message));verify(service,never()).target(anyLong());verifyNoInteractions(factory,apis);verify(jdbc,never()).update(anyString(),any(Object[].class));}
    @Test void threeVerifiedProductsCannotPublishFourProductContract(){products.remove(3);refused("未包含");}
    @Test void foreignProductCannotReplaceMissingProduct(){products.set(3,product(5,15,1));refused("不一致");}
    @Test void productCannotBorrowAnotherBatchModel(){products.get(3).put("model_id",99L);refused("模型版本");}
    @Test void productCannotMixContractVersions(){products.get(3).put("contract_version",2);refused("契约版本");}
    @Test void duplicateProductsCannotSatisfyCount(){products.set(3,product(1,11,1));refused("不一致");}
    @Test void incompleteContractRegistryRefusesPublication(){when(jdbc.queryForList(startsWith("SELECT dataset_id FROM dp_warehouse_serving_product"),eq(Long.class),eq(1L),eq(1))).thenReturn(List.of(1L,2L,3L));refused("集合不完整");}
    @Test void unfinishedProductRefusesPublication(){products.get(3).put("status","SYNCING");refused("尚未全部就绪");}
    @Test void incompleteBatchRefusesPublication(){when(batches.get("batch")).thenReturn(Map.of("space_id",1L,"status","FAILED"));refused("未全部成功");}
    @Test void inconsistentSuccessSummaryCannotHideFailedAds(){expected.get(3).put("status","FAILED");refused("ADS产品尚未全部成功");}
    @Test void assessmentMustBelongToReleaseBatch(){when(quality.get("assessment")).thenReturn(Map.of("status","PASSED","batch_id","other"));refused("不属于发布来源批次");}
    @Test void completeBundleContinuesToDorisReconciliation(){assertEquals("REACHED_DORIS_RECONCILIATION",assertThrows(IllegalStateException.class,()->service.publish("release")).getMessage());verify(service).target(1L);verifyNoInteractions(apis);}
    @Test void failedBatchCannotStartPartialSync(){when(batches.get("batch")).thenReturn(Map.of("space_id",1L,"status","FAILED"));assertTrue(assertThrows(IllegalArgumentException.class,()->service.synchronize("batch","assessment",1,true)).getMessage().contains("整批加工成功"));verifyNoInteractions(quality,factory,apis);}
}
