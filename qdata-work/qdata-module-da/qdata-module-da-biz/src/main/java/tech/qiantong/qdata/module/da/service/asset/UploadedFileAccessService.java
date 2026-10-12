package tech.qiantong.qdata.module.da.service.asset;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.file.UploadedFileAccess;
import tech.qiantong.qdata.common.file.UploadedFileKey;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

/** Uploaded bytes and their current asset references share the same project capabilities. */
@Service
@RequiredArgsConstructor
public class UploadedFileAccessService implements UploadedFileAccess {
    private final JdbcTemplate jdbc;
    private final AssetProjectReadAccess assets;
    private final ProjectPermissionService permissions;
    @org.springframework.beans.factory.annotation.Value("${qdata.profile}") private String profile;
    private static final Set<String> DISPLAY=Set.of("png","jpg","jpeg","gif","bmp","webp","avif","mp4","webm","mp3","wav");
    private String key(String resource) {
        String supplied=UploadedFileKey.of(resource);
        java.nio.file.Path root=java.nio.file.Path.of(profile);
        java.nio.file.Path real=tech.qiantong.qdata.common.utils.file.SafeUploadPath.resolve(root,supplied);
        try {return UploadedFileKey.of(root.toRealPath().relativize(real).toString());}
        catch(java.io.IOException unavailable){throw new IllegalArgumentException("上传目录不可用",unavailable);}
    }
    @Override public void register(String resource,String purpose) {
        String key=key(resource);Long owner;try{owner=SecurityUtils.getUserId();}catch(RuntimeException missing){if(!"EXPORT".equals(purpose))throw missing;owner=null;}
        if(!Set.of("UPLOAD","AVATAR","CA","EXPORT").contains(purpose))throw new IllegalArgumentException("上传用途无效");
        jdbc.update("INSERT IGNORE INTO sys_uploaded_file(resource_hash,resource_key,owner_user_id,purpose) VALUES(?,?,?,?)",UploadedFileKey.hash(key),key,owner,purpose);
        var record=record(key,false);
        if(!key.equals(record.get("resource_key")) || !Objects.equals(owner,number(record.get("owner_user_id"))) || !purpose.equals(record.get("purpose")))throw new IllegalStateException("文件标识已登记，不允许替换归属");
    }
    private Map<String,Object> record(String key,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM sys_uploaded_file WHERE resource_hash=?"+(lock?" FOR UPDATE":""),UploadedFileKey.hash(key));
        if(rows.isEmpty())return Map.of();if(!key.equals(rows.get(0).get("resource_key")))throw denied();return rows.get(0);
    }
    private List<Long> references(String key) {
        var candidates=jdbc.queryForList("SELECT r.asset_id,f.url resource FROM sys_uploaded_file_ref r JOIN da_asset_files f ON r.kind='FILES' AND f.id=r.child_id AND f.asset_id=r.asset_id AND f.del_flag='0' JOIN da_asset a ON a.id=r.asset_id AND a.del_flag='0' WHERE r.resource_hash=? UNION ALL SELECT r.asset_id,g.file_url resource FROM sys_uploaded_file_ref r JOIN da_asset_geo g ON r.kind='GEO' AND g.id=r.child_id AND g.asset_id=r.asset_id AND g.del_flag='0' JOIN da_asset a ON a.id=r.asset_id AND a.del_flag='0' WHERE r.resource_hash=?",UploadedFileKey.hash(key),UploadedFileKey.hash(key));
        List<Long> result=new ArrayList<>();for(var candidate:candidates)try{if(key.equals(key(Objects.toString(candidate.get("resource"),""))))result.add(number(candidate.get("asset_id")));}catch(IllegalArgumentException invalid){}
        return result;
    }
    private Set<Long> projects(Long asset) {return new HashSet<>(jdbc.queryForList("SELECT DISTINCT project_id FROM da_asset_project_rel WHERE asset_id=? AND valid_flag='1' AND del_flag='0'",Long.class,asset));}
    private boolean readable(Long asset,String capability) {try{assets.asset(asset,capability);return true;}catch(AccessDeniedException denied){return false;}}
    private boolean personal(Map<String,Object> record) {return !record.isEmpty() && !bound(record) && Objects.equals(number(record.get("owner_user_id")),SecurityUtils.getUserId());}
    private static boolean bound(Map<String,Object> record){return "1".equals(Objects.toString(record.get("bound_flag"),"0")) || Boolean.TRUE.equals(record.get("bound_flag"));}
    @Override public void read(String resource) {
        String key=key(resource);SecurityUtils.getUserId();if(permissions.isAdministrator())return;
        String extension=key.substring(key.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(key.startsWith("/profile/avatar/") && Set.of("png","jpg","jpeg","gif","bmp","webp","avif").contains(extension) && jdbc.queryForObject("SELECT COUNT(*) FROM system_user WHERE BINARY avatar=BINARY ? AND del_flag='0'",Long.class,key)>0)return;
        var record=record(key,false);var refs=references(key);
        if(!refs.isEmpty()) {
            String capability=DISPLAY.contains(extension)?"da:asset:query":"da:asset:export";
            if(refs.stream().anyMatch(asset->readable(asset,"da:asset:query") && readable(asset,capability)))return;throw denied();
        }
        if(personal(record)) {
            if(!"CA".equals(record.get("purpose")) || SecurityUtils.hasPermi("ca:cert:query") || SecurityUtils.hasPermi("ca:subject:query"))return;
        }
        throw denied();
    }
    @Override public void use(String resource,Set<Long> targetProjects,Long targetAsset) {
        authorizeUse(key(resource),targetProjects==null?Set.of():targetProjects,targetAsset,false);
    }
    private void authorizeUse(String key,Set<Long> targets,Long targetAsset,boolean lock) {
        SecurityUtils.getUserId();
        var record=record(key,lock);
        if(key.startsWith("/profile/ca/") || key.startsWith("/profile/avatar/") || Set.of("CA","AVATAR").contains(Objects.toString(record.get("purpose"),"")))throw new AccessDeniedException("此文件仅供原业务用途使用");
        if(permissions.isAdministrator())return;
        var refs=references(key);
        if(refs.isEmpty()){if(personal(record))return;throw denied();}
        if(targets.isEmpty()) {
            if(targetAsset!=null && refs.contains(targetAsset) && readable(targetAsset,"da:asset:query"))return;throw denied();
        }
        for(Long project:targets)if(!permissions.has(project,"da:asset:query") || refs.stream().noneMatch(asset->projects(asset).contains(project) && readable(asset,"da:asset:query")))throw denied();
    }
    @Override public void bind(String resource,Long asset) {
        String key=key(resource);authorizeUse(key,projects(asset),asset,true);
        jdbc.update("INSERT IGNORE INTO sys_uploaded_file(resource_hash,resource_key,owner_user_id,purpose,bound_flag) VALUES(?,?,?,'LEGACY',1)",UploadedFileKey.hash(key),key,SecurityUtils.getUserId());
        jdbc.update("UPDATE sys_uploaded_file SET bound_flag=1 WHERE resource_hash=?",UploadedFileKey.hash(key));
    }
    @Override public void indexAsset(String kind,Long child,Long asset,String resource) {
        if(!Set.of("FILES","GEO").contains(kind))throw new IllegalArgumentException("文件引用类型无效");
        String key=key(resource);
        jdbc.update("UPDATE sys_uploaded_file SET bound_flag=1 WHERE resource_hash=?",UploadedFileKey.hash(key));
        jdbc.update("INSERT INTO sys_uploaded_file_ref(kind,child_id,asset_id,resource_hash) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE asset_id=VALUES(asset_id),resource_hash=VALUES(resource_hash)",kind,child,asset,UploadedFileKey.hash(key));
    }
    private static Long number(Object value){return value==null?null:Long.valueOf(String.valueOf(value));}
    private static AccessDeniedException denied(){return new AccessDeniedException("文件不属于当前可访问项目，或未获得相应读取权限");}
}
