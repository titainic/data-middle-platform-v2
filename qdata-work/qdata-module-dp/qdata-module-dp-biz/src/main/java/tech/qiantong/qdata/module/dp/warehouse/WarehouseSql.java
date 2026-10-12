package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.expr.SQLCastExpr;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Hive SQL generation and fail-closed source validation using the existing SQL parser. */
public final class WarehouseSql {
    public static final List<String> LAYERS = List.of("ODS", "DWD", "DWS", "DWT", "ADS");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,127}");
    private static final Pattern TYPE = Pattern.compile("(?:STRING|BOOLEAN|TINYINT|SMALLINT|INT|BIGINT|FLOAT|DOUBLE|DATE|TIMESTAMP|BINARY|DECIMAL\\([0-9]+,[0-9]+\\)|VARCHAR\\([0-9]+\\)|CHAR\\([0-9]+\\))");
    private WarehouseSql() {}
    public static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
    public static String id(String name) {
        require(name != null && IDENTIFIER.matcher(name).matches(), "表名/字段名只允许字母、数字和下划线，且不能以数字开头：" + name);
        return "`" + name.toLowerCase(Locale.ROOT) + "`";
    }
    public static String qualified(String database, String table) { return id(database) + "." + id(table); }
    public static String canonical(String name, String database) {
        String clean = name.replace("`", "").toLowerCase(Locale.ROOT);
        String[] parts = clean.split("\\.", -1);
        require(parts.length == 1 || parts.length == 2, "仅支持 database.table 形式的 Hive 表名");
        for (String part : parts) id(part);
        return parts.length == 1 ? database.toLowerCase(Locale.ROOT) + "." + clean : clean;
    }
    public static String type(String type) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        require(TYPE.matcher(t).matches(), "不支持的 Hive 字段类型：" + type);
        if (t.startsWith("DECIMAL")) {
            String[] n = t.substring(8,t.length()-1).split(","); int p=Integer.parseInt(n[0]),s=Integer.parseInt(n[1]);
            require(p >= 1 && p <= 38 && s >= 0 && s <= p, "DECIMAL 精度需为 1–38，标度不能大于精度");
        }
        if(t.startsWith("VARCHAR") || t.startsWith("CHAR")) {
            int length=Integer.parseInt(t.substring(t.indexOf('(')+1,t.length()-1));
            require(length>0 && length <= (t.startsWith("VARCHAR")?65535:255), "CHAR/VARCHAR 长度超出 Hive 范围");
        }
        return t;
    }
    public static String literal(String text) { return "'" + (text == null ? "" : text).replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ") + "'"; }
    public static List<WarehouseDefinition.Column> ordered(WarehouseDefinition d) {
        return d.getColumns().stream().sorted(Comparator.comparing(WarehouseDefinition.Column::isPartition)).collect(Collectors.toList());
    }
    public static void validateColumns(WarehouseDefinition d) {
        require(d.getColumns()!=null && !d.getColumns().isEmpty(), "至少配置一个字段");
        Set<String> names=new HashSet<>();
        for(var c:d.getColumns()) {
            id(c.getName()); type(c.getType());
            require(names.add(c.getName().toLowerCase(Locale.ROOT)), "字段重复："+c.getName());
            require(c.getSource()!=null && !c.getSource().isBlank(), "请填写字段来源/加工规则："+c.getName());
        }
        require(d.getColumns().stream().anyMatch(c->!c.isPartition()),"至少需要一个非分区字段");
        require(Set.of("PARQUET","ORC").contains(d.getStorage()),"存储格式仅支持 PARQUET 或 ORC");
    }
    public static Set<String> readTables(String sql, String database) {
        require(sql!=null && !sql.isBlank(), "请填写 SELECT 加工 SQL");
        Set<String> tables=tech.qiantong.qdata.common.database.core.HiveReadQuery.tables(sql,database);
        validateNumericCasts(sql);
        require(!tables.isEmpty(),"加工 SQL 必须读取已登记的上一层模型");
        return tables;
    }
    /** Catch declared numeric CAST errors before any batch target is created. */
    static void validateNumericCasts(String sql) {
        var statement=SQLUtils.parseSingleStatement(sql,DbType.hive);
        require(statement instanceof SQLSelectStatement,"加工 SQL 只能包含一条 SELECT/WITH 查询");
        statement.accept(new SQLASTVisitorAdapter(){
            @Override public boolean visit(SQLCastExpr node) {
                String name=node.getDataType().getName().toUpperCase(Locale.ROOT);
                if("DECIMAL".equals(name)) {
                    var args=node.getDataType().getArguments();
                    require(args.size()==1 || args.size()==2,"DECIMAL CAST 需要明确精度及可选标度");
                    int precision=castInteger(args.get(0).toString());int scale=args.size()==2?castInteger(args.get(1).toString()):0;
                    require(precision>=1 && precision<=38 && scale>=0 && scale<=precision,"DECIMAL CAST 精度需为1–38，标度不能大于精度");
                    BigDecimal value=literalNumber(node);
                    if(value!=null){
                        BigDecimal rounded=value.setScale(scale,RoundingMode.HALF_UP);
                        int digits=Math.max(0,rounded.precision()-rounded.scale());
                        require(digits<=precision-scale,"DECIMAL CAST 常量溢出："+node);
                    }
                } else if(Set.of("TINYINT","SMALLINT","INT","INTEGER","BIGINT").contains(name)) {
                    BigDecimal value=literalNumber(node);
                    if(value!=null){
                        long bound=switch(name){case "TINYINT" -> Byte.MAX_VALUE;case "SMALLINT" -> Short.MAX_VALUE;case "INT","INTEGER" -> Integer.MAX_VALUE;default -> Long.MAX_VALUE;};
                        BigDecimal minimum=switch(name){case "TINYINT" -> BigDecimal.valueOf(Byte.MIN_VALUE);case "SMALLINT" -> BigDecimal.valueOf(Short.MIN_VALUE);case "INT","INTEGER" -> BigDecimal.valueOf(Integer.MIN_VALUE);default -> BigDecimal.valueOf(Long.MIN_VALUE);};
                        require(value.compareTo(minimum)>=0 && value.compareTo(BigDecimal.valueOf(bound))<=0,"整数 CAST 常量溢出："+node);
                    }
                } else if(Set.of("FLOAT","DOUBLE").contains(name)) {
                    BigDecimal value=literalNumber(node);
                    if(value!=null)require("FLOAT".equals(name)?Float.isFinite(value.floatValue()):Double.isFinite(value.doubleValue()),
                            "浮点 CAST 常量超出有限值范围："+node);
                }
                return true;
            }
        });
    }
    private static int castInteger(String value) {
        require(value.matches("[0-9]+"),"CAST 类型精度必须为整数常量");
        try{return Integer.parseInt(value);}catch(NumberFormatException overflow){throw new IllegalArgumentException("CAST 类型精度超出范围",overflow);}
    }
    private static BigDecimal literalNumber(SQLCastExpr node) {
        var expr=node.getExpr();String text=expr instanceof SQLCharExpr string?string.getText():expr.toString();
        if(!(expr instanceof SQLCharExpr) && !text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))return null;
        try{return new BigDecimal(text);}catch(NumberFormatException invalid){throw new IllegalArgumentException("数值 CAST 字面量不是有效数值："+node,invalid);}
    }
    /** Read-only full-row scan used before INSERT to detect data-dependent numeric CAST failures. */
    static String numericCastPreflight(String sql) {
        validateNumericCasts(sql);
        var parsed=SQLUtils.parseSingleStatement(sql,DbType.hive);
        require(parsed instanceof SQLSelectStatement,"数值 CAST 预检只允许 SELECT/WITH 查询");
        var casts=new ArrayList<SQLCastExpr>();
        parsed.accept(new SQLASTVisitorAdapter(){
            @Override public boolean visit(SQLCastExpr cast) {
                if(Set.of("DECIMAL","TINYINT","SMALLINT","INT","INTEGER","BIGINT","FLOAT","DOUBLE")
                        .contains(cast.getDataType().getName().toUpperCase(Locale.ROOT))) casts.add(cast);
                return true;
            }
        });
        if(casts.isEmpty())return null;
        Collections.reverse(casts); // Replace inner casts before their parents.
        for(var cast:casts) {
            String input=SQLUtils.toSQLString(cast.getExpr(),DbType.hive);
            String target=SQLUtils.toSQLString(cast.getDataType(),DbType.hive);
            String converted="CAST(("+input+") AS "+target+")";
            boolean floating=Set.of("FLOAT","DOUBLE").contains(cast.getDataType().getName().toUpperCase(Locale.ROOT));
            String invalid=converted+" IS NULL"+(floating
                    ?" OR UPPER(CAST("+converted+" AS STRING)) IN ('NAN','INFINITY','-INFINITY')":"");
            String guarded="CASE WHEN ("+input+") IS NOT NULL AND ("+invalid+") "
                    +"THEN CAST(assert_true(("+input+") IS NULL) AS "+target+") "
                    +"ELSE "+converted+" END";
            require(SQLUtils.replaceInParent(cast,SQLUtils.toSQLExpr(guarded,DbType.hive)),
                    "数值 CAST 无法安全生成全量预检 SQL");
        }
        String rewritten=SQLUtils.toSQLString(parsed,DbType.hive);
        require(rewritten.length()<=1_000_000 && SQLUtils.parseSingleStatement(rewritten,DbType.hive) instanceof SQLSelectStatement,
                "数值 CAST 预检 SQL 超限或不是只读查询");
        return rewritten;
    }
    public static String ddl(WarehouseDefinition d, String database) {
        validateColumns(d);
        String normal=d.getColumns().stream().filter(c->!c.isPartition()).map(WarehouseSql::column).collect(Collectors.joining(",\n  "));
        String partitions=d.getColumns().stream().filter(WarehouseDefinition.Column::isPartition).map(WarehouseSql::column).collect(Collectors.joining(", "));
        return "CREATE TABLE " + qualified(database,d.getTableName()) + " (\n  " + normal + "\n) COMMENT " + literal(d.getName())
                + (partitions.isEmpty()?"":"\nPARTITIONED BY ("+partitions+")") + "\nSTORED AS " + d.getStorage()
                + ("ORC".equals(d.getStorage()) ? "\nTBLPROPERTIES ('orc.compress'='SNAPPY')" : "");
    }
    private static String column(WarehouseDefinition.Column c) { return id(c.getName())+" "+type(c.getType())+" COMMENT "+literal(c.getComment()); }
    public static String select(WarehouseDefinition d, String database) {
        if(!"ODS".equals(d.getLayer())) return d.getSelectSql().trim().replaceAll(";+\\s*$", "");
        require(d.getRawTable()!=null && !d.getRawTable().isBlank(), "ODS 采集需要填写 Hive 原始来源表；也可仅物化后通过数据集成写入");
        String[] raw=canonical(d.getRawTable(),database).split("\\.");
        return "SELECT "+ordered(d).stream().map(c->id(c.getName())).collect(Collectors.joining(", "))+" FROM "+qualified(raw[0],raw[1]);
    }
    public static String insert(WarehouseDefinition d, String database) {
        String partitions=ordered(d).stream().filter(WarehouseDefinition.Column::isPartition).map(c->id(c.getName())).collect(Collectors.joining(", "));
        String[] query=splitWith(select(d,database));
        return query[0]+"INSERT INTO TABLE "+qualified(database,d.getTableName())
                +(partitions.isEmpty()?"":" PARTITION ("+partitions+")")+"\n"+query[1];
    }
    public static String schemaCheck(WarehouseDefinition d, String database) {
        String[] query=splitWith(select(d,database));
        return query[0]+"SELECT * FROM ("+query[1]+") warehouse_schema_check LIMIT 0";
    }
    private static String[] splitWith(String sql) {
        var statements=SQLUtils.parseStatements(sql,DbType.hive);
        require(statements.size()==1 && statements.get(0) instanceof SQLSelectStatement,"加工 SQL 只能包含一条 SELECT/WITH 查询");
        var query=((SQLSelectStatement)statements.get(0)).getSelect();
        var with=query.getWithSubQuery();
        if(with==null) return new String[]{"",sql};
        // Hive requires WITH at statement scope, before INSERT or a schema-check SELECT.
        // A WITH clause nested inside a subquery is rejected by Hive's parser.
        query.setWithSubQuery(null);
        return new String[]{SQLUtils.toSQLString(with,DbType.hive)+"\n",SQLUtils.toSQLString(query,DbType.hive)};
    }
}
