package tech.qiantong.qdata.module.dp.api.warehouse;

import java.util.Map;

/** Native data-service execution delegates only registered warehouse products to the serving manifest. */
public interface WarehouseServingGateway {
    boolean handles(long apiId);
    Object query(long apiId,String clientId,Map<String,Object> parameters);
    Object preview(long apiId,Map<String,Object> parameters);
}
