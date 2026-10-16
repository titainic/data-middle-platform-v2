package tech.qiantong.qdata.datax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataXHiveJdbcMappingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void zeroRowAppendKeepsHive2UrlAndDoesNotGenerateDestructiveSql() throws Exception {
        JsonNode content = content("Hive");
        JsonNode reader = content.path("reader");
        JsonNode writer = content.path("writer");
        assertEquals("rdbmswriter", writer.path("name").asText());
        JsonNode parameters = writer.path("parameter");
        assertEquals("jdbc:hive2://example.invalid:10000/fixture_db",
                parameters.path("connection").get(0).path("jdbcUrl").asText());
        assertEquals("fixture_db.target", parameters.path("connection").get(0).path("table").get(0).asText());
        assertFalse(parameters.has("writeMode"));
        assertFalse(parameters.has("preSql"));
        assertFalse(parameters.has("postSql"));
        assertEquals("1=0", reader.path("parameter").path("where").asText());
        assertTrue(reader.path("parameter").path("connection").get(0).path("jdbcUrl").isArray());
    }

    @Test
    void mysqlStillUsesGenericWriterAndMysqlUrl() throws Exception {
        JsonNode writer = content("MySql").path("writer");
        assertEquals("rdbmswriter", writer.path("name").asText());
        assertTrue(writer.path("parameter").path("connection").get(0).path("jdbcUrl").asText()
                .startsWith("jdbc:mysql://example.invalid:3306/fixture_db"));
    }

    private JsonNode content(String writerType) throws Exception {
        Map<String, Object> reader = Map.of("readerDatasource", datasource("MySql"),
                "componentType", "1", "table_name", "source", "where", "1=0");
        Map<String, Object> writer = Map.of("writerDatasource", datasource(writerType),
                "target_table_name", "fixture_db.target", "target_columns", List.of("id", "name", "amt", "created"),
                "writeModeType", "2", "preSql", "", "postSql", "");
        return json.readTree(DataXJsonBuilder.buildJson(reader, writer, List.of())).path("job").path("content").get(0);
    }

    private String datasource(String type) throws Exception {
        return json.writeValueAsString(Map.of("datasourceType", type, "ip", "example.invalid",
                "port", "Hive".equals(type) ? 10000 : 3306, "dbname", "fixture_db",
                "datasourceConfig", json.writeValueAsString(Map.of("username", "fixture", "password", "fixture-password"))));
    }
}
