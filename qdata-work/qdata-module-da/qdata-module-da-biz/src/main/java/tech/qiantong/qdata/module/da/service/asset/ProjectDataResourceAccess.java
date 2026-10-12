package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.service.datasource.HiveNamespaceBrowser;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProjectDataResourceAccess implements DataResourceAccess {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<HiveNamespaceBrowser> namespaces;
    private boolean administrator(){return SecurityUtils.isGlobalAdministrator();}
    @Override public boolean isAdministrator(){return administrator();}
    @Override public Set<Long> visibleProjectIds(){return new HashSet<>(administrator()?jdbc.queryForList("SELECT id FROM att_project WHERE del_flag='0' AND valid_flag='1'",Long.class):projects());}
    private List<Long> projects(){return jdbc.queryForList("SELECT p.id FROM att_project p WHERE p.del_flag='0' AND p.valid_flag='1' AND (p.manager_id=? OR EXISTS(SELECT 1 FROM att_project_user_rel r WHERE r.project_id=p.id AND r.user_id=? AND r.del_flag='0' AND r.valid_flag='1'))",Long.class,SecurityUtils.getUserId(),SecurityUtils.getUserId());}
    @Override public Set<Long> visibleDatasourceIds() {return datasourceIds(false);}
    @Override public Set<Long> configurableDatasourceIds() {return datasourceIds(true);}
    private Set<Long> datasourceIds(boolean includeDisabled) {
        String active=includeDisabled?"":" AND valid_flag='1'";
        if(administrator())return new HashSet<>(jdbc.queryForList("SELECT id FROM da_datasource WHERE del_flag='0'"+active,Long.class));
        var projects=projects();List<Object> args=new ArrayList<>();args.add(SecurityUtils.getUserId());args.addAll(projects);
        String membership=projects.isEmpty()?"FALSE":"EXISTS(SELECT 1 FROM da_datasource_project_rel r WHERE r.datasource_id=d.id AND r.valid_flag='1' AND r.project_id IN ("+placeholders(projects.size())+"))";
        return new HashSet<>(jdbc.queryForList("SELECT d.id FROM da_datasource d WHERE d.del_flag='0'"+active+" AND (d.creator_id=? OR "+membership+")",Long.class,args.toArray()));
    }
    @Override public Set<Long> visibleAssetIds() {
        if(administrator())return new HashSet<>(jdbc.queryForList("SELECT id FROM da_asset WHERE del_flag='0'",Long.class));
        var projects=projects();List<Object> args=new ArrayList<>();
        String managed=projects.isEmpty()?"FALSE":"s.project_id IN ("+placeholders(projects.size())+")";args.addAll(projects);
        args.add(SecurityUtils.getUserId());
        String shared=projects.isEmpty()?"FALSE":"EXISTS(SELECT 1 FROM da_asset_project_rel r WHERE r.asset_id=a.id AND r.valid_flag='1' AND r.del_flag='0' AND r.project_id IN ("+placeholders(projects.size())+"))";args.addAll(projects);
        return new HashSet<>(jdbc.queryForList("SELECT a.id FROM da_asset a LEFT JOIN dp_warehouse_dataset d ON d.id=a.dataset_id LEFT JOIN dp_warehouse_space s ON s.id=d.space_id WHERE a.del_flag='0' AND ((a.dataset_id IS NOT NULL AND "+managed+") OR (a.dataset_id IS NULL AND (a.creator_id=? OR "+shared+")))",Long.class,args.toArray()));
    }
    @Override public void requireAsset(Long id){if(id==null || !visibleAssetIds().contains(id))throw new AccessDeniedException("资产不存在或不属于当前可访问项目");}
    @Override public void requireDatasource(Long id){if(id==null || !visibleDatasourceIds().contains(id))throw new AccessDeniedException("连接不属于当前可访问项目");}
    @Override public void requireDatasourceConfiguration(Long id){if(id==null || !configurableDatasourceIds().contains(id))throw new AccessDeniedException("连接不属于当前可管理项目");}
    @Override public void requireTable(Long sourceId,String table) {
        requireDatasource(sourceId);
        authorizeNamespace(sourceId,table);
    }
    @Override public void requireMetadataTable(Long sourceId,String table) {
        requireDatasourceConfiguration(sourceId);
        authorizeNamespace(sourceId,table);
    }
    @Override public Set<Long> owningHiveProjects(Long sourceId,String table){return namespaces.getObject().owningProjects(sourceId,table);}
    private void authorizeNamespace(Long sourceId,String table) {
        try {namespaces.getObject().authorizeTable(sourceId,table);}
        catch(IllegalArgumentException denied){throw new AccessDeniedException("数据表不属于当前可访问的Hive数据库");}
    }
    private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
}
