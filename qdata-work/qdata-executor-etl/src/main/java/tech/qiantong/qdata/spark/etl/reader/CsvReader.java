/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.spark.etl.reader;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.*;
import tech.qiantong.qdata.common.enums.TaskComponentTypeEnum;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.spark.etl.utils.LogUtils;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat;

/**
 * <P>
 * Purpose: csv input
 * </p>
 *
 * @author: FXB
 * @create: 2025-04-21 13:34
 **/
@Slf4j
public class CsvReader implements Reader {

    @Override
    public Dataset<Row> read(SparkSession spark, JSONObject reader,List<String> readerColumns,LogUtils.Params logParams) {
        LogUtils.writeLog(logParams, "*********************************  Initialize task context  ***********************************");
        LogUtils.writeLog(logParams, MessageUtils.messageEn("etl.reader.csv.start"));
        LogUtils.writeLog(logParams, MessageUtils.messageEn("etl.task.start.time", DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss.SSS")));
        LogUtils.writeLog(logParams, MessageUtils.messageEn("etl.task.parameters", reader.toJSONString(PrettyFormat)));
        //Parameter information
        JSONObject parameter = reader.getJSONObject("parameter");
        //Field
        List<Object> column = parameter.getJSONArray("column");
        //csv file path
        String path = parameter.getString("path");

        Dataset<Row> dataset = loadCsv(spark,path,column.stream().map(value->(JSONObject)value).collect(Collectors.toList()));
        readerColumns.addAll(column.stream().map(c -> ((JSONObject) c).getString("columnName")).collect(Collectors.toList()));
        LogUtils.writeLog(logParams, MessageUtils.messageEn("etl.input.data.count", dataset.count()));
        log.info(MessageUtils.message("log.etl.sample.data"));
        dataset.na().fill("Unknown").show(10);
        // Spark 4.0 removed Dataset#showString; build the sample string from collected rows
        StringBuilder sampleData = new StringBuilder();
        for (org.apache.spark.sql.Row row : dataset.na().fill("Unknown").limit(10).collectAsList()) {
            sampleData.append(row.mkString(",")).append("\n");
        }
        LogUtils.writeLog(logParams, MessageUtils.messageEn("etl.sample.data", sampleData.toString()));
        return dataset;
    }

    /** Validate full source records before exposing a lazy Spark result to any writer. */
    public static Dataset<Row> loadCsv(SparkSession spark,String path,List<JSONObject> columns) {
        if(path==null || path.isBlank() || columns==null || columns.isEmpty())throw new IllegalArgumentException("CSV路径及读取字段不能为空");
        List<String> names=columns.stream().map(c->c.getString("columnName")).collect(Collectors.toList());
        if(names.stream().anyMatch(n->n==null || n.isBlank()) || names.stream().map(n->n.toLowerCase(java.util.Locale.ROOT)).distinct().count()!=names.size())throw new IllegalArgumentException("CSV读取字段不能为空或重复");
        spark.conf().set("dfs.client.use.datanode.hostname","true");
        var readConfiguration=new org.apache.hadoop.conf.Configuration(spark.sparkContext().hadoopConfiguration());
        readConfiguration.setBoolean("dfs.client.use.datanode.hostname",true);
        Dataset<Row> data=spark.read().format("csv").option("header","true").option("inferSchema","false")
                .option("encoding","UTF-8").option("mode","FAILFAST").option("enforceSchema","false")
                .option("multiLine","true").option("escape","\"").load(path);
        for(String file:data.inputFiles()){
            var inputPath=new org.apache.hadoop.fs.Path(file);
            try(var stream=inputPath.getFileSystem(readConfiguration).open(inputPath)){
                var codec=new org.apache.hadoop.io.compress.CompressionCodecFactory(readConfiguration).getCodec(inputPath);
                tech.qiantong.qdata.common.utils.file.StrictCsv.read(codec==null?stream:codec.createInputStream(stream),names,(headers,values)->{
                    for(JSONObject definition:columns){
                        String configured=declaredType(definition);String format=definition.getString("format");
                        if(configured.equals("string") || ((configured.equals("date") || configured.equals("timestamp")) && format!=null && !format.isBlank()))continue;
                        String name=definition.getString("columnName");int position=-1;
                        for(int i=0;i<headers.size();i++)if(headers.get(i).equalsIgnoreCase(name)){position=i;break;}
                        String value=values.get(position);if(value.isEmpty())continue;
                        try {tech.qiantong.qdata.common.database.core.HiveValueCodec.convert(value,configured);}
                        catch(IllegalArgumentException failure){throw new IllegalArgumentException("CSV字段值不符合配置类型："+name+" -> "+configured);}
                    }
                });
            }catch(java.io.IOException failure){throw new IllegalArgumentException("CSV读取失败",failure);}
        }
        data=data.toDF(java.util.Arrays.stream(data.columns()).map(String::trim).toArray(String[]::new));
        List<Column> selected=new java.util.ArrayList<>();
        for(JSONObject definition:columns){
            String name=definition.getString("columnName");String type=declaredType(definition);
            Column raw=org.apache.spark.sql.functions.col("`"+name.replace("`","``")+"`");Column converted=raw;
            if(!type.equals("string")){
                String format=definition.getString("format");
                converted=format!=null && !format.isBlank() && type.equals("date")?org.apache.spark.sql.functions.to_date(raw,format):
                        format!=null && !format.isBlank() && type.equals("timestamp")?org.apache.spark.sql.functions.to_timestamp(raw,format):raw.cast(type);
                if(data.filter(raw.isNotNull().and(converted.isNull())).limit(1).count()>0)throw new IllegalArgumentException("CSV字段类型转换失败："+name+" -> "+type);
            }
            selected.add(converted.alias(name));
        }
        return data.select(selected.toArray(Column[]::new));
    }

    private static String declaredType(JSONObject definition) {
        String type=definition.getString("type");if(type==null || type.isBlank())type=definition.getString("columnType");
        if(type==null || type.isBlank())return "string";
        type=type.trim().toLowerCase(java.util.Locale.ROOT);
        type=switch(type){case "integer"->"int";case "long"->"bigint";case "short"->"smallint";case "byte"->"tinyint";case "datetime"->"timestamp";case "varchar","varchar2","text"->"string";default->type;};
        if(type.equals("decimal") || type.equals("numeric"))throw new IllegalArgumentException("CSV DECIMAL字段必须明确精度和小数位数："+definition.getString("columnName"));
        return type;
    }

    @Override
    public String code() {
        return TaskComponentTypeEnum.CSV_READER.getCode();
    }
}
