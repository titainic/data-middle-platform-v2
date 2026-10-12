package tech.qiantong.qdata.module.da.service.asset;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;

/** Authorize user initiated simulation before any external request is sent. */
@Service
@RequiredArgsConstructor
public class AssetForwardingAccess {
    private final JdbcTemplate jdbc;
    private final AssetMutationAccess mutations;
    public Long authorize(String kind,Long childId,Long suppliedAsset){
        String type=switch(kind){case "api"->"2";case "gis"->"3";default->throw new IllegalArgumentException("不支持的模拟调用资源类型");};
        if(childId==null)throw denied();
        var rows=jdbc.queryForList("SELECT c.asset_id FROM da_asset_"+kind+" c JOIN da_asset a ON a.id=c.asset_id WHERE c.id=? AND c.valid_flag='1' AND c.del_flag='0' AND a.valid_flag='1' AND a.del_flag='0' AND a.type=?",Long.class,childId,type);
        if(rows.size()!=1)throw denied();Long actual=rows.get(0);
        if(suppliedAsset!=null && !suppliedAsset.equals(actual))throw denied();
        mutations.asset(actual,"da:asset:edit");return actual;
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("模拟调用资源不可用、父资产不匹配或所属项目未授权");}
}
