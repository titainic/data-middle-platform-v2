package tech.qiantong.qdata.module.da.service.assetColumn;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Keep catalog counts in the same transaction as field mutations. No physical data access. */
@Service
@RequiredArgsConstructor
public class AssetColumnStatistics {
    private final JdbcTemplate jdbc;
    public void lock(Collection<Long> assets){
        requireTransaction();
        for(Long id:ordered(assets))if(jdbc.queryForList("SELECT id FROM da_asset WHERE id=? AND del_flag='0' FOR UPDATE",Long.class,id).size()!=1)throw new IllegalArgumentException("字段所属资产不存在");
    }
    public void refresh(Collection<Long> assets){
        requireTransaction();
        for(Long id:ordered(assets))jdbc.update("UPDATE da_asset SET field_count=(SELECT COUNT(*) FROM da_asset_column WHERE asset_id=? AND del_flag='0') WHERE id=? AND del_flag='0'",String.valueOf(id),id);
    }
    private static Set<Long> ordered(Collection<Long> assets){
        if(assets==null || assets.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("字段必须归属有效资产");return new TreeSet<>(assets);
    }
    private static void requireTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("字段及统计更新必须处于同一事务");}
}
