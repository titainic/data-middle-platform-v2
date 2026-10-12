package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;
import java.util.*;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL metadata checks in a retained isolated schema; no Hive execution. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION", matches="true")
class WarehouseNamespaceIntegrationTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private WarehouseSpaceService spaces;
    private IDaDatasourceApiService sources;
    private DataSourceFactory factory;

    @BeforeEach void setup() {
        String url = System.getenv("QDATA_NAMESPACE_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:3307/qdata_namespace_acceptance_[a-zA-Z0-9_]+(?:\\?.*)?"),
                "Integration tests require a dedicated namespace acceptance schema");
        var datasource = new DriverManagerDataSource(url, "root", System.getenv("QDATA_NAMESPACE_PASSWORD"));
        datasource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new JdbcTemplate(datasource);
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("qdata_namespace_acceptance_"));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        sources = mock(IDaDatasourceApiService.class);
        factory = mock(DataSourceFactory.class);
        when(sources.getDatasourceById(9L)).thenAnswer(call -> {
            var row = jdbc.queryForMap("SELECT id,ip,port,datasource_type,datasource_config FROM da_datasource WHERE id=9");
            var source = new DaDatasourceRespDTO(); source.setId(9L); source.setIp((String)row.get("ip"));
            source.setPort(((Number)row.get("port")).longValue()); source.setDatasourceType((String)row.get("datasource_type"));
            source.setDatasourceConfig((String)row.get("datasource_config")); return source;
        });
        spaces = new WarehouseSpaceService(jdbc, transaction, sources, factory);
        var source = sources.getDatasourceById(9L);
        var property = new DbQueryProperty(source.getDatasourceType(), source.getIp(), source.getPort(), source.getDatasourceConfig());
        jdbc.update("INSERT IGNORE INTO dp_warehouse_engine(id,name,datasource_id,catalog_key,connection_fingerprint,create_by) VALUES (991,'fixture Hive',9,'fixture_hive',?,'integration')",
                WarehouseSpaceService.connectionFingerprint(property));
    }

    private MockedStatic<SecurityUtils> actor(long id, boolean manager) {
        var security = mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(id);
        security.when(SecurityUtils::getUsername).thenReturn("namespace-integration-" + id);
        for (String permission : List.of("dp:model:edit", "da:dataSource:edit", "att:project:edit"))
            security.when(() -> SecurityUtils.hasPermi(permission)).thenReturn(manager);
        return security;
    }

    @Test void unrelatedTableCannotAttachToAnExistingDatasetIdentity() {
        String code="dataset_guard_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO dp_warehouse_dataset(space_id,code,name,layer_code,create_by) VALUES (1,?,'Dataset guard fixture','DWT','integration')",code);
        var existing=jdbc.queryForMap("SELECT id,space_id,code,layer_code FROM dp_warehouse_dataset WHERE code=?",code);
        var space=new WarehouseSpaceService.Space();
        space.setId(((Number)existing.get("space_id")).longValue());
        var candidate=new WarehouseDefinition();
        candidate.setId(999999999L);
        candidate.setDatasetId(((Number)existing.get("id")).longValue());
        candidate.setLayer((String)existing.get("layer_code"));
        candidate.setTableName(existing.get("code")+"_unrelated_p6");
        candidate.setDatabaseName("isolated_test_db");
        assertThrows(IllegalArgumentException.class,()->spaces.registerMaterialization(candidate,space,"test"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_materialization WHERE model_id=999999999",Integer.class));
        candidate.setId(null);
        candidate.setName("Unrelated dataset validation fixture");
        candidate.setDatasourceId(9L);
        try(var ignored=actor(2,true)) {
            var service=new WarehouseService(jdbc,transaction,sources,factory,spaces);
            try {assertThrows(IllegalArgumentException.class,()->service.validate(candidate));}
            finally {service.close();}
        }
    }
    @Test void dnsAliasCannotRegisterAnotherEngineForTheSameDirectHiveAddress() {
        var template=sources.getDatasourceById(9L);
        String fingerprint=WarehouseSpaceService.connectionFingerprint(
                new DbQueryProperty("Hive","127.0.0.1",10000L,template.getDatasourceConfig()));
        var existing=jdbc.queryForList("SELECT datasource_id FROM dp_warehouse_engine WHERE connection_fingerprint=? LIMIT 1",
                Long.class,fingerprint);
        long directId=existing.isEmpty()?Math.abs(System.nanoTime()):existing.get(0);
        long aliasId=directId+1;
        var direct=new DaDatasourceRespDTO();direct.setId(directId);direct.setDatasourceType("Hive");
        direct.setIp("127.0.0.1");direct.setPort(10000L);direct.setDatasourceConfig(template.getDatasourceConfig());
        var alias=new DaDatasourceRespDTO();alias.setId(aliasId);alias.setDatasourceType("Hive");
        alias.setIp("localhost");alias.setPort(10000L);alias.setDatasourceConfig(template.getDatasourceConfig());
        when(sources.getDatasourceById(directId)).thenReturn(direct);
        when(sources.getDatasourceById(aliasId)).thenReturn(alias);
        if(existing.isEmpty())
            jdbc.update("INSERT INTO dp_warehouse_engine(id,name,datasource_id,catalog_key,connection_fingerprint,create_by) VALUES (?,?,?,?,?,?)",
                    directId,"alias reference",directId,"alias_reference_"+directId,fingerprint,"integration");
        var candidate=new WarehouseSpaceService.Engine();candidate.setName("alias candidate");
        candidate.setDatasourceId(aliasId);candidate.setCatalogKey("alias_candidate_"+aliasId);
        try(var security=actor(2,true)) {
            security.when(SecurityUtils::isGlobalAdministrator).thenReturn(true);
            var failure=assertThrows(IllegalArgumentException.class,()->spaces.registerEngine(candidate));
            assertTrue(failure.getMessage().contains("同一服务"));
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_engine WHERE datasource_id=?",Integer.class,aliasId));
        verifyNoInteractions(factory);
    }
    @Test void differentHiveServerAddressesCannotRegisterTheSameMetastore() throws Exception {
        long candidateId = 999990L;
        var template = sources.getDatasourceById(9L);
        var candidateSource = new DaDatasourceRespDTO();
        candidateSource.setId(candidateId);
        candidateSource.setDatasourceType("Hive");
        candidateSource.setIp("198.51.100.6");
        candidateSource.setPort(10000L);
        candidateSource.setDatasourceConfig(template.getDatasourceConfig());
        when(sources.getDatasourceById(candidateId)).thenReturn(candidateSource);
        for (Long registeredSource : jdbc.queryForList(
                "SELECT datasource_id FROM dp_warehouse_engine", Long.class)) {
            if (registeredSource != 9L && registeredSource != candidateId)
                when(sources.getDatasourceById(registeredSource)).thenReturn(template);
        }

        DbQuery candidateQuery = mock(DbQuery.class);
        DbQuery existingQuery = mock(DbQuery.class);
        Connection candidateConnection = mock(Connection.class);
        Connection existingConnection = mock(Connection.class);
        Statement candidateStatement = mock(Statement.class);
        Statement existingStatement = mock(Statement.class);
        ResultSet connectionRows = mock(ResultSet.class);
        ResultSet candidateMetastore = mock(ResultSet.class);
        ResultSet existingMetastore = mock(ResultSet.class);
        when(factory.createDbQuery(any())).thenReturn(candidateQuery, existingQuery);
        when(candidateQuery.getConnection()).thenReturn(candidateConnection);
        when(existingQuery.getConnection()).thenReturn(existingConnection);
        when(candidateConnection.createStatement()).thenReturn(candidateStatement);
        when(existingConnection.createStatement()).thenReturn(existingStatement);
        when(candidateStatement.executeQuery("SELECT current_database(), current_user()"))
                .thenReturn(connectionRows);
        when(connectionRows.next()).thenReturn(true);
        when(candidateStatement.executeQuery("SET hive.metastore.uris"))
                .thenReturn(candidateMetastore);
        when(existingStatement.executeQuery("SET hive.metastore.uris"))
                .thenReturn(existingMetastore);
        when(candidateMetastore.next()).thenReturn(true);
        when(existingMetastore.next()).thenReturn(true);
        when(candidateMetastore.getString(1)).thenReturn(
                "hive.metastore.uris=thrift://shared-metastore.example:9083");
        when(existingMetastore.getString(1)).thenReturn(
                "hive.metastore.uris=thrift://shared-metastore.example:9083");

        var engine = new WarehouseSpaceService.Engine();
        engine.setName("NAT alias candidate");
        engine.setDatasourceId(candidateId);
        engine.setCatalogKey("nat_alias_candidate_" + candidateId);
        try (var security = actor(2, true)) {
            security.when(SecurityUtils::isGlobalAdministrator).thenReturn(true);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> spaces.registerEngine(engine));
            assertTrue(failure.getMessage().contains("共享元数据服务"), failure.getMessage());
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dp_warehouse_engine WHERE datasource_id=?",
                Integer.class, candidateId));
    }

    private WarehouseSpaceService.Space definition(String code, long project) {
        var space = new WarehouseSpaceService.Space();
        space.setName(code); space.setProjectId(project); space.setEnvironmentCode("test"); space.setEngineId(991L);
        Map<String,String> databases = new LinkedHashMap<>();
        WarehouseSql.LAYERS.forEach(layer -> databases.put(layer, code + "_" + layer.toLowerCase(Locale.ROOT)));
        space.setDatabases(databases); return space;
    }

    @Test void projectManagerCanManageWithoutAnAdminNamedRole() {
        try (var ignored = actor(2, true)) {
            assertFalse(spaces.administrator()); assertTrue(spaces.canManage());
            assertTrue(spaces.legacyModelIds().contains(13L));
            var created = spaces.createSpace(definition("allowed_" + UUID.randomUUID().toString().replace("-", ""), 1));
            assertEquals(9L, created.getDatasourceId());
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_namespace WHERE space_id=?", Integer.class, created.getId()));
            spaces.authorize(created.getId());
        }
        verifyNoInteractions(factory);
    }

    @Test void projectMembershipDoesNotGrantAnotherProjectsConnection() {
        try (var ignored = actor(2, true)) {
            assertTrue(spaces.projects().stream().anyMatch(p -> ((Number)p.get("id")).longValue() == 2));
            assertThrows(IllegalArgumentException.class, () -> spaces.createSpace(definition("unassigned_source", 2)));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_space WHERE name='unassigned_source'", Integer.class));
        }
    }

    @Test void outsideUserCannotListOrClaimLegacyModels() {
        try (var ignored = actor(999999, true)) {
            assertTrue(spaces.projects().isEmpty()); assertTrue(spaces.legacyModelIds().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> spaces.authorizeLegacy(13L));
            assertThrows(IllegalArgumentException.class, () -> spaces.createSpace(definition("outside_scope", 1)));
        }
    }

    @Test void dataModelEditAloneDoesNotGrantNamespaceAdministration() {
        try (var security = actor(2, false)) {
            security.when(() -> SecurityUtils.hasPermi("dp:model:edit")).thenReturn(true);
            assertFalse(spaces.canManage());
            assertThrows(IllegalArgumentException.class, () -> spaces.createSpace(definition("no_management", 1)));
        }
    }

    @Test void namespaceConflictRollsBackTheWholeSpace() {
        try (var ignored = actor(2, true)) {
            String prefix = "collision_" + UUID.randomUUID().toString().replace("-", "");
            var first = spaces.createSpace(definition(prefix, 1));
            var second = definition(prefix + "_second", 1); second.setDatabases(first.getDatabases());
            assertThrows(IllegalArgumentException.class, () -> spaces.createSpace(second));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_space WHERE name=?", Integer.class, second.getName()));
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM dp_warehouse_namespace WHERE space_id=?", Integer.class, first.getId()));
        }
    }

    @Test void descriptiveModelEditsPreserveColumnIdsAndStandards() {
        try (var ignored = actor(2, true)) {
            var model = JSON.parseObject(jdbc.queryForObject("SELECT warehouse_config FROM dp_model WHERE id=13", String.class), WarehouseDefinition.class);
            model.setId(13L); model.setName("Metadata reference verification");
            var before = jdbc.queryForList("SELECT id,eng_name,data_elem_id FROM dp_model_column WHERE model_id=13 AND del_flag='0' ORDER BY id");
            var service = new WarehouseService(jdbc, transaction, sources, factory, spaces);
            try { service.save(model, "namespace-integration-2", 2L); }
            finally { service.close(); }
            var after = jdbc.queryForList("SELECT id,eng_name,data_elem_id FROM dp_model_column WHERE model_id=13 AND del_flag='0' ORDER BY id");
            assertEquals(before, after);
            assertEquals("qdata_test", jdbc.queryForObject("SELECT database_name FROM dp_model WHERE id=13", String.class));
        }
        verifyNoInteractions(factory);
    }
    @Test void twoApplicationInstancesCannotCommitTheSameModelRevision() throws Exception {
        var first=spy(new WarehouseService(jdbc,transaction,sources,factory,spaces));
        var second=spy(new WarehouseService(jdbc,transaction,sources,factory,spaces));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            WarehouseDefinition original;
            try(var ignored=actor(2,true)) {
                var space=spaces.createSpace(definition("concurrent_"+UUID.randomUUID().toString().replace("-",""),1));
                original=new WarehouseDefinition();original.setName("Concurrent save fixture");original.setSpaceId(space.getId());
                original.setLayer("ODS");original.setRole("DETAIL");original.setDatasourceId(9L);original.setTableName("concurrent_source");original.setGrain("one observation");
                var column=new WarehouseDefinition.Column();column.setName("device_id");column.setType("STRING");column.setSource("raw.device_id");original.getColumns().add(column);
                original=first.save(original,"concurrency-test",2L);
            }
            final String initial=JSON.toJSONString(original);long id=original.getId();
            var columns=jdbc.queryForList("SELECT id,eng_name FROM dp_model_column WHERE model_id=? ORDER BY id",id);
            var validated=new java.util.concurrent.CountDownLatch(2);
            for(var service:List.of(first,second))doAnswer(call->{Object result=call.callRealMethod();validated.countDown();return result;}).when(service).validate(any());
            List<java.util.concurrent.Future<Boolean>> results=new ArrayList<>();
            try(var lock=jdbc.getDataSource().getConnection()) {
                lock.setAutoCommit(false);
                try(var statement=lock.prepareStatement("SELECT id FROM dp_model WHERE id=? FOR UPDATE")) {
                    statement.setLong(1,id);try(var held=statement.executeQuery()){assertTrue(held.next());}
                }
                for(var service:List.of(first,second))results.add(pool.submit(()->{
                    try(var ignored=actor(2,true)) {
                        var edit=JSON.parseObject(initial,WarehouseDefinition.class);edit.setDescription("editor-"+Thread.currentThread().threadId());
                        try {service.save(edit,"concurrency-test",2L);return true;}
                        catch(IllegalArgumentException conflict){assertTrue(conflict.getMessage().contains("其他用户修改"));return false;}
                    }
                }));
                assertTrue(validated.await(15,java.util.concurrent.TimeUnit.SECONDS));
                Thread.sleep(250);lock.commit();
            }
            int committed=0;for(var result:results)if(result.get(15,java.util.concurrent.TimeUnit.SECONDS))committed++;
            assertEquals(1,committed);assertEquals(original.getRevision()+1,first.getInternal(id).getRevision());
            assertEquals(columns,jdbc.queryForList("SELECT id,eng_name FROM dp_model_column WHERE model_id=? ORDER BY id",id));
            verifyNoInteractions(factory);
        } finally {pool.shutdownNow();first.close();second.close();}
    }
}
