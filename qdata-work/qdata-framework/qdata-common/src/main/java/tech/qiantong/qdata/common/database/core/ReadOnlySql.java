package tech.qiantong.qdata.common.database.core;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import java.util.*;

/** Parse the actual query structure before a data-service request can open a connection. */
public final class ReadOnlySql {
    private ReadOnlySql() { }
    public static Set<String> tables(String sql,DbType dialect){
        if(sql==null || sql.isBlank())throw new IllegalArgumentException("查询SQL不能为空");
        java.util.List<SQLStatement> statements;
        try{statements=SQLUtils.parseStatements(sql,dialect);}catch(RuntimeException invalid){throw new IllegalArgumentException("查询SQL语法错误或不受支持");}
        if(statements.size()!=1 || !(statements.get(0) instanceof SQLSelectStatement))throw new IllegalArgumentException("仅允许单条只读SELECT/WITH查询");
        statements.get(0).accept(new SQLASTVisitorAdapter(){
            @Override public void preVisit(SQLObject object){
                if(object instanceof SQLStatement && !(object instanceof SQLSelectStatement))throw new IllegalArgumentException("查询不能包含数据修改语句");
                if(object instanceof SQLSelectQueryBlock block && (block.getInto()!=null || block.isForUpdate()))throw new IllegalArgumentException("查询不能写入目标或申请更新锁");
                if(object instanceof SQLMethodInvokeExpr method){
                    String name=method.getMethodName().replace("`","").replace("\"","").replace("[","").replace("]","").toLowerCase(Locale.ROOT);name=name.substring(name.lastIndexOf('.')+1);
                    if(Set.of("transform","reflect","reflect2","java_method","load_file","sleep","pg_sleep","benchmark","sys_eval","sys_exec","pg_read_file","pg_read_binary_file","lo_import","lo_export","dblink","dblink_exec","openrowset","opendatasource").contains(name))throw new IllegalArgumentException("查询不允许外部执行、文件访问或等待函数");
                }
            }
        });
        var visitor=SQLUtils.createSchemaStatVisitor(dialect);statements.get(0).accept(visitor);Set<String> result=new LinkedHashSet<>();visitor.getTables().keySet().forEach(table->result.add(table.getName()));return result;
    }
}
