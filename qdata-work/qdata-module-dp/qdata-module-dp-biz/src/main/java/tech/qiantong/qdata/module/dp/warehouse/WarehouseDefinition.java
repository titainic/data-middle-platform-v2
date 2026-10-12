package tech.qiantong.qdata.module.dp.warehouse;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** Versioned logical definition. Credentials are always resolved from the datasource. */
@Data
public class WarehouseDefinition {
    private Long id;
    private int revision;
    private String name;
    private String tableName;
    private String databaseName;
    private Long spaceId;
    private Long datasetId;
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private String managedBatchId;
    private String layer = "ODS";
    private String role = "DETAIL";
    private Long datasourceId;
    private String grain;
    private String topic;
    private String description;
    private String storage = "PARQUET";
    private String rawTable;
    private String selectSql;
    private List<Long> upstreamIds = new ArrayList<>();
    private List<Column> columns = new ArrayList<>();
    private List<Relation> dimensions = new ArrayList<>();

    @Data
    public static class Column {
        private String name;
        private String type = "STRING";
        private String comment;
        private boolean partition;
        private boolean key;
        private String source; // Human-readable source field / business expression.
    }
    @Data
    public static class Relation {
        private String column;
        private Long modelId;
        private String targetColumn;
    }
}
