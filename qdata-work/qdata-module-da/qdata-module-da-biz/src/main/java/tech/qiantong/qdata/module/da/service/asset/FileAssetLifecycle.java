package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.common.utils.file.SafeUploadPath;
import tech.qiantong.qdata.common.utils.file.TabularFile;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetSaveReqVO;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.files.vo.DaAssetFilesSaveReqVO;
import tech.qiantong.qdata.module.da.service.assetColumn.AssetColumnStatistics;
import java.nio.file.*;
import java.util.*;

/** File metadata and derived field identities are committed together; uploaded files are never rewritten. */
@Service
@RequiredArgsConstructor
public class FileAssetLifecycle {
    private final JdbcTemplate jdbc;
    private final AssetColumnStatistics statistics;
    private final AssetReferenceGuard references;
    private final tech.qiantong.qdata.common.file.UploadedFileAccess uploadedFiles;
    @Value("${qdata.profile}") private String profile;
    public record Prepared(Long asset, TabularFile.Snapshot snapshot) {}

    public Map<String,Object> inspect(String url,Integer headerRow,Integer dataRow,Set<Long> projects,Long asset) {
        uploadedFiles.use(url,projects,asset);
        var snapshot=TabularFile.read(SafeUploadPath.resolve(Path.of(profile),url),headerRow,dataRow);
        if(!tech.qiantong.qdata.common.utils.ExcelToCsvUtil.verifyColumn(snapshot.columns()))throw new IllegalArgumentException("文件表头须使用合法字段名称");
        return Map.of("columnList",snapshot.columns(),"total",snapshot.rows().size());
    }
    public Prepared prepare(DaAssetFilesSaveReqVO request, boolean updating) {
        Map<String,Object> old=null;
        if(updating){
            var owners=jdbc.queryForList("SELECT asset_id FROM da_asset_files WHERE id=? AND del_flag='0'",Long.class,request.getId());
            if(owners.size()!=1)throw new IllegalArgumentException("文件绑定不存在");
            if(request.getAssetId()!=null && !request.getAssetId().equals(owners.get(0)))throw new IllegalArgumentException("文件绑定不能迁移到其他资产");
            request.setAssetId(owners.get(0));
        } else if(request.getId()!=null)throw new IllegalArgumentException("新增文件绑定不能指定已有ID");
        if(request.getAssetId()==null)throw new IllegalArgumentException("文件必须归属资产");
        statistics.lock(List.of(request.getAssetId()));
        if(updating){
            var rows=jdbc.queryForList("SELECT * FROM da_asset_files WHERE id=? AND del_flag='0' FOR UPDATE",request.getId());
            if(rows.size()!=1)throw new IllegalArgumentException("文件绑定已变更，请刷新");old=rows.get(0);
            if(request.getUrl()==null)request.setUrl((String)old.get("url"));
            if(request.getStartColumn()==null && old.get("start_column")!=null)request.setStartColumn(((Number)old.get("start_column")).intValue());
            if(request.getStartData()==null && old.get("start_data")!=null)request.setStartData(((Number)old.get("start_data")).intValue());
        }
        if(!"6".equals(jdbc.queryForObject("SELECT type FROM da_asset WHERE id=?",String.class,request.getAssetId())))throw new IllegalArgumentException("父资产不是文件类型");
        var bound=jdbc.queryForList("SELECT id FROM da_asset_files WHERE asset_id=? AND del_flag='0' FOR UPDATE",Long.class,request.getAssetId());
        if(bound.size()>1 || (!updating && !bound.isEmpty()) || (updating && !bound.equals(List.of(request.getId()))))throw new IllegalArgumentException("资产文件绑定不唯一，请刷新核对");
        uploadedFiles.bind(request.getUrl(),request.getAssetId());
        Path file=SafeUploadPath.resolve(Path.of(profile),request.getUrl());String name=file.getFileName().toString();int dot=name.lastIndexOf('.');String extension=dot<0?"":name.substring(dot).toLowerCase(Locale.ROOT);request.setType(extension);
        TabularFile.Snapshot snapshot=new TabularFile.Snapshot(List.of(),List.of());
        if(Set.of(".csv",".xls",".xlsx").contains(extension)){
            if(".csv".equals(extension)){request.setStartColumn(1);request.setStartData(2);}
            else {if(request.getStartColumn()==null)request.setStartColumn(1);if(request.getStartData()==null)request.setStartData(request.getStartColumn()+1);}
            snapshot=TabularFile.read(file,request.getStartColumn(),request.getStartData());
            if(!tech.qiantong.qdata.common.utils.ExcelToCsvUtil.verifyColumn(snapshot.columns()))throw new IllegalArgumentException("文件表头须使用合法字段名称");
        }
        var existing=jdbc.queryForList("SELECT column_name FROM da_asset_column WHERE asset_id=? AND del_flag='0' ORDER BY id FOR UPDATE",String.class,String.valueOf(request.getAssetId()));
        if(existing.size()!=new HashSet<>(existing).size())throw new IllegalArgumentException("原文件字段存在重复名称，请先核对");
        boolean bindingChanged=old!=null && !Objects.equals(old.get("url"),request.getUrl());
        if((bindingChanged || !new HashSet<>(existing).equals(new HashSet<>(snapshot.columns()))) && Boolean.TRUE.equals(references.references(request.getAssetId()).get("referenced")))
            throw new IllegalArgumentException("文件资产已有业务引用，请通过新资产版本变更文件或字段结构");
        return new Prepared(request.getAssetId(),snapshot);
    }

    public void synchronize(Prepared prepared) {
        Long asset=prepared.asset();var snapshot=prepared.snapshot();
        var file=jdbc.queryForMap("SELECT id,url FROM da_asset_files WHERE asset_id=? AND del_flag='0'",asset);
        uploadedFiles.indexAsset("FILES",number(file.get("id")),asset,String.valueOf(file.get("url")));
        Map<String,Long> existing=new LinkedHashMap<>();
        for(var row:jdbc.queryForList("SELECT id,column_name FROM da_asset_column WHERE asset_id=? AND del_flag='0' ORDER BY id FOR UPDATE",String.valueOf(asset)))
            if(existing.put(String.valueOf(row.get("column_name")),number(row.get("id")))!=null)throw new IllegalArgumentException("原文件字段存在重复名称，请先核对");
        var removed=existing.entrySet().stream().filter(e->!snapshot.columns().contains(e.getKey())).map(Map.Entry::getValue).toList();
        references.requireColumnsRemovable(removed);
        Long user=SecurityUtils.getUserId();String username=SecurityUtils.getUsername();var now=new java.sql.Timestamp(System.currentTimeMillis());
        for(Long id:removed)jdbc.update("UPDATE da_asset_column SET del_flag='1',updater_id=?,update_by=?,update_time=? WHERE id=? AND del_flag='0'",user,username,now,id);
        for(String name:snapshot.columns())if(!existing.containsKey(name))jdbc.update("INSERT INTO da_asset_column(asset_id,column_name,column_type,nullable_flag,creator_id,create_by,create_time,updater_id,update_by,update_time) VALUES(?,?,'STRING','1',?,?,?,?,?,?)",String.valueOf(asset),name,user,username,now,user,username,now);
        statistics.refresh(List.of(asset));
        jdbc.update("UPDATE da_asset SET data_count=? WHERE id=? AND del_flag='0'",snapshot.rows().size(),asset);
    }
    public void presentStatistics(DaAssetSaveReqVO request){var counts=jdbc.queryForMap("SELECT field_count,data_count FROM da_asset WHERE id=?",request.getId());request.setFieldCount(number(counts.get("field_count")));request.setDataCount(number(counts.get("data_count")));}
    private static Long number(Object value){return value==null?null:Long.valueOf(String.valueOf(value));}
}
