package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.core.PageResult;
import tech.qiantong.qdata.module.dp.api.warehouse.WarehouseServingGateway;
import tech.qiantong.qdata.module.dp.api.warehouse.ServingRequestException;
import tech.qiantong.qdata.redis.service.IRedisService;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** Prepared parameters, version-pinned pages and per-client scope checks for published products. */
@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class WarehouseServingQuery implements WarehouseServingGateway {
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=ServiceRowSerializer.class)
    public static class ServiceRow extends LinkedHashMap<String,Object> {
        public ServiceRow(){} public ServiceRow(Map<String,Object> values){super(values);}
    }
    public static class ServiceRowSerializer extends com.fasterxml.jackson.databind.JsonSerializer<ServiceRow> {
        @Override public void serialize(ServiceRow row,com.fasterxml.jackson.core.JsonGenerator output,com.fasterxml.jackson.databind.SerializerProvider provider)throws java.io.IOException {
            output.writeStartObject();for(var entry:row.entrySet()){output.writeFieldName(entry.getKey());provider.defaultSerializeValue(entry.getValue(),output);}output.writeEndObject();
        }
    }
    private final JdbcTemplate jdbc;
    private final WarehouseServingService serving;
    private final WarehouseSpaceService spaces;
    private final DataSourceFactory factory;
    private final IRedisService redis;
    private final ServingDependencyGuard dependencies = new ServingDependencyGuard();
    @Getter public static class PublishedPage extends PageResult<Map<String,Object>> implements tech.qiantong.qdata.module.dp.api.warehouse.PublishedPageMetadata {
        private final String releaseId;
        private final int contractVersion;
        PublishedPage(int total,List<Map<String,Object>> data,String release,int version){super(total,data);releaseId=release;contractVersion=version;}
    }
    @Override public boolean handles(long id){return jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_serving_product WHERE api_id=?",Long.class,id)>0;}
    @Override public Object query(long apiId,String clientId,Map<String,Object> parameters){check(clientId!=null && clientId.matches("[0-9]+"),401,"应用身份无效");return validatedQuery(apiId,clientId,parameters,false);}
    @Override public Object preview(long apiId,Map<String,Object> parameters){return validatedQuery(apiId,"admin",parameters,true);}
    private Object validatedQuery(long apiId,String clientId,Map<String,Object> parameters,boolean preview) {
        try{return query(apiId,clientId,parameters,preview);}
        catch(ServingRequestException failure){throw failure;}
        catch(IllegalArgumentException | java.time.DateTimeException failure){throw new ServingRequestException(400,"查询参数无效："+failure.getMessage());}
        catch(Exception failure){throw new ServingRequestException(503,"服务暂时不可用，请稍后重试");}
    }
    private Object query(long apiId,String clientId,Map<String,Object> parameters,boolean preview) {
        var product=jdbc.queryForMap("SELECT * FROM dp_warehouse_serving_product WHERE api_id=?",apiId);
        long spaceId=((Number)product.get("space_id")).longValue();int version=((Number)product.get("contract_version")).intValue();
        if(preview)spaces.authorize(spaceId);
        else {
            var now=Timestamp.from(java.time.Instant.now());
            check(jdbc.queryForObject("SELECT COUNT(*) FROM att_client c JOIN att_client_api_rel r ON r.client_id=c.id JOIN ds_api a ON a.id=r.api_id WHERE c.id=? AND a.id=? AND c.valid_flag='1' AND c.del_flag='0' AND r.status='1' AND r.valid_flag='1' AND r.del_flag='0' AND a.status='1' AND a.del_flag='0' AND (r.pv_flag='1' OR (r.start_time<=? AND r.end_time>=?))",Long.class,clientId,apiId,now,now)>0,403,"应用授权已失效");
            check(redis.allowRequest("warehouse:service:limit:"+clientId+":"+apiId,100,60),429,"请求过于频繁，请稍后重试");
        }
        Map<String,Object> params=parameters==null?Map.of():parameters;
        require(Set.of("pageNum","pageSize","releaseId","dateFrom","dateTo","locationCode","deviceId").containsAll(params.keySet()),"存在不支持的查询参数");
        int page=positive(params.get("pageNum"),1,100000),size=positive(params.get("pageSize"),20,200);
        String release=text(params.get("releaseId"));
        if(release==null) {
            require(page==1,"翻页请携带首屏返回的releaseId，以固定同一数据版本");
            var heads=jdbc.queryForList("SELECT release_id FROM dp_warehouse_serving_head WHERE space_id=? AND contract_version=?",String.class,spaceId,version);
            check(heads.size()==1,503,"服务尚无已发布数据版本");release=heads.get(0);
        }
        UUID.fromString(release);
        require(jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_serving_release r JOIN dp_warehouse_serving_release_product p ON p.release_id=r.id WHERE r.id=? AND r.space_id=? AND r.contract_version=? AND r.ever_published=TRUE AND r.status='PUBLISHED' AND p.dataset_id=? AND p.status='VERIFIED'",Long.class,release,spaceId,version,product.get("dataset_id"))==1,"请求的数据版本未发布或不属于本服务");
        var columns=JSON.parseArray(String.valueOf(product.get("schema_json")),WarehouseDefinition.Column.class);Set<String> names=new HashSet<>();columns.forEach(column->names.add(column.getName()));
        List<String> locations=List.of(),allowedColumns=List.of(),maskedColumns=List.of();
        if(!preview) {
            var configured=jdbc.queryForList("SELECT locations_json,columns_json,masked_columns_json FROM dp_warehouse_serving_scope WHERE client_id=? AND space_id=?",clientId,spaceId);
            if(!configured.isEmpty()) {
                locations=JSON.parseArray(String.valueOf(configured.get(0).get("locations_json")),String.class);
                allowedColumns=JSON.parseArray(String.valueOf(configured.get(0).get("columns_json")),String.class);
                maskedColumns=JSON.parseArray(String.valueOf(configured.get(0).get("masked_columns_json")),String.class);
            }
        }
        var selectedColumns=selectColumns(columns,allowedColumns);final Set<String> masked=new HashSet<>(maskedColumns);
        check(locations.isEmpty() || names.contains("location_code"),403,"当前产品不能按应用的地点范围提供结果");
        StringBuilder where=new StringBuilder(" WHERE `_qdata_release_id`=?");List<Object> values=new ArrayList<>();values.add(release);
        if(!locations.isEmpty()){where.append(" AND `location_code` IN (").append(String.join(",",Collections.nCopies(locations.size(),"?"))).append(")");values.addAll(locations);}
        String from=text(params.get("dateFrom")),to=text(params.get("dateTo"));
        if(from!=null || to!=null) {
            require(names.contains("event_date"),"当前产品不支持按观测日期筛选");
            if(from!=null){LocalDate.parse(from);where.append(" AND `event_date`>=?");values.add(from);}
            if(to!=null){LocalDate.parse(to);where.append(" AND `event_date`<=?");values.add(to);}
            require(from==null || to==null || from.compareTo(to)<=0,"日期范围无效");
        }
        for(var filter:Map.of("locationCode","location_code","deviceId","device_id").entrySet()) {
            String value=text(params.get(filter.getKey()));if(value==null)continue;
            check(!masked.contains(filter.getValue()) && (allowedColumns.isEmpty() || allowedColumns.contains(filter.getValue())),403,"应用无权使用该筛选字段");
            require(names.contains(filter.getValue()) && value.length()<=128,"筛选字段或值无效");
            where.append(" AND ").append(WarehouseSql.id(filter.getValue())).append("=?");values.add(value);
        }
        String cacheKey="warehouse:service:cache:"+WarehouseDataFingerprint.sha(JSON.toJSONString(List.of(apiId,clientId,release,page,size,locations,allowedColumns,maskedColumns,new TreeMap<>(params))));
        String cached=redis.get(cacheKey);
        if(cached!=null) {
            var saved=JSON.parseObject(cached);
            List<Map<String,Object>> restored=new ArrayList<>();
            for(int i=0;i<saved.getJSONArray("data").size();i++)restored.add(new ServiceRow(saved.getJSONArray("data").getJSONObject(i)));
            var result=new PublishedPage(saved.getIntValue("total"),restored,release,version);
            result.setPageNum(page).setPageSize(size);return result;
        }
        var target=serving.target(spaceId);long sourceId=((Number)target.get("datasource_id")).longValue();
        try(var permit=dependencies.acquire(sourceId)) {
        tech.qiantong.qdata.common.database.DbQuery query=null;
        try {
        query=factory.createDbQuery(serving.property(sourceId,15000));
        String table=WarehouseSql.qualified(String.valueOf(target.get("database_name")),String.valueOf(product.get("table_name")));
        try(Connection connection=query.getConnection()) {
            int total;
            try(PreparedStatement count=connection.prepareStatement("SELECT COUNT(*) FROM "+table+where)){bind(count,values);count.setQueryTimeout(15);try(ResultSet result=count.executeQuery()){result.next();total=Math.toIntExact(result.getLong(1));}}
            List<Map<String,Object>> data=new ArrayList<>();
            try(PreparedStatement statement=connection.prepareStatement("SELECT "+WarehouseServingService.fields(selectedColumns)+" FROM "+table+where+" ORDER BY `_qdata_row_number` LIMIT ? OFFSET ?")) {
                List<Object> paging=new ArrayList<>(values);paging.add(size);paging.add(Math.multiplyExact(page-1,size));bind(statement,paging);statement.setQueryTimeout(15);
                try(ResultSet result=statement.executeQuery()){while(result.next()){Map<String,Object> row=new ServiceRow();for(int i=0;i<selectedColumns.size();i++){var column=selectedColumns.get(i);row.put(column.getName(),masked.contains(column.getName())?null:ServingRowCodec.response(result.getString(i+1),column.getType()));}data.add(row);}}
            }
            permit.succeeded();
            redis.set(cacheKey,JSON.toJSONString(Map.of("total",total,"data",data),com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue),30);
            var result=new PublishedPage(total,data,release,version);result.setPageNum(page).setPageSize(size);return result;
        }
        } catch(Exception failure){
            permit.failed();
            log.warn("Published query dependency failure apiId={} datasourceId={} type={}",apiId,sourceId,failure.getClass().getSimpleName());
            throw new ServingRequestException(503,"已发布服务查询失败，请稍后重试");
        } finally {if(query!=null)query.close();}
        }
    }
    private static void bind(PreparedStatement statement,List<Object> values) throws SQLException {for(int i=0;i<values.size();i++)statement.setObject(i+1,values.get(i));}
    static List<WarehouseDefinition.Column> selectColumns(List<WarehouseDefinition.Column> columns,List<String> allowed){var selected=allowed.isEmpty()?columns:columns.stream().filter(column->allowed.contains(column.getName())).toList();check(!selected.isEmpty(),403,"应用没有当前产品的可见字段");return selected;}
    private static void require(boolean valid,String message){check(valid,400,message);}
    private static void check(boolean valid,int status,String message){if(!valid)throw new ServingRequestException(status,message);}
    private static String text(Object value){return value==null || value.toString().isBlank()?null:value.toString();}
    private static int positive(Object value,int fallback,int maximum){if(value==null)return fallback;String text=value.toString();require(text.matches("[0-9]+"),"分页参数必须是正整数");int number=Integer.parseInt(text);require(number>=1 && number<=maximum,"分页参数超出范围");return number;}
}
