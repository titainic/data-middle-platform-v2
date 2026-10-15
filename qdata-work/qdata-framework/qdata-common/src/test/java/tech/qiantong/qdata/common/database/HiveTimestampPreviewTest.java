package tech.qiantong.qdata.common.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.hive.jdbc.HiveBaseResultSet;
import org.apache.hive.service.cli.TableSchema;
import org.apache.hadoop.hive.serde2.thrift.Type;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.database.dialect.HiveDialect;
import tech.qiantong.qdata.common.database.dialect.MySqlDialect;
import tech.qiantong.qdata.common.database.query.AbstractDbQueryFactory;
import tech.qiantong.qdata.common.database.query.HiveRowMapper;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HiveTimestampPreviewTest {
    // Real Hive JDBC 4.2 conversion and metadata; only the network cursor is absent.
    private ResultSet hiveRow(String type, String value) throws Exception {
        var rs = mock(HiveBaseResultSet.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(rs, "row", new Object[]{value});
        ReflectionTestUtils.setField(rs, "columnNames", List.of("event_time"));
        ReflectionTestUtils.setField(rs, "columnTypes", List.of(type));
        ReflectionTestUtils.setField(rs, "columnAttributes", Collections.singletonList(null));
        var schema = new TableSchema();
        schema.addPrimitiveColumn("event_time", "date".equals(type) ? Type.DATE_TYPE : Type.TIMESTAMP_TYPE, "");
        ReflectionTestUtils.setField(rs, "schema", schema);
        return rs;
    }

    @Test void driverTimestampSurvivesShanghaiJsonWithoutAnEightHourShift() throws Exception {
        for (String clock : List.of("08:00:00", "08:05:00", "08:10:00", "23:59:59.123456789")) {
            var rs = hiveRow("timestamp", "2024-01-01 " + clock);
            assertInstanceOf(Timestamp.class, rs.getObject(1));
            String expected = rs.getString(1);
            var mapped = new HiveRowMapper().mapRow(rs, 0);
            var json = new ObjectMapper().setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            assertEquals(expected, json.readTree(json.writeValueAsString(mapped)).get("event_time").asText());
            assertTrue(expected.startsWith("2024-01-01 " + clock));
        }
    }

    @Test void sqlNullAndBusinessDateStayUnzoned() throws Exception {
        var mapped = new HiveRowMapper().mapRow(hiveRow("timestamp", null), 0);
        assertTrue(mapped.containsKey("event_time"));
        assertNull(mapped.get("event_time"));
        assertEquals("2024-02-29", new HiveRowMapper().mapRow(hiveRow("date", "2024-02-29"), 0).get("event_time"));
    }

    @Test void integersAndDecimalsKeepTheirExistingTypesAndPrecision() throws Exception {
        var rs = mock(ResultSet.class);
        var meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("data_timestamp");
        when(meta.getColumnLabel(2)).thenReturn("amount");
        when(meta.getColumnType(1)).thenReturn(Types.BIGINT);
        when(meta.getColumnType(2)).thenReturn(Types.DECIMAL);
        when(rs.getObject(1)).thenReturn(1704067200000L);
        var amount = new java.math.BigDecimal("123456789012345678.123456789");
        when(rs.getObject(2)).thenReturn(amount);
        var mapped = new HiveRowMapper().mapRow(rs, 0);
        assertEquals(1704067200000L, mapped.get("data_timestamp"));
        assertSame(amount, mapped.get("amount"));
    }

    @Test void plainQueriesUseHiveMappingWhileOtherEnginesKeepJdbcValues() throws Exception {
        var query = mock(AbstractDbQueryFactory.class, CALLS_REAL_METHODS);
        var jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(query, "jdbcTemplate", jdbc);
        var rs = hiveRow("timestamp", "2024-01-01 08:00:00");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
        query.setDbDialect(new HiveDialect());
        assertEquals(rs.getString(1), query.queryList("SELECT event_time FROM fixture").get(0).get("event_time"));
        query.setDbDialect(new MySqlDialect());
        assertInstanceOf(Timestamp.class, query.queryList("SELECT event_time FROM fixture").get(0).get("event_time"));
    }

    @Test void wrappedPaginationKeepsBothLocalTimeAndStableColumnLabels() throws Exception {
        var query = mock(AbstractDbQueryFactory.class, CALLS_REAL_METHODS);
        query.setDbDialect(new HiveDialect());
        var jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(query, "jdbcTemplate", jdbc);
        doReturn(3).when(query).count(anyString());
        var rs = hiveRow("timestamp", "2024-01-01 08:05:00");
        ReflectionTestUtils.setField(rs, "columnNames", List.of("qdata_page.event_time"));
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper<?>) call.getArgument(1)).mapRow(rs, 0)));
        var row = query.queryByPage("SELECT event_time FROM fixture", 1, 1).getData().get(0);
        assertEquals(Map.of("event_time", rs.getString(1)), row);
    }

    @Test void timestampsWithAnExplicitTimeZoneKeepTheirExistingSemantics() throws Exception {
        var rs = mock(ResultSet.class);
        var meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("instant");
        when(meta.getColumnType(1)).thenReturn(Types.TIMESTAMP_WITH_TIMEZONE);
        var value = java.time.OffsetDateTime.parse("2024-01-01T08:00:00+08:00");
        when(rs.getObject(1)).thenReturn(value);
        assertSame(value, new HiveRowMapper().mapRow(rs, 0).get("instant"));
        verify(rs, never()).getString(anyInt());
    }
}
