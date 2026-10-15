package tech.qiantong.qdata.module.dpp.service.etl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.file.DevelopmentFileAccess;
import tech.qiantong.qdata.common.file.UploadedFileAccess;
import tech.qiantong.qdata.common.file.UploadedFileKey;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.utils.file.SafeUploadPath;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

/** Immutable project provenance for a conversion artifact; scheduler checks do not need a user session. */
@Service
@RequiredArgsConstructor
public class DevelopmentFileAccessService implements DevelopmentFileAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess resources;
    private final ProjectPermissionService permissions;
    private final UploadedFileAccess uploads;
    @Value("${qdata.profile}") private String profile;

    private static AccessDeniedException denied(){return new AccessDeniedException("输入文件或转换结果不属于当前项目");}
    private String source(String value) {
        String key=UploadedFileKey.of(value);
        Path root=Path.of(profile);Path real=SafeUploadPath.resolve(root,key);
        try{return UploadedFileKey.of(root.toRealPath().relativize(real).toString());}
        catch(java.io.IOException error){throw new IllegalArgumentException("上传根目录不可用",error);}
    }
    /** Absolute local paths and HDFS URIs are accepted only when the exact server-issued value is registered. */
    static String artifact(String value) {
        if(value==null || value.isBlank() || value.length()>2048 || value.indexOf('\\')>=0 || value.indexOf('\0')>=0)throw new IllegalArgumentException("转换文件路径无效");
        if(value.startsWith("/")) {
            Path path=Path.of(value);if(!path.isAbsolute() || value.contains("%") || !path.normalize().toString().equals(value))throw new IllegalArgumentException("转换文件路径无效");
            return path.toString();
        }
        URI uri;
        try{uri=URI.create(value);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("转换文件路径无效",invalid);}
        if(!"hdfs".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getRawPath()==null || uri.getRawPath().contains("%") || !uri.normalize().equals(uri) || !uri.getRawPath().startsWith("/"))throw new IllegalArgumentException("转换文件路径无效");
        return "hdfs://"+uri.getRawAuthority().toLowerCase(Locale.ROOT)+uri.getRawPath();
    }
    private void project(Long project) {
        if(project==null || jdbc.queryForObject("SELECT COUNT(*) FROM att_project WHERE id=? AND valid_flag='1' AND del_flag='0'",Long.class,project)!=1)throw denied();
    }
    private void write(Long project) {
        project(project);SecurityUtils.getUserId();
        if(!resources.isAdministrator() && (!resources.visibleProjectIds().contains(project) || !permissions.any(project,"dpp:integratioTask:add","dpp:integratioTask:edit","dpp:etlTask:add","dpp:etlTask:edit")))throw denied();
    }
    @Override public void authorizeSource(Long projectId,String resource) {
        write(projectId);String key=source(resource);
        boolean existing=jdbc.queryForObject("SELECT COUNT(*) FROM sys_development_file_artifact WHERE source_hash=? AND project_id=?",Long.class,UploadedFileKey.hash(key),projectId)>0;
        if(!existing){uploads.read(key);uploads.use(key,Set.of(projectId),null);}
    }
    @Override public void register(Long projectId,String resource,String executionPath) {
        authorizeSource(projectId,resource);
        String key=source(resource),artifact=artifact(executionPath),hash=UploadedFileKey.hash(artifact);
        jdbc.update("INSERT IGNORE INTO sys_development_file_artifact(execution_hash,execution_path,source_hash,source_resource,project_id,owner_user_id,origin) VALUES(?,?,?,?,?,?,'UPLOAD')",hash,artifact,UploadedFileKey.hash(key),key,projectId,SecurityUtils.getUserId());
        validate(projectId,key,artifact);
    }
    public void validate(Long projectId,String resource,String executionPath) {
        project(projectId);String key=source(resource),artifact=artifact(executionPath);
        var rows=jdbc.queryForList("SELECT execution_path,source_hash,source_resource,project_id FROM sys_development_file_artifact WHERE execution_hash=?",UploadedFileKey.hash(artifact));
        if(rows.size()!=1)throw denied();var row=rows.get(0);
        if(!artifact.equals(row.get("execution_path")) || !key.equals(row.get("source_resource")) || !UploadedFileKey.hash(key).equals(row.get("source_hash")) || !projectId.equals(((Number)row.get("project_id")).longValue()))throw denied();
    }
    public void definitions(Long projectId,String definitions) {
        if(definitions==null || definitions.isBlank())return;
        var nodes=JSON.parseArray(definitions,JSONObject.class);if(nodes==null)throw new IllegalArgumentException("节点定义必须是JSON数组");
        for(JSONObject node:nodes)validateNode(projectId,node.getString("componentType"),node.getJSONObject("taskParams"));
    }
    private void validateNode(Long project,String component,JSONObject params) {
        String executionPath=params==null?null:params.getString("csvFile");
        // Older canvases add an empty csvFile placeholder to every input, including database readers.
        // Actual file inputs and non-empty artifact references must still pass provenance validation.
        if(!"2".equals(component) && !"4".equals(component) && (executionPath==null || executionPath.isBlank()))return;
        if(params==null)throw denied();String origin="2".equals(component)?params.getString("excelFile"):params.getString("file");
        if(origin==null || origin.isBlank() || executionPath==null || executionPath.isBlank())throw denied();
        validate(project,origin,executionPath);
    }
    public void task(Long taskId) {
        var task=jdbc.queryForList("SELECT t.project_id FROM dpp_etl_task t JOIN att_project p ON p.id=t.project_id AND p.valid_flag='1' AND p.del_flag='0' WHERE t.id=? AND t.del_flag='0'",taskId);
        if(task.size()!=1)throw denied();Long project=((Number)task.get(0).get("project_id")).longValue();
        var nodes=jdbc.queryForList("SELECT n.component_type,n.parameters FROM dpp_etl_task_node_rel r JOIN dpp_etl_node n ON n.code=r.post_node_code AND n.version=r.post_node_version AND n.project_id=r.project_id AND n.del_flag='0' WHERE r.task_id=? AND r.project_id=? AND r.del_flag='0'",taskId,project);
        for(var node:nodes)validateNode(project,String.valueOf(node.get("component_type")),JSON.parseObject(Objects.toString(node.get("parameters"),"{}")));
    }
}
