package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.module.da.controller.admin.assetchild.projectRel.vo.DaAssetProjectRelSaveReqVO;
import java.util.*;

/** Internal composition may create the first relation; never clear other owners implicitly. */
@Service
@RequiredArgsConstructor
public class AssetProjectRelationIntegrity {
    private final JdbcTemplate jdbc;
    public Long existingOrValidate(DaAssetProjectRelSaveReqVO request){
        if(request.getAssetId()==null || request.getProjectId()==null)throw new IllegalArgumentException("关联须指定资产和项目");
        if(jdbc.queryForList("SELECT id FROM da_asset WHERE id=? AND del_flag='0' FOR UPDATE",Long.class,request.getAssetId()).size()!=1)throw new IllegalArgumentException("资产不存在");
        var codes=jdbc.queryForList("SELECT code FROM att_project WHERE id=? AND valid_flag='1' AND del_flag='0'",String.class,request.getProjectId());if(codes.size()!=1)throw new IllegalArgumentException("目标项目不可用");request.setProjectCode(codes.get(0));
        var ids=jdbc.queryForList("SELECT id FROM da_asset_project_rel FORCE INDEX(idx_da_asset_project_membership) WHERE asset_id=? AND project_id=? AND valid_flag='1' AND del_flag='0' FOR UPDATE",Long.class,request.getAssetId(),request.getProjectId());if(ids.size()>1)throw new IllegalStateException("关联已存在重复记录，请先核对");return ids.isEmpty()?null:ids.get(0);
    }
    public void remove(Collection<Long> ids){
        if(ids==null || ids.isEmpty())throw new IllegalArgumentException("请选择项目关系");Set<Long> selected=new HashSet<>(ids);Set<Long> assets=new TreeSet<>();
        for(Long id:ids){var rows=jdbc.queryForList("SELECT asset_id FROM da_asset_project_rel WHERE id=? AND del_flag='0'",Long.class,id);if(rows.size()!=1)throw new IllegalArgumentException("关联已变更，请刷新");assets.add(rows.get(0));}
        for(Long asset:assets){jdbc.queryForList("SELECT id FROM da_asset WHERE id=? AND del_flag='0' FOR UPDATE",Long.class,asset);var active=jdbc.queryForList("SELECT id FROM da_asset_project_rel FORCE INDEX(idx_da_asset_project_membership) WHERE asset_id=? AND valid_flag='1' AND del_flag='0' FOR UPDATE",Long.class,asset);if(active.stream().noneMatch(id->!selected.contains(id)))throw new IllegalArgumentException("必须保留至少一个有效归属项目");}
    }
    public int archive(Collection<Long> ids){
        if(ids.isEmpty())return 0;List<Object> args=new ArrayList<>();args.add(tech.qiantong.qdata.common.utils.SecurityUtils.getUserId());args.add(tech.qiantong.qdata.common.utils.SecurityUtils.getUsername());args.add(new java.sql.Timestamp(System.currentTimeMillis()));args.addAll(ids);
        return jdbc.update("UPDATE da_asset_project_rel SET del_flag='1',updater_id=?,update_by=?,update_time=? WHERE del_flag='0' AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray());
    }
}


