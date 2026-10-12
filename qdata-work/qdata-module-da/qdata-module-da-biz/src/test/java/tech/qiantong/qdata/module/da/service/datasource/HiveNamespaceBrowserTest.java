package tech.qiantong.qdata.module.da.service.datasource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.common.database.DataSourceFactory;
import tech.qiantong.qdata.common.database.DbQuery;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.DbTable;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HiveNamespaceBrowserTest {
    @Test void querySourcesAreAuthorizedBeforeAnyHiveConnectionIsOpened() {
        var factory=mock(DataSourceFactory.class);var sources=mock(IDaDatasourceService.class);
        var source=new tech.qiantong.qdata.module.da.dal.dataobject.datasource.DaDatasourceDO();source.setDatasourceType("Hive");
        when(sources.getDaDatasourceById(9L)).thenReturn(source);
        var service=spy(new HiveNamespaceBrowser(mock(JdbcTemplate.class),sources,factory));
        doReturn(property()).when(service).property(9L);doReturn(Set.of("ods_iot_db","dwd_iot_db")).when(service).allowed(9L,"qdata_test");
        assertThrows(IllegalArgumentException.class,()->service.authorizeQuery(9L,"SELECT * FROM private_project.water_quality"));
        assertDoesNotThrow(()->service.authorizeQuery(9L,"SELECT * FROM ods_iot_db.water_quality"));
        verifyNoInteractions(factory);
    }
    DbQueryProperty property(){return new DbQueryProperty("Hive","host","hadoop","",10000,"qdata_test",null);}
    @Test void rejectsNamespaceBeforeOpeningHiveConnection() {
        var factory=mock(DataSourceFactory.class);
        var service=spy(new HiveNamespaceBrowser(mock(JdbcTemplate.class),mock(IDaDatasourceService.class),factory));
        doReturn(property()).when(service).property(9L);
        doReturn(Set.of("ods_iot_db")).when(service).allowed(9L,"qdata_test");
        assertThrows(IllegalArgumentException.class,()->service.tables(9L,"dwd_iot_db"));
        assertThrows(IllegalArgumentException.class,()->service.tables(9L,"ods_iot_db;SELECT 1"));
        verifyNoInteractions(factory);
    }
    @Test void listsOnlyVisibleAuthorizedDatabasesAndClosesConnection() {
        var factory=mock(DataSourceFactory.class);var query=mock(DbQuery.class);
        var service=spy(new HiveNamespaceBrowser(mock(JdbcTemplate.class),mock(IDaDatasourceService.class),factory));
        doReturn(property()).when(service).property(9L);
        doReturn(new LinkedHashSet<>(List.of("qdata_test","ods_iot_db","dwd_iot_db"))).when(service).allowed(9L,"qdata_test");
        when(factory.createDbQuery(any())).thenReturn(query);
        when(query.queryList("SHOW DATABASES")).thenReturn(List.of(Map.of("database_name","ods_iot_db"),Map.of("database_name","private_project")));
        assertEquals(List.of("ods_iot_db"),service.databases(9L));verify(query).close();
    }
    @Test void sameShortTableNameRetainsSelectedDatabase() {
        var factory=mock(DataSourceFactory.class);var query=mock(DbQuery.class);
        var service=spy(new HiveNamespaceBrowser(mock(JdbcTemplate.class),mock(IDaDatasourceService.class),factory));
        doAnswer(x->property()).when(service).property(9L);
        doReturn(Set.of("ods_iot_db","dwd_iot_db")).when(service).allowed(9L,"qdata_test");
        when(factory.createDbQuery(any())).thenReturn(query);
        when(query.getTables(any(DbQueryProperty.class))).thenAnswer(x->{var table=new DbTable();table.setTableName("water_quality");return List.of(table);});
        assertEquals("ods_iot_db.water_quality",service.tables(9L,"ods_iot_db").get(0).getTableName());
        assertEquals("dwd_iot_db.water_quality",service.tables(9L,"dwd_iot_db").get(0).getTableName());
        verify(factory).createDbQuery(argThat(p->p.getDbName().equals("ods_iot_db")));
        verify(factory).createDbQuery(argThat(p->p.getDbName().equals("dwd_iot_db")));
        verify(query,times(2)).close();
    }
    @Test void failedMetadataRequestStillClosesConnection() {
        var factory=mock(DataSourceFactory.class);var query=mock(DbQuery.class);
        var service=spy(new HiveNamespaceBrowser(mock(JdbcTemplate.class),mock(IDaDatasourceService.class),factory));
        doReturn(property()).when(service).property(9L);doReturn(Set.of("ods_iot_db")).when(service).allowed(9L,"qdata_test");
        when(factory.createDbQuery(any())).thenReturn(query);when(query.getTables(any(DbQueryProperty.class))).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,()->service.tables(9L,"ods_iot_db"));verify(query).close();
    }
    @Test void noAssignedProjectCannotBrowseConnectionDefault() {
        var jdbc=mock(JdbcTemplate.class);var service=new HiveNamespaceBrowser(jdbc,mock(IDaDatasourceService.class),mock(DataSourceFactory.class));
        try(var security=mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(2L);
            assertThrows(IllegalArgumentException.class,()->service.allowed(9L,"qdata_test"));
        }
    }
}
