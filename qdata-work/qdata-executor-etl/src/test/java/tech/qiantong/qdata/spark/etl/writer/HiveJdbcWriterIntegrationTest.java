package tech.qiantong.qdata.spark.etl.writer;
import java.sql.*;import java.util.*;import org.apache.spark.sql.*;import tech.qiantong.qdata.spark.etl.writer.HiveJdbcWriter;
public class HiveJdbcWriterIntegrationTest {
 static long count(Connection c,String t)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM "+t)){r.next();return r.getLong(1);}}
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);System.out.println("PASS "+label);}
 @org.junit.jupiter.api.Test
 @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="qdata.hive.integration", matches="true")
 public void appendOverwriteAndFailurePreservation()throws Exception{
 Class.forName("org.apache.hive.jdbc.HiveDriver");String t="qdata_test.codex_hive_writer_"+System.currentTimeMillis();
 try(Connection c=DriverManager.getConnection("jdbc:hive2://bigdata-hdfs-4:10000/qdata_test","hadoop","")){
 try(Statement s=c.createStatement()){s.execute("CREATE TABLE "+t+" (id INT,note STRING,amount DECIMAL(12,2),day DATE) STORED AS PARQUET");}
 List<String> cols=List.of("id","note","amount","day");String note="comma, quote' and newline\n测试";
 var rows=List.of(RowFactory.create(1,note,new java.math.BigDecimal("12.34"),java.time.LocalDate.of(2026,9,7)),RowFactory.create(2,null,null,null),RowFactory.create(3,"tail",new java.math.BigDecimal("0.50"),null));
 check(HiveJdbcWriter.write(c,rows.iterator(),t,cols,2,2,null,null)==3 && count(c,t)==3,"append 3 rows across bounded batches");
 try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT note,amount,day FROM "+t+" WHERE id=1")){r.next();check(note.equals(r.getString(1)) && r.getBigDecimal(2).compareTo(new java.math.BigDecimal("12.34"))==0,"quoted text, decimal, date roundtrip");}
 HiveJdbcWriter.write(c,List.of(RowFactory.create(9,"replacement",null,null)).iterator(),t,cols,1,1,null,null);check(count(c,t)==1,"full overwrite preserves target");
 Iterator<Row> broken=new Iterator<>(){int i;public boolean hasNext(){if(i>0)throw new IllegalStateException("injected reader failure");return true;}public Row next(){i++;return RowFactory.create(10,"staged only",null,null);}};
 try{HiveJdbcWriter.write(c,broken,t,cols,1,1,null,null);throw new AssertionError("expected failure");}catch(IllegalStateException expected){}check(count(c,t)==1,"staging failure preserves existing rows");
 try{HiveJdbcWriter.write(c,rows.iterator(),t,cols,3,2,null,null);throw new AssertionError("expected upsert rejection");}catch(SQLException expected){}check(count(c,t)==1,"reject unsupported upsert without writes");
 HiveJdbcWriter.write(c,Collections.<Row>emptyIterator(),t,cols,1,2,null,null);check(count(c,t)==0,"empty full overwrite");
 HiveJdbcWriter.write(c,rows.iterator(),t,cols,2,2,null,null);System.out.println("TEST_TABLE "+t);
 }
 }
 @org.junit.jupiter.api.Test
 @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="qdata.hive.integration", matches="true")
 public void partitionedHiveTablePreservesDefinitionAndReplacesRows()throws Exception {
  Class.forName("org.apache.hive.jdbc.HiveDriver");
  String table="qdata_test.codex_hive_partition_"+System.currentTimeMillis();
  try(Connection c=DriverManager.getConnection("jdbc:hive2://bigdata-hdfs-4:10000/qdata_test","hadoop","")) {
   try(Statement s=c.createStatement()){s.execute("CREATE TABLE "+table+" (id INT,note STRING) PARTITIONED BY (dt STRING) STORED AS PARQUET");}
   var columns=List.of("id","note","dt");
   HiveJdbcWriter.write(c,List.of(RowFactory.create(1,"first","2026-09-07"),RowFactory.create(2,"second","2026-09-08"),RowFactory.create(4,"null partition",null),RowFactory.create(5,"escaped partition","a/b+c")).iterator(),table,columns,2,1,null,null);
   check(count(c,table)==4,"partitioned append with null and escaped values");
   HiveJdbcWriter.write(c,List.of(RowFactory.create(3,"replacement","2026-09-09")).iterator(),table,columns,1,1,null,null);
   check(count(c,table)==1,"partitioned overwrite");
   java.util.List<tech.qiantong.qdata.common.database.core.DbColumn> raw=new ArrayList<>();
   var mapper=new tech.qiantong.qdata.common.database.dialect.HiveDialect().columnMapper();
   try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("DESCRIBE "+table)){while(rs.next())raw.add(mapper.mapRow(rs,raw.size()));}
   var normalized=tech.qiantong.qdata.common.database.dialect.HiveDialect.normalizeColumns(raw);
   check(normalized.stream().map(tech.qiantong.qdata.common.database.core.DbColumn::getColName).toList().equals(columns),"live partition metadata has no duplicates");
   HiveJdbcWriter.write(c,Collections.<Row>emptyIterator(),table,columns,1,1,null,null);
   check(count(c,table)==0,"empty full overwrite clears old partitions");
   System.out.println("TEST_TABLE "+table);
  }
 }

}
