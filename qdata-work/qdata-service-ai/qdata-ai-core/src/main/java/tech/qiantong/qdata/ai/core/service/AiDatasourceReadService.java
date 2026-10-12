package tech.qiantong.qdata.ai.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.common.database.core.DbName;
import tech.qiantong.qdata.common.database.core.DbTable;
import tech.qiantong.qdata.common.database.core.HiveEndpointIdentity;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import tech.qiantong.qdata.common.database.exception.DataQueryException;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.datasource.dto.DatasourceCreaTeTableListReqDTO;
import tech.qiantong.qdata.module.da.api.datasource.dto.DatasourceCreaTeTableReqDTO;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import tech.qiantong.qdata.module.da.dal.mapper.datasource.DaDatasourceMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class AiDatasourceReadService implements IDaDatasourceApiService {
    private final DaDatasourceMapper mapper;
    private final DataSourceFactory factory;
    private final DataResourceAccess access;
    private final JdbcTemplate jdbc;

    @Override
    public DaDatasourceRespDTO getDatasourceById(Long id) {
        access.requireDatasource(id);
        var source = mapper.selectById(id);
        if (source == null || !Boolean.TRUE.equals(source.getValidFlag())) {
            throw new AccessDeniedException("AI datasource is unavailable");
        }
        DaDatasourceRespDTO result = new DaDatasourceRespDTO();
        BeanUtils.copyProperties(source, result);
        return result;
    }

    private DbQueryProperty property(DaDatasourceRespDTO source) {
        return new DbQueryProperty(source.getDatasourceType(), source.getIp(), source.getPort(), source.getDatasourceConfig());
    }

    private <T> T read(DaDatasourceRespDTO source, Function<DbQuery, T> operation) {
        DbQuery query = factory.createDbQuery(property(source));
        try {
            if (!query.valid()) throw new DataQueryException("db.error.connection.fail", "Database connection failed");
            return operation.apply(query);
        } finally {
            query.close();
        }
    }

    private void authorizeTable(DaDatasourceRespDTO source, String tableName) {
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("Table name is required");
        if (!"HIVE".equalsIgnoreCase(source.getDatasourceType())) return;
        DbQueryProperty property = property(source);
        String database = HiveTableIdentity.resolve(tableName, property.getDbName()).databaseName();
        List<Long> projects = new ArrayList<>(access.visibleProjectIds());
        if (!access.isAdministrator()) projects.retainAll(jdbc.queryForList(
                "SELECT project_id FROM da_datasource_project_rel WHERE datasource_id=? AND valid_flag='1'", Long.class, source.getId()));
        if (projects.isEmpty()) throw new AccessDeniedException("AI Hive datasource has no authorized project");
        if (Objects.equals(database, property.getDbName()) && jdbc.queryForObject(
                "SELECT COUNT(*) FROM dp_warehouse_namespace WHERE database_name=?", Long.class, database) == 0) return;
        String placeholders = String.join(",", Collections.nCopies(projects.size(), "?"));
        String endpoint = HiveEndpointIdentity.fingerprint(property);
        List<Object> arguments = new ArrayList<>();
        arguments.add(endpoint); arguments.add(database); arguments.addAll(projects);
        Long owned = jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_namespace n JOIN dp_warehouse_engine e ON e.id=n.engine_id JOIN dp_warehouse_space s ON s.id=n.space_id WHERE e.connection_fingerprint=? AND n.database_name=? AND s.project_id IN (" + placeholders + ")", Long.class, arguments.toArray());
        if (owned != null && owned > 0) return;
        arguments = new ArrayList<>(); arguments.add(database); arguments.addAll(projects);
        arguments.add(source.getId()); arguments.add(endpoint);
        Long sparkOwned = jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_namespace n JOIN dp_warehouse_space s ON s.id=n.space_id WHERE n.database_name=? AND s.project_id IN (" + placeholders + ") AND EXISTS (SELECT 1 FROM dp_warehouse_batch b WHERE b.space_id=s.id AND b.status='SUCCESS' AND JSON_CONTAINS(b.request_json,JSON_OBJECT('computeEngine','KYUUBI_SPARK','computeDatasourceId',?,'computeEndpointFingerprint',?)))", Long.class, arguments.toArray());
        if (sparkOwned == null || sparkOwned == 0) throw new AccessDeniedException("AI Hive database belongs to another project");
    }

    @Override
    public List<DbColumn> getDbTableColumns(Long sourceId, String tableName) {
        DaDatasourceRespDTO source = getDatasourceById(sourceId);
        authorizeTable(source, tableName);
        return read(source, query -> query.getTableColumns(property(source), tableName));
    }

    @Override
    public DbTable getDbTable(Long sourceId, String tableName) {
        DaDatasourceRespDTO source = getDatasourceById(sourceId);
        authorizeTable(source, tableName);
        return read(source, query -> query.getTables(property(source)).stream()
                .filter(table -> Objects.equals(tableName, table.getTableName())).findFirst().orElse(null));
    }

    @Override
    public List<DbName> getDatabaseListByDatasourceId(Long sourceId) {
        DaDatasourceRespDTO source = getDatasourceById(sourceId);
        return read(source, query -> query.getDbNames(null).stream().filter(database -> {
            try { authorizeTable(source, database.getDbName() + ".__qdata_authorization__"); return true; }
            catch (AccessDeniedException denied) { return false; }
        }).toList());
    }

    @Override
    public List<DaDatasourceRespDTO> getDatabaseListByIds(List<Long> ids) {
        return ids.stream().map(this::getDatasourceById).toList();
    }

    @Override
    public boolean creaDatasourceTeTableApi(DatasourceCreaTeTableReqDTO request) {
        throw new UnsupportedOperationException("Create warehouse tables through qdata-server");
    }

    @Override
    public boolean creaDatasourceTeTableApi(DbQuery query, DbQueryProperty property, DatasourceCreaTeTableReqDTO request) {
        throw new UnsupportedOperationException("Create warehouse tables through qdata-server");
    }

    @Override
    public boolean creaDatasourceTeTableListApi(DatasourceCreaTeTableListReqDTO request) {
        throw new UnsupportedOperationException("Create warehouse tables through qdata-server");
    }
}
