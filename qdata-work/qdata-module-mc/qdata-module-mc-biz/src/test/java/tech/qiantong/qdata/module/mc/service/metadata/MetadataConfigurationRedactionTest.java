package tech.qiantong.qdata.module.mc.service.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.mc.controller.admin.metadata.vo.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataConfigurationRedactionTest {
    final String config="{\"dbname\":\"warehouse\",\"username\":\"catalog\",\"password\":\"fixture-only\",\"keySecret\":\"fixture-key\",\"token\":\"fixture-token\"}";
    MetadataResourceAccess access(){var resources=mock(DataResourceAccess.class);when(resources.isAdministrator()).thenReturn(true);return new MetadataResourceAccess(mock(JdbcTemplate.class),resources,mock(MetadataProjectAccess.class));}
    McDbRespVO database(){var db=new McDbRespVO();db.setId(1L);db.setDatasourceId(9L);db.setDatasourceConfig(config);var source=new DaDatasourceRespDTO();source.setDatasourceConfig(config);db.setDatasource(source);return db;}
    void safe(McDbRespVO value){assertTrue(value.getDatasourceConfig().contains("warehouse"));assertFalse(value.getDatasourceConfig().contains("fixture-"));assertFalse(value.getDatasource().getDatasourceConfig().contains("fixture-"));}
    @Test void administratorsAlsoReceiveCatalogConfigurationWithoutSecrets(){safe(access().present(database()));}
    @Test void tableNestedDatabaseAndSourceAreSanitized(){var table=new McTableRespVO();table.setDbRespVO(database());safe(access().present(table).getDbRespVO());}
    @Test void columnNestedTableDatabaseAndSourceAreSanitized(){var table=new McTableRespVO();table.setDbRespVO(database());var column=new McColumnRespVO();column.setTableRespVO(table);safe(access().present(column).getTableRespVO().getDbRespVO());}
    @Test void presentationDoesNotModifyStoredMetadataObjects(){var db=database();safe(access().present(db));assertEquals(config,db.getDatasourceConfig());assertEquals(config,db.getDatasource().getDatasourceConfig());}
    @Test void malformedConfigurationsDoNotExposeTheirContents(){var db=database();db.setDatasourceConfig("invalid fixture-only");db.getDatasource().setDatasourceConfig("invalid fixture-key");var output=access().present(db);assertEquals("{}",output.getDatasourceConfig());assertEquals("{}",output.getDatasource().getDatasourceConfig());}
}
