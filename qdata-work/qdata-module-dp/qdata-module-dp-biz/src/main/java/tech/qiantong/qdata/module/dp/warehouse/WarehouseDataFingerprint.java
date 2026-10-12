package tech.qiantong.qdata.module.dp.warehouse;

import com.alibaba.fastjson2.JSON;
import tech.qiantong.qdata.common.database.core.HiveTableIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Order-independent full-row fingerprint that preserves duplicate multiplicity. */
final class WarehouseDataFingerprint {
    private WarehouseDataFingerprint() { }
    record Value(long rows,String sha256) { }
    static Value read(Connection connection,List<String> tables,List<WarehouseDefinition.Column> columns,
                      LocalDate from,LocalDate through,long maxRows) throws SQLException {
        List<String> rowHashes=new ArrayList<>();
        String fields=columns.stream().map(c->WarehouseSql.id(c.getName())).collect(Collectors.joining(","));
        String filter=window(columns,from,through);
        for(String name:tables) {
            String table=HiveTableIdentity.resolve(name,null).qualified();
            try(Statement statement=connection.createStatement()) {
                statement.setQueryTimeout(300);statement.setFetchSize(1000);
                try(ResultSet result=statement.executeQuery("SELECT "+fields+" FROM "+table+filter)) {
                    while(result.next()) {
                        if(rowHashes.size()>=maxRows)throw new IllegalArgumentException("输入记录超过当前批次验证容量，请缩小业务窗口");
                        List<String> row=new ArrayList<>(columns.size());for(int i=1;i<=columns.size();i++)row.add(result.getString(i));
                        rowHashes.add(sha(JSON.toJSONString(row)));
                    }
                }
            }
        }
        Collections.sort(rowHashes);
        String schema=columns.stream().map(c->c.getName().toLowerCase(Locale.ROOT)+":"+WarehouseSql.type(c.getType())).collect(Collectors.joining("|"));
        return new Value(rowHashes.size(),sha(schema+"\n"+String.join("\n",rowHashes)));
    }
    static String window(List<WarehouseDefinition.Column> columns,LocalDate from,LocalDate through) {
        if(from==null && through==null)return "";
        if(from==null || through==null || from.isAfter(through))throw new IllegalArgumentException("业务窗口无效");
        if(columns.stream().noneMatch(c->"data_date".equalsIgnoreCase(c.getName())))throw new IllegalArgumentException("窗口快照需要原始模型的 data_date 字段");
        return " WHERE `data_date` >= '"+from+"' AND `data_date` <= '"+through+"'";
    }
    static String sha(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
