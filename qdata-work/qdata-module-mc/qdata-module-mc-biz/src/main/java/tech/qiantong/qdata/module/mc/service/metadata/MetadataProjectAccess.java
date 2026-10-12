package tech.qiantong.qdata.module.mc.service.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Functional permissions are resolved in the projects owning the catalog object. */
@Service
@RequiredArgsConstructor
public class MetadataProjectAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess resources;
    private final ProjectPermissionService permissions;
    private boolean granted(Long project,String action){if("browse".equals(action))return permissions.any(project,"mc:metadata:table:list","mc:metadata:table:query","mc:metadata:table:detail","mc:task:list","mc:task:query");return "read".equals(action)?permissions.any(project,"mc:metadata:table:list","mc:metadata:table:query","mc:metadata:table:detail"):permissions.has(project,"mc:metadata:table:"+action);}
    private Set<Long> eligible(String action){
        var request=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();String key=getClass().getName()+".permissions."+SecurityUtils.getUserId()+"."+action;
        if(request!=null && request.getAttribute(key,0) instanceof Set<?> cached)return cached.stream().map(x->((Number)x).longValue()).collect(java.util.stream.Collectors.toSet());
        var result=resources.visibleProjectIds().stream().filter(project->granted(project,action)).collect(java.util.stream.Collectors.toSet());if(request!=null)request.setAttribute(key,result,0);return result;
    }
    public Set<Long> sourceProjects(Long source,String table){
        if(source==null)return Set.of();var hive=resources.owningHiveProjects(source,table);if(!hive.isEmpty())return hive;
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT project_id FROM da_datasource_project_rel WHERE datasource_id=? AND valid_flag='1'",Long.class,source));
    }
    public Set<Long> linkedProjects(Long table){
        var result=jdbc.queryForList("SELECT DISTINCT CASE WHEN a.dataset_id IS NOT NULL THEN s.project_id ELSE r.project_id END FROM da_asset a LEFT JOIN dp_warehouse_dataset d ON d.id=a.dataset_id LEFT JOIN dp_warehouse_space s ON s.id=d.space_id LEFT JOIN da_asset_project_rel r ON r.asset_id=a.id AND r.valid_flag='1' AND r.del_flag='0' WHERE a.table_id=? AND a.del_flag='0'",Long.class,table);
        return result.stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
    }
    public boolean allowed(Set<Long> owners,Long creator,String action,boolean write){
        if(resources.isAdministrator())return true;
        if(owners.isEmpty())return !write && Objects.equals(creator,SecurityUtils.getUserId()) && ("read".equals(action)?SecurityUtils.hasPermi("mc:metadata:table:list") || SecurityUtils.hasPermi("mc:metadata:table:query") || SecurityUtils.hasPermi("mc:metadata:table:detail"):SecurityUtils.hasPermi("mc:metadata:table:"+action));
        var permitted=eligible(action);return write?permitted.containsAll(owners):owners.stream().anyMatch(permitted::contains);
    }
    public boolean source(Long source,String table,String action,boolean write){return allowed(sourceProjects(source,table),null,action,write);}
    public boolean table(Long id,Long source,String table,Long creator,String action,boolean write){var owners=new HashSet<>(sourceProjects(source,table));owners.addAll(linkedProjects(id));return allowed(owners,source==null?creator:null,action,write);}
    public boolean connectionBrowse(Long source){return allowed(new HashSet<>(jdbc.queryForList("SELECT DISTINCT project_id FROM da_datasource_project_rel WHERE datasource_id=? AND valid_flag='1'",Long.class,source)),null,"browse",false);}
}
