package tech.qiantong.qdata.module.ds.handler;

import com.alibaba.fastjson2.JSON;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tech.qiantong.qdata.module.ds.async.AsyncTask;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;
import tech.qiantong.qdata.module.ds.dal.dataobject.apiLog.DsApiLogDO;
import tech.qiantong.qdata.module.dp.api.warehouse.PublishedPageMetadata;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceCallAudit {
    private final org.springframework.beans.factory.ObjectProvider<AsyncTask> audit;
    public static String parameters(Map<String,?> parameters,Object result) {
        Map<String,Object> safe=new LinkedHashMap<>();
        for(String key:List.of("pageNum","pageSize","releaseId","dateFrom","dateTo","locationCode","deviceId")) {
            Object value=parameters.get(key);if(value instanceof String text)safe.put(key,text.substring(0,Math.min(text.length(),256)));else if(value instanceof Number || value instanceof Boolean)safe.put(key,value);
        }
        if(result instanceof PublishedPageMetadata metadata){safe.put("effectiveReleaseId",metadata.getReleaseId());safe.put("contractVersion",metadata.getContractVersion());}
        return JSON.toJSONString(safe);
    }
    public void denied(HttpServletRequest request,DsApiDO api,int status,String message) {
        if(api==null)return;
        var record=new DsApiLogDO();record.setApiId(api.getId());record.setCatId(api.getCatId());record.setCatCode(api.getCatCode());record.setCallerStartDate(java.time.LocalDateTime.now());record.setCallerTime(0L);record.setCallerSize(0);record.setStatus(0);
        record.setCallerUrl(request.getRequestURI());record.setCallerIp(request.getRemoteAddr());record.setCallerId(Objects.toString(request.getAttribute("qdata.clientId"),"anonymous"));record.setCallerBy("应用鉴权拒绝");record.setMsg("HTTP "+status+" "+message);
        Map<String,Object> values=new HashMap<>();request.getParameterMap().forEach((key,value)->{if(value.length==1)values.put(key,value[0]);});record.setCallerParams(parameters(values,null));
        try{audit.getObject().doTask(record);}catch(RuntimeException unavailable){log.warn("Service denial audit unavailable: api={}, status={}, failure={}",api.getId(),status,unavailable.getClass().getSimpleName());}
    }
}
