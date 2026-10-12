package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DppPublishedExecutionPlanTest {
    DppEtlNodeRespVO node(String code,long version,String sql) {
        var node=new DppEtlNodeRespVO();node.setCode(code);node.setVersion(version);node.setName(code);node.setParameters(sql);return node;
    }
    DppPublishedExecutionPlan.Key key(String code){return new DppPublishedExecutionPlan.Key(code,1);}
    DppPublishedExecutionPlan.Edge edge(String from,String to){return new DppPublishedExecutionPlan.Edge(from==null?null:key(from),key(to));}
    @Test void diamondExecutesSharedNodeExactlyOnceInDependencyOrder() {
        var a=node("A",1,"a");var b=node("B",1,"b");var c=node("C",1,"c");var d=node("D",1,"d");
        var result=DppPublishedExecutionPlan.order(List.of(d,c,a,b,d),List.of(edge("A","B"),edge("A","C"),edge("B","D"),edge("C","D"),edge(null,"A")));
        assertEquals(List.of("A","B","C","D"),result.stream().map(DppEtlNodeRespVO::getCode).toList());
    }
    @Test void duplicatedEdgesDoNotRunTheTargetTwice() {
        var result=DppPublishedExecutionPlan.order(List.of(node("A",1,"a"),node("B",1,"b")),List.of(edge("A","B"),edge("A","B")));
        assertEquals(2,result.size());
    }
    @Test void cycleStopsBeforeAnExecutionPlanCanBeReturned() {
        assertThrows(IllegalArgumentException.class,()->DppPublishedExecutionPlan.order(List.of(node("A",1,"a"),node("B",1,"b")),List.of(edge("A","B"),edge("B","A"))));
    }
    @Test void wrongVersionCannotUseTheCurrentNodeAsAFallback() {
        assertThrows(IllegalArgumentException.class,()->DppPublishedExecutionPlan.order(List.of(node("A",2,"new"),node("B",1,"b")),List.of(edge("A","B"))));
    }
    @Test void ambiguousHistoricalVersionIsRejected() {
        assertThrows(IllegalArgumentException.class,()->DppPublishedExecutionPlan.order(List.of(node("A",1,"old"),node("A",1,"changed")),List.of(edge(null,"A"))));
    }
    @Test void missingParentStopsBeforeAnyNodeRuns() {
        assertThrows(IllegalArgumentException.class,()->DppPublishedExecutionPlan.order(List.of(node("B",1,"b")),List.of(edge("A","B"))));
    }
    org.springframework.jdbc.core.JdbcTemplate database() {
        String url=System.getenv("QDATA_NAMESPACE_URL"),password=System.getenv("QDATA_NAMESPACE_PASSWORD");
        assertNotNull(url);assertTrue(url.matches("jdbc:mysql://[^/]+/qdata_namespace_acceptance_[a-z0-9_]+[?].*"));
        var admin=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"root",password));
        String database="qdata_published_graph_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"`");
        int slash=url.indexOf('/',"jdbc:mysql://".length()),question=url.indexOf('?',slash);
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(url.substring(0,slash+1)+database+url.substring(question),"root",password);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);
        System.out.println("Retained MySQL published-graph fixture: "+database);
        jdbc.execute("CREATE TABLE dpp_etl_node_log(id BIGINT PRIMARY KEY,code VARCHAR(255),version BIGINT,project_code VARCHAR(255),name VARCHAR(255),parameters VARCHAR(255),component_type VARCHAR(255),del_flag VARCHAR(1))");
        jdbc.execute("CREATE TABLE dpp_etl_task_node_rel_log(id BIGINT PRIMARY KEY,task_id BIGINT,task_code VARCHAR(255),project_code VARCHAR(255),task_version BIGINT,pre_node_code VARCHAR(255),pre_node_version BIGINT,post_node_code VARCHAR(255),post_node_version BIGINT,post_node_id BIGINT,del_flag VARCHAR(1))");
        jdbc.update("INSERT INTO dpp_etl_node_log VALUES (10,'A',1,'project','source','old SQL','SQL','0'),(11,'A',2,'project','source','new SQL','SQL','0'),(12,'B',1,'project','target','target SQL','SQL','0')");
        jdbc.update("INSERT INTO dpp_etl_task_node_rel_log VALUES (1,501,'task','project',1,'0',0,'A',1,999,'0'),(2,501,'task','project',1,'A',1,'B',1,12,'0'),(3,502,'task','project',2,'0',0,'A',2,11,'0')");
        return jdbc;
    }
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
    @Test void nodeCodeProjectAndVersionResolveHistoricalPointerFormats() {
        var service=new DppPublishedExecutionPlan(database());
        var first=service.load("task","project",1L);var second=service.load("task","project",2L);
        assertEquals(List.of("A","B"),first.stream().map(DppEtlNodeRespVO::getCode).toList());
        assertEquals("old SQL",first.get(0).getParameters());assertEquals("new SQL",second.get(0).getParameters());
    }
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION",matches="true")
    @Test void wrongProjectAndMissingVersionCannotFallBackToCurrentConfiguration() {
        var service=new DppPublishedExecutionPlan(database());
        assertThrows(IllegalArgumentException.class,()->service.load("task","other-project",1L));
        assertThrows(IllegalArgumentException.class,()->service.load("task","project",3L));
    }
}
