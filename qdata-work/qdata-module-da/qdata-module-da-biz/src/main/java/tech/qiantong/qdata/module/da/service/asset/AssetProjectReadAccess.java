package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetRespVO;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;
import java.util.*;

/** Read capabilities must be granted in a project owning the actual asset. */
@Service
@RequiredArgsConstructor
public class AssetProjectReadAccess {
    private final JdbcTemplate jdbc;
    private final DataResourceAccess data;
    private final ProjectPermissionService permissions;

    private Map<String,Object> row(Long id){
        if(id==null)throw denied();var rows=jdbc.queryForList("SELECT id,creator_id,dataset_id,datasource_id,database_name,table_name,type FROM da_asset WHERE id=? AND del_flag='0'",id);
        if(rows.size()!=1)throw denied();return rows.get(0);
    }
    private Set<Long> owners(Map<String,Object> row){
        if(row.get("dataset_id")!=null)return new HashSet<>(jdbc.queryForList("SELECT s.project_id FROM dp_warehouse_dataset d JOIN dp_warehouse_space s ON s.id=d.space_id WHERE d.id=?",Long.class,row.get("dataset_id")));
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT project_id FROM da_asset_project_rel WHERE asset_id=? AND valid_flag='1' AND del_flag='0'",Long.class,row.get("id")));
    }
    private boolean allowed(Map<String,Object> row,String permission){
        if(permissions.isAdministrator())return true;
        var owners=owners(row);
        if(owners.isEmpty())return row.get("dataset_id")==null && Objects.equals(number(row.get("creator_id")),SecurityUtils.getUserId()) && SecurityUtils.hasPermi(permission);
        Set<Long> visible=data.visibleProjectIds();return owners.stream().anyMatch(p->visible.contains(p) && permissions.has(p,permission));
    }
    public List<Long> ids(String permission){
        List<Long> ids=new ArrayList<>();for(Long id:data.visibleAssetIds())if(allowed(row(id),permission))ids.add(id);return ids;
    }
    public void requireProject(Long projectId,String permission){
        if(projectId==null || permissions.isAdministrator())return;
        if(!data.visibleProjectIds().contains(projectId) || !permissions.has(projectId,permission))throw denied();
    }
    public void asset(Long id,String permission){data.requireAsset(id);if(!allowed(row(id),permission))throw denied();}
    public void column(Long id,String permission){
        if(id==null)throw denied();var assets=jdbc.queryForList("SELECT asset_id FROM da_asset_column WHERE id=? AND del_flag='0'",Long.class,id);if(assets.size()!=1)throw denied();asset(assets.get(0),permission);
    }
    public DaAssetRespVO present(DaAssetRespVO item){
        if(item==null)return null;var row=row(item.getId());
        if("2".equals(item.getType())){var names=jdbc.queryForList("SELECT app_name FROM da_asset_api WHERE asset_id=? AND del_flag='0' ORDER BY id",String.class,item.getId());if(!names.isEmpty())item.setAppName(names.get(0));}
Map<String,Boolean> capabilities=new LinkedHashMap<>();
        for(String action:List.of("list","query","export"))capabilities.put(action,allowed(row,"da:asset:"+action));item.setReadCapabilities(capabilities);return item;
    }
    public void preview(Long id,Map<String,Object> request){
        asset(id,"da:asset:query");if(request.get("taskId")!=null)throw new AccessDeniedException("资产预览只能使用当前资产登记的数据来源");
        previewConditions(id,request);
        var row=row(id);if(!"1".equals(Objects.toString(row.get("type"),"")))return;
        Long source=number(row.get("datasource_id"));String table=Objects.toString(row.get("table_name"),"");String database=Objects.toString(row.get("database_name"),"");
        var types=jdbc.queryForList("SELECT datasource_type FROM da_datasource WHERE id=? AND del_flag='0'",String.class,source);if(types.size()!=1)throw denied();
        if("Hive".equalsIgnoreCase(types.get(0)) && !database.isBlank())table=tech.qiantong.qdata.common.database.core.HiveTableIdentity.resolve(table,database).canonical();
        data.requireTable(source,table);
        if(!permissions.isAdministrator()){
            Set<Long> physicalOwners=data.owningHiveProjects(source,table);
            if(physicalOwners.isEmpty())physicalOwners=new HashSet<>(jdbc.queryForList("SELECT DISTINCT project_id FROM da_datasource_project_rel WHERE datasource_id=? AND valid_flag='1'",Long.class,source));
            Set<Long> visible=data.visibleProjectIds();
            if(physicalOwners.stream().noneMatch(project->visible.contains(project) && permissions.has(project,"da:asset:query")))throw denied();
        }
    }
    private void previewConditions(Long id,Map<String,Object> request){
        String filter=Objects.toString(request.get("filter"),"");Object ordering=request.get("orderBy");
        if(filter.isBlank() && (ordering==null || ordering instanceof List<?> items && items.isEmpty()))return;
        Set<String> columns=new HashSet<>();for(String column:jdbc.queryForList("SELECT column_name FROM da_asset_column WHERE asset_id=? AND del_flag='0'",String.class,id))columns.add(column.toLowerCase(Locale.ROOT));
        if(!filter.isBlank()){
            String sql="SELECT * FROM qdata_asset_preview WHERE "+filter;
            if(!tech.qiantong.qdata.common.database.core.ReadOnlySql.tables(sql,com.alibaba.druid.DbType.mysql).equals(Set.of("qdata_asset_preview")))throw new IllegalArgumentException("资产筛选不能访问其他数据表");
            var statement=(com.alibaba.druid.sql.ast.statement.SQLSelectStatement)com.alibaba.druid.sql.SQLUtils.parseSingleStatement(sql,com.alibaba.druid.DbType.mysql);
            var query=statement.getSelect().getQuery();
            if(!(query instanceof com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock block) || block.getOrderBy()!=null || block.getGroupBy()!=null || block.getLimit()!=null || statement.getSelect().getOrderBy()!=null)throw new IllegalArgumentException("筛选只能包含当前资产的字段条件");
            block.getWhere().accept(new com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter(){
                @Override public void preVisit(com.alibaba.druid.sql.ast.SQLObject object){
                    if(object instanceof com.alibaba.druid.sql.ast.statement.SQLSelect || object instanceof com.alibaba.druid.sql.ast.expr.SQLPropertyExpr || object instanceof com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr)throw new IllegalArgumentException("资产筛选不支持子查询或外部字段引用");
                    if(object instanceof com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr column && !columns.contains(column.getName().replace("`","").replace("\"","").toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("筛选字段未登记到当前资产");
                }
            });
        }
        if(ordering!=null){
            if(!(ordering instanceof List<?> items))throw new IllegalArgumentException("排序参数必须为字段列表");
            for(Object item:items){
                if(!(item instanceof Map<?,?> entry))throw new IllegalArgumentException("排序参数格式错误");
                String column=Objects.toString(entry.get("orderByColumn"),""),direction=Objects.toString(entry.get("isAsc"),"desc");
                if(!column.matches("[\\p{L}_][\\p{L}\\p{N}_]*") || !columns.contains(column.toLowerCase(Locale.ROOT)) || !Set.of("asc","desc").contains(direction.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("仅能按当前资产登记字段升序或降序排列");
            }
        }
    }
    public void physicalRules(Long source,String table){
        data.requireTable(source,table);String name=table;String database=null;
        var types=jdbc.queryForList("SELECT datasource_type FROM da_datasource WHERE id=? AND del_flag='0'",String.class,source);
        if(types.size()==1 && "Hive".equalsIgnoreCase(types.get(0)) && table.contains(".")){var identity=tech.qiantong.qdata.common.database.core.HiveTableIdentity.resolve(table,null);name=identity.tableName();database=identity.databaseName();}
        List<Long> matches=database==null?jdbc.queryForList("SELECT id FROM da_asset WHERE datasource_id=? AND table_name=? AND del_flag='0'",Long.class,source,name):jdbc.queryForList("SELECT id FROM da_asset WHERE datasource_id=? AND database_name=? AND table_name=? AND del_flag='0'",Long.class,source,database,name);
        for(Long id:matches)asset(id,"da:asset:query");
    }
    private static Long number(Object value){if(value==null)return null;try{return Long.valueOf(String.valueOf(value));}catch(NumberFormatException invalid){throw denied();}}
    private static AccessDeniedException denied(){return new AccessDeniedException("资产读取功能未授权给当前资产所属项目");}
}
