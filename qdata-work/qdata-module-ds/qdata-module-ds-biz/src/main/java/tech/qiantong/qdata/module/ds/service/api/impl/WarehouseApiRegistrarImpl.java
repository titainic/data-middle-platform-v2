package tech.qiantong.qdata.module.ds.service.api.impl;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.ds.api.api.WarehouseApiRegistrar;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.ExecuteConfig;
import tech.qiantong.qdata.module.ds.handler.MappingHandlerMapping;
import tech.qiantong.qdata.module.ds.service.api.IDsApiService;
import java.util.*;

/** Keep warehouse services visible in the native service catalog and route registry. */
@Service
@RequiredArgsConstructor
public class WarehouseApiRegistrarImpl implements WarehouseApiRegistrar {
    private final ObjectProvider<IDsApiService> services;
    private final JdbcTemplate jdbc;
    private final MappingHandlerMapping mappings;
    @Override public long register(String name,String path,int version,long source,String database,String table,List<Map<String,Object>> columns) {
        var categories=jdbc.queryForList("SELECT id,code FROM att_api_cat WHERE del_flag='0' ORDER BY id LIMIT 1");
        if(categories.isEmpty())throw new IllegalArgumentException("请先建立数据服务类目");
        var ownership=jdbc.queryForList("SELECT DISTINCT s.project_id FROM dp_warehouse_serving_product p JOIN dp_warehouse_space s ON s.id=p.space_id WHERE p.table_name=?",Long.class,table);
        if(ownership.size()!=1)throw new IllegalArgumentException("服务产品缺少唯一的仓库项目归属");
        var api=new DsApiDO();api.setProjectId(ownership.get(0));api.setName(name);api.setApiUrl(path);api.setApiVersion("v"+version+".0.0");api.setReqMethod("1");api.setApiServiceType("2");api.setResDataType("3");api.setStatus("0");api.setValidFlag(true);api.setDelFlag(false);
        api.setCatId(((Number)categories.get(0).get("id")).longValue());api.setCatCode(String.valueOf(categories.get(0).get("code")));
        api.setDescription("数仓固定版本服务：以已通过质量门禁的发布清单提供数据，支持固定版本分页。");api.setCreatorId(SecurityUtils.getUserId());api.setCreateBy(SecurityUtils.getUsername());api.setCreateTime(new Date());
        describe(api,columns);
        var config=new ExecuteConfig();config.setSourceId(String.valueOf(source));config.setApiServiceType("2");config.setDbName(database);config.setDbType("Doris");config.setTableName(table);
        config.setSqlText("SELECT "+String.join(",",columns.stream().map(column->"`"+column.get("name")+"`").toList())+" FROM `"+database+"`.`"+table+"` WHERE `_qdata_release_id` = :releaseId ORDER BY `_qdata_row_number`");
        api.setExecuteConfig(config);api.setConfigJson(JSON.toJSONString(config));
        services.getObject().saveDataApi(api);
        if(api.getId()==null || api.getId()<=0)throw new IllegalStateException("服务登记未成功");
        return api.getId();
    }
    static void describe(DsApiDO api,List<? extends Map<String,?>> columns) {
        Set<String> names=new HashSet<>();columns.forEach(column->names.add(String.valueOf(column.get("name"))));
        List<String> parameters=new ArrayList<>(List.of("pageNum","pageSize","releaseId"));
        if(names.contains("event_date"))parameters.addAll(List.of("dateFrom","dateTo"));
        if(names.contains("location_code"))parameters.add("locationCode");
        if(names.contains("device_id"))parameters.add("deviceId");
        List<Map<String,Object>> requests=new ArrayList<>();
        for(String field:parameters)requests.add(Map.of("paramName",field,"paramType",field.startsWith("page")?"2":"1","nullable","1"));
        api.setReqParams(JSON.toJSONString(requests));
        List<Map<String,Object>> responses=new ArrayList<>();
        for(var column:columns)responses.add(Map.of("fieldName",column.get("name"),"paramName",column.get("name"),"fieldAliasName",column.get("name"),"fieldComment",column.get("comment")==null?column.get("name"):column.get("comment"),"dataType",column.get("type")));
        api.setResParams(JSON.toJSONString(responses));
    }
    @Override public void activate(long id) {
        var api=services.getObject().getDsApiById(id);if(api==null)throw new IllegalArgumentException("服务登记不存在");
        String path="/services/"+api.getApiVersion()+"/"+api.getApiUrl().replaceFirst("^/+","");
        var registered=MappingHandlerMapping.getMappingApiInfo(MappingHandlerMapping.buildMappingKey(api.getReqMethod(),path));
        if(registered!=null && !Objects.equals(registered.getId(),id))throw new IllegalArgumentException("服务路径已被其他接口占用");
        var schemas=jdbc.queryForList("SELECT schema_json FROM dp_warehouse_serving_product WHERE api_id=?",String.class,id);
        if(!schemas.isEmpty()) {
            describe(api,JSON.parseArray(schemas.get(0),com.alibaba.fastjson2.JSONObject.class));
            jdbc.update("UPDATE ds_api SET req_params=?,res_params=? WHERE id=? AND del_flag='0'",api.getReqParams(),api.getResParams(),id);
        }
        if(registered==null)mappings.registerMapping(api);
        jdbc.update("UPDATE ds_api SET status='1' WHERE id=? AND del_flag='0'",id);
    }
}
