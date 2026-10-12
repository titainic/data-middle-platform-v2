package tech.qiantong.qdata.module.da.api.service;

import java.util.Set;

/** Current project membership controls interactive resource access, independently of menu permissions. */
public interface DataResourceAccess {
    boolean isAdministrator();
    Set<Long> visibleProjectIds();
    Set<Long> visibleAssetIds();
    Set<Long> visibleDatasourceIds();
    /** Configuration management retains disabled connections; execution does not. */
    default Set<Long> configurableDatasourceIds(){return visibleDatasourceIds();}
    default void requireDatasourceConfiguration(Long datasourceId){requireDatasource(datasourceId);}
    void requireDatasource(Long datasourceId);
    void requireAsset(Long assetId);
    void requireTable(Long datasourceId,String tableName);
    Set<Long> owningHiveProjects(Long datasourceId,String tableName);
    default void requireMetadataTable(Long datasourceId,String tableName){requireTable(datasourceId,tableName);}
}
