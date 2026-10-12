package tech.qiantong.qdata.server.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tech.qiantong.qdata.common.core.domain.CommonResult;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class OverviewController {
    private final JdbcTemplate jdbc;
    private final tech.qiantong.qdata.module.da.api.service.DataResourceAccess resources;
    private final tech.qiantong.qdata.module.dpp.service.qa.QualityResourceAccess qualityResources;
    private record Scope(String where,List<Object> args) {}
    @GetMapping("/home")
    @PreAuthorize("isAuthenticated()")
    public CommonResult<Map<String,Object>> overview() {
        boolean administrator=resources.isAdministrator();
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<String> days=today.minusDays(6).datesUntil(today.plusDays(1)).map(LocalDate::toString).toList();
        String start=days.get(0)+" 00:00:00",end=today.plusDays(1)+" 00:00:00";
        Map<String,Object> totals=new LinkedHashMap<>(),recent=new LinkedHashMap<>(),result=new LinkedHashMap<>();
        boolean integration=permitted("dpp:etlTask:list","dpp:integratioTask:list"),development=permitted("dpp:etlTask:list","dpp:developTask:list");
        boolean apiLogs=SecurityUtils.hasPermi("ds:apiLog:list"),quality=permitted("da:qualityLog:index","dpp:qualityLog:list"),instances=permitted("dpp:etlTaskInstance:list","dpp:etl:etltaskinstance:list","dpp:developTask:instance:list","dpp:integratioTask:instance:list","dpp:integratioInstance:list");
        boolean assets=SecurityUtils.hasPermi("da:asset:list"),apis=SecurityUtils.hasPermi("ds:api:list");
        var projects=!administrator && (integration||development||instances||apis||apiLogs)?resources.visibleProjectIds():Set.<Long>of();
        Scope tasks=administrator?all():ids("project_id",projects);
        Scope taskInstances=administrator?all():new Scope(" AND task_id IN (SELECT id FROM dpp_etl_task WHERE del_flag='0'"+tasks.where()+")",tasks.args());
        Scope assetScope=administrator?all():ids("id",assets?resources.visibleAssetIds():Set.of());
        Scope qualityScope=administrator?all():ids("id",quality?qualityResources.visibleLogs():List.of());
        Scope apiScope=administrator?all():apis?apiScope(projects,false):ids("id",List.of());
        Scope logApis=administrator?all():apiLogs?apiScope(projects,true):ids("id",List.of());
        Scope apiLogScope=administrator?all():new Scope(" AND api_id IN (SELECT id FROM ds_api WHERE 1=1"+logApis.where()+")",logApis.args());
        card(totals,recent,"integration","dpp_etl_task"," AND type=1",tasks,start,end,integration);
        card(totals,recent,"development","dpp_etl_task"," AND type=3",tasks,start,end,development);
        card(totals,recent,"jobs","dpp_etl_task"," AND type=4",tasks,start,end,integration||development);
        card(totals,recent,"assets","da_asset","",assetScope,start,end,assets);
        card(totals,recent,"apis","ds_api","",apiScope,start,end,apis);
        result.put("scope",administrator?"all":"accessible");result.put("totals",totals);result.put("recent",recent);result.put("days",days);
        result.put("apiCalls",daily("ds_api_log","",apiLogScope,start,end,days,apiLogs));
        result.put("qualitySuccess",daily("dpp_quality_log"," AND success_flag='0'",qualityScope,start,end,days,quality));
        result.put("qualityFailure",daily("dpp_quality_log"," AND success_flag='1'",qualityScope,start,end,days,quality));
        result.put("qualityRunning",daily("dpp_quality_log"," AND success_flag='2'",qualityScope,start,end,days,quality));
        List<Map<String,Object>> executions=new ArrayList<>();
        for(var entry:Map.of("integration",1,"development",3,"jobs",4).entrySet()){
            Map<String,Object> row=new LinkedHashMap<>();row.put("key",entry.getKey());
            row.put("success",instances?count("dpp_etl_task_instance"," AND task_type="+entry.getValue()+" AND status='7'",taskInstances,start,end):null);
            row.put("failure",instances?count("dpp_etl_task_instance"," AND task_type="+entry.getValue()+" AND status='6'",taskInstances,start,end):null);executions.add(row);
        }
        Map<String,Object> row=new LinkedHashMap<>();row.put("key","quality");row.put("success",quality?count("dpp_quality_log"," AND success_flag='0'",qualityScope,start,end):null);row.put("failure",quality?count("dpp_quality_log"," AND success_flag='1'",qualityScope,start,end):null);executions.add(row);
        result.put("executions",executions);
        return CommonResult.success(result);
    }
    private static boolean permitted(String... permissions){return Arrays.stream(permissions).anyMatch(SecurityUtils::hasPermi);}
    private static Scope all(){return new Scope("",List.of());}
    private static Scope ids(String column,Collection<Long> ids){return ids.isEmpty()?new Scope(" AND 1=0",List.of()):new Scope(" AND "+column+" IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",new ArrayList<>(ids));}
    private Scope apiScope(Set<Long> projects,boolean logs){
        var permissions=new tech.qiantong.qdata.security.web.service.ProjectPermissionService(jdbc);
        String[] capabilities=logs?new String[]{"ds:apiLog:list","ds:apiLog:query","ds:apilog:query","ds:apiLog:export"}:new String[]{"ds:api:list","ds:api:query"};
        var visible=projects.stream().filter(project->permissions.any(project,capabilities)).toList();
        List<Object> args=new ArrayList<>();args.add(SecurityUtils.getUserId());
        if(visible.isEmpty())return new Scope(" AND project_id IS NULL AND creator_id=?",args);
        args.addAll(visible);return new Scope(" AND ((project_id IS NULL AND creator_id=?) OR project_id IN ("+String.join(",",Collections.nCopies(visible.size(),"?"))+"))",args);
    }
    private void card(Map<String,Object> totals,Map<String,Object> recent,String key,String table,String filter,Scope scope,String start,String end,boolean allowed){
        totals.put(key,allowed?count(table,filter,scope,null,null):null);recent.put(key,allowed?count(table,filter,scope,start,end):null);
    }
    private long count(String table,String filter,Scope scope,String start,String end){
        List<Object> args=new ArrayList<>(scope.args());String range="";
        if(start!=null){range=" AND create_time>=? AND create_time<?";args.add(start);args.add(end);}
        Long value=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE del_flag='0'"+scope.where()+filter+range,Long.class,args.toArray());return value==null?0:value;
    }
    private List<Long> daily(String table,String filter,Scope scope,String start,String end,List<String> days,boolean allowed){
        if(!allowed)return Collections.nCopies(days.size(),null);
        List<Object> args=new ArrayList<>(scope.args());args.add(start);args.add(end);
        Map<String,Long> values=new HashMap<>();
        jdbc.query("SELECT DATE_FORMAT(create_time,'%Y-%m-%d') day,COUNT(*) total FROM "+table+" WHERE del_flag='0'"+scope.where()+filter+" AND create_time>=? AND create_time<? GROUP BY DATE_FORMAT(create_time,'%Y-%m-%d')",rs->{values.put(rs.getString("day"),rs.getLong("total"));},args.toArray());
        return days.stream().map(day->values.getOrDefault(day,0L)).toList();
    }
}
