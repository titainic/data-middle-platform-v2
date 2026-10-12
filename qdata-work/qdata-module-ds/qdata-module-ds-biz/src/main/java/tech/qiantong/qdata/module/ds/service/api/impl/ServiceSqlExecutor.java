package tech.qiantong.qdata.module.ds.service.api.impl;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.database.*;
import tech.qiantong.qdata.common.database.constants.*;
import tech.qiantong.qdata.common.database.core.PageResult;
import tech.qiantong.qdata.common.database.query.MyRowMapper;
import tech.qiantong.qdata.common.exception.DependencyUnavailableException;
import tech.qiantong.qdata.common.httpClient.DependencyGuard;
import tech.qiantong.qdata.module.dp.api.warehouse.ServingRequestException;

/** Service-only deadlines and bounded work; data-development jobs do not use this executor. */
@Service
@Slf4j
public class ServiceSqlExecutor {
    private final DataSourceFactory factory;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final DependencyGuard dependencies;
    private final ThreadPoolExecutor workers, aborters;
    private final long timeoutMillis;
    private final int querySeconds, maxRows;
    @Autowired public ServiceSqlExecutor(DataSourceFactory factory,com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this(factory,Duration.ofSeconds(20),15,10000,new DependencyGuard(),mapper);
    }
    ServiceSqlExecutor(DataSourceFactory factory,Duration timeout,int querySeconds,int maxRows,DependencyGuard dependencies) {
        this(factory,timeout,querySeconds,maxRows,dependencies,new com.fasterxml.jackson.databind.ObjectMapper());
    }
    private ServiceSqlExecutor(DataSourceFactory factory,Duration timeout,int querySeconds,int maxRows,DependencyGuard dependencies,com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.mapper=mapper;
        this.factory=factory;this.timeoutMillis=timeout.toMillis();this.querySeconds=querySeconds;this.maxRows=maxRows;this.dependencies=dependencies;
        if(timeoutMillis<1 || querySeconds<1 || maxRows<1)throw new IllegalArgumentException("Invalid SQL service limits");
        workers=pool(32,"qdata-service-sql");aborters=pool(4,"qdata-service-sql-abort");
    }
    private static ThreadPoolExecutor pool(int limit,String name) {
        var counter=new AtomicInteger();return new ThreadPoolExecutor(0,limit,60,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{var t=new Thread(task,name+"-"+counter.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public Object query(long sourceId,DbQueryProperty property,String sql,Map<String,Object> parameters,String resultType,int pageNum,int pageSize) {
        if(!Set.of("1","2","3").contains(resultType))throw new ServingRequestException(400,"返回结果类型无效");
        if(pageNum<1 || pageNum>100000 || pageSize<1 || pageSize>200)throw new ServingRequestException(400,"分页参数无效，pageSize需为1至200");
        var dialect=DialectFactory.getDialect(DbType.getDbType(property.getDbType()));
        if(dialect==null)throw new ServingRequestException(400,"此连接暂不支持SQL服务查询");
        var cancelled=new AtomicBoolean();var connection=new AtomicReference<Connection>();
        Future<Object> future;
        try {
            future=workers.submit(()-> {
                try(var permit=dependencies.acquire(sourceId)) {
                    DbQuery db=null;
                    try {
                        db=factory.createDbQuery(boundedProperty(property));
                        try(Connection c=db.getConnection()) {
                            connection.set(c);
                            if(cancelled.get())throw new DependencyUnavailableException("服务查询已超时，请稍后重试");
                            try {c.setNetworkTimeout(Runnable::run,querySeconds*1000);}catch(SQLFeatureNotSupportedException | AbstractMethodError unsupported) { /* Caller deadline and finite slots still apply. */ }
                            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(c,true));jdbc.setQueryTimeout(querySeconds);jdbc.setMaxRows(maxRows+1);jdbc.setFetchSize(Math.min(200,maxRows+1));
                            var named=new NamedParameterJdbcTemplate(jdbc);
                            int offset=Math.multiplyExact(pageNum-1,pageSize);
                            Object result;var budget=new RowBudget();
                            if("3".equals(resultType)) {
                                Long total=named.queryForObject(dialect.count(sql),parameters,Long.class);
                                if(total==null || total>Integer.MAX_VALUE)throw new ServingRequestException(400,"结果数量超出分页范围，请缩小查询条件");
                                var rows=named.query(dialect.buildPaginationSql(sql,offset,pageSize),parameters,budget.wrap(mapper(dialect,sql,offset)));
                                result=new PageResult<Map<String,Object>>(total.intValue(),rows).setPageNum(pageNum).setPageSize(pageSize);
                            } else if("2".equals(resultType)) {
                                var rows=named.query(sql,parameters,budget.wrap(new MyRowMapper()));
                                if(rows.size()>maxRows)throw new ServingRequestException(400,"结果超过"+maxRows+"行，请改用分页查询");
                                result=rows;
                            } else {
                                jdbc.setMaxRows(1);result=named.query(sql,parameters,rs->rs.next()?budget.wrap(new MyRowMapper()).mapRow(rs,1):null);
                            }
                            if(cancelled.get())throw new DependencyUnavailableException("服务查询已超时，请稍后重试");
                            permit.succeeded();return result;
                        }
                    } catch(ServingRequestException invalid) {permit.succeeded();throw invalid;}
                    catch(Exception failure) {
                        if(invalidSql(failure)){permit.succeeded();throw new ServingRequestException(400,"SQL执行失败，请检查已保存的查询和参数");}
                        permit.failed();log.warn("SQL service dependency failed datasourceId={} type={}",sourceId,failure.getClass().getSimpleName());
                        throw new DependencyUnavailableException("服务数据库连接失败或查询超时，请稍后重试");
                    } finally {connection.set(null);if(db!=null)db.close();}
                }
            });
        } catch(RejectedExecutionException busy) {throw new DependencyUnavailableException("服务查询繁忙，请稍后重试");}
        try {return future.get(timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(TimeoutException expired) {
            cancel(future,cancelled,connection);throw new DependencyUnavailableException("服务查询超过总时间限制，请稍后重试");
        } catch(InterruptedException interrupted) {
            cancel(future,cancelled,connection);Thread.currentThread().interrupt();throw new DependencyUnavailableException("服务查询已中断，请稍后重试");
        } catch(ExecutionException failed) {
            if(failed.getCause() instanceof RuntimeException runtime)throw runtime;
            throw new DependencyUnavailableException("服务查询暂时不可用，请稍后重试");
        }
    }
    private final class RowBudget {
        private long written;
        private final java.io.OutputStream output=new java.io.OutputStream() {
            @Override public void write(int value)throws java.io.IOException { reserve(1); }
            @Override public void write(byte[] bytes,int offset,int length)throws java.io.IOException { reserve(length); }
            private void reserve(int bytes)throws java.io.IOException {
                written+=bytes;if(written>16L*1024*1024)throw new java.io.IOException("SQL_RESULT_LIMIT");
            }
        };
        org.springframework.jdbc.core.RowMapper<Map<String,Object>> wrap(MyRowMapper delegate) {
            return (result,index)-> {
                var row=delegate.mapRow(result,index);
                try {mapper.writeValue(output,row);}
                catch(java.io.IOException failure) {
                    if(written>16L*1024*1024)throw new ServingRequestException(400,"返回数据超过16MiB，请缩小查询字段或条件");
                    throw new ServingRequestException(400,"返回字段无法序列化，请检查服务字段类型");
                }
                return row;
            };
        }
    }
    private DbQueryProperty boundedProperty(DbQueryProperty original) {
        var bounded=new DbQueryProperty(original.getDbType(),original.getHost(),original.getUsername(),original.getPassword(),original.getPort(),original.getDbName(),original.getSid()) {
            @Override public String trainToJdbcUrl() {
                String url=original.trainToJdbcUrl();
                if(url.startsWith("jdbc:mysql:"))return url+(url.contains("?")?"&":"?")+"connectTimeout=5000&socketTimeout="+(querySeconds*1000)+"&autoReconnect=false";
                return url;
            }
        };
        bounded.setConfig(original.getConfig());bounded.setDatasourceConfig(original.getDatasourceConfig());return bounded;
    }
    private static MyRowMapper mapper(DbDialect dialect,String sql,long offset) {
        if(dialect instanceof tech.qiantong.qdata.common.database.dialect.HiveDialect hive && hive.wrapsForPagination(sql,offset))return new MyRowMapper() {
            @Override protected String getColumnKey(String label){String prefix="qdata_page.";return label.regionMatches(true,0,prefix,0,prefix.length())?label.substring(prefix.length()):label;}
        };
        return new MyRowMapper();
    }
    private static boolean invalidSql(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql && sql.getSQLState()!=null && (sql.getSQLState().startsWith("42") || sql.getSQLState().startsWith("22")))return true;
        return false;
    }
    private void cancel(Future<?> future,AtomicBoolean cancelled,AtomicReference<Connection> connection) {
        cancelled.set(true);future.cancel(true);var active=connection.get();if(active==null)return;
        try {aborters.execute(()->{try{active.abort(Runnable::run);}catch(Exception unsupported){try{active.close();}catch(Exception ignored){}}});}catch(RejectedExecutionException busy){/* Occupied workers retain their permits until the driver exits. */}
    }
    @PreDestroy public void close(){workers.shutdownNow();aborters.shutdownNow();}
}
