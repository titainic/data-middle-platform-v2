package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.module.att.dal.dataobject.cat.*;
import tech.qiantong.qdata.module.att.dal.mapper.cat.*;
import tech.qiantong.qdata.module.att.service.cat.impl.AttCatServiceImpl;
import tech.qiantong.qdata.module.att.service.cat.impl.AttTaskCatServiceImpl;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatSaveReqVO;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.da.api.service.DataResourceAccess;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.DppEtlTaskController;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNewNodeSaveReqVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.*;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.*;
import tech.qiantong.qdata.module.dpp.service.etl.DevelopmentResourceAccess;
import tech.qiantong.qdata.module.dpp.service.etl.impl.*;
import tech.qiantong.qdata.quartz.scheduler.ISchedulerAdapter;
import tech.qiantong.qdata.security.web.service.ProjectPermissionService;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Real controller -> access/permission services -> task/category/scheduler MyBatis SQL, H2 MySQL mode.
 * Spring MVC and Bean Validation run in-process. Membership and Redis code generation are fixtures; no login filters,
 * external scheduler, business database, Hive/HDFS connection or cleanup is used.
 */
class TaskCreationValidationTest {
    private org.springframework.test.web.servlet.MockMvc mvc;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private AtomicLong sequence;
    private JdbcTemplate jdbc;
    private DevelopmentResourceAccess access;
    private DppEtlTaskController controller;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:task-validation-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        ds.setUser("sa"); jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ATT_PROJECT(id BIGINT PRIMARY KEY,code VARCHAR(128),scheduler_type VARCHAR(32),"
                + "valid_flag VARCHAR(1) DEFAULT '1',del_flag VARCHAR(1) DEFAULT '0')");
        jdbc.update("INSERT INTO ATT_PROJECT(id,code,scheduler_type) VALUES (5,'5005','QUARTZ'),(6,'6006','QUARTZ'),(7,'7007','QUARTZ'),(8,'8008','QUARTZ')");
        jdbc.execute("CREATE TABLE SYSTEM_USER_ROLE(user_id BIGINT,role_id BIGINT)");
        jdbc.execute("CREATE TABLE SYSTEM_ROLE(role_id BIGINT,project_id BIGINT,role_key VARCHAR(64),status VARCHAR(1),del_flag VARCHAR(1))");
        jdbc.execute("CREATE TABLE SYSTEM_ROLE_MENU(role_id BIGINT,menu_id BIGINT)");
        jdbc.execute("CREATE TABLE SYSTEM_MENU(menu_id BIGINT,perms VARCHAR(128),status VARCHAR(1))");
        jdbc.update("INSERT INTO SYSTEM_ROLE VALUES (5,5,'writer5','0','0'),(6,6,'writer6','0','0'),(7,7,'reader7','0','0')");
        jdbc.update("INSERT INTO SYSTEM_USER_ROLE VALUES (926,5),(926,6),(926,7)");
        jdbc.update("INSERT INTO SYSTEM_ROLE_MENU VALUES (5,1),(6,1),(7,2)");
        jdbc.update("INSERT INTO SYSTEM_MENU VALUES (1,'dpp:*','0'),(2,'dpp:developTask:list','0')");
        login();
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(AttCatMapper.class, AttTaskCatMapper.class, AttDataDevCatMapper.class,
                DppEtlTaskMapper.class, DppEtlSchedulerMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        // Match the existing offline category fixture: execute the real category code-to-ID XML.
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/att/cat/AttCatMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        for (var entity : List.of(AttTaskCatDO.class, AttDataDevCatDO.class, DppEtlTaskDO.class, DppEtlSchedulerDO.class)) {
            var info = TableInfoHelper.getTableInfo(entity);
            var columns = new ArrayList<String>(); columns.add("id BIGINT AUTO_INCREMENT PRIMARY KEY");
            for (var field : info.getFieldList()) {
                Class<?> type = field.getPropertyType();
                String sqlType = type == Boolean.class ? "TINYINT DEFAULT 0"
                        : Number.class.isAssignableFrom(type) ? "BIGINT"
                        : java.util.Date.class.isAssignableFrom(type) ? "TIMESTAMP" : "VARCHAR(2048)";
                columns.add(field.getColumn() + " " + sqlType);
            }
            jdbc.execute("CREATE TABLE " + info.getTableName() + "(" + String.join(",", columns) + ")");
        }
        for (String table : List.of("dpp_etl_node", "dpp_etl_node_log"))
            jdbc.execute("CREATE TABLE " + table + "(code VARCHAR(128),project_id BIGINT,del_flag CHAR(1) DEFAULT '0')");
        DataResourceAccess membership = (DataResourceAccess) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataResourceAccess.class}, (p,m,a) -> switch (m.getName()) {
                    case "visibleProjectIds" -> Set.of(5L,6L,7L);
                    case "isAdministrator" -> false;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        access = new DevelopmentResourceAccess(jdbc,membership,new ProjectPermissionService(jdbc));
        var tasks = new DppEtlTaskServiceImpl(); var taskMapper = session.getMapper(DppEtlTaskMapper.class);
        ReflectionTestUtils.setField(tasks,"baseMapper",taskMapper);
        ReflectionTestUtils.setField(tasks,"dppEtlTaskMapper",taskMapper);
        var categories = new AttCatServiceImpl();
        ReflectionTestUtils.setField(categories,"attCatMapper",session.getMapper(AttCatMapper.class));
        ReflectionTestUtils.setField(tasks,"attCatService",categories);
        var schedules = new DppEtlSchedulerServiceImpl();
        ReflectionTestUtils.setField(schedules,"dppEtlSchedulerMapper",session.getMapper(DppEtlSchedulerMapper.class));
        ReflectionTestUtils.setField(tasks,"iDppEtlSchedulerService",schedules);
        sequence = new AtomicLong(9000000000000000L);
        ISchedulerAdapter generator = (ISchedulerAdapter) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ISchedulerAdapter.class}, (p,m,a) -> {
                    if (m.getName().equals("generateTaskCode")) return sequence.incrementAndGet();
                    throw new UnsupportedOperationException(m.getName());
                });
        ReflectionTestUtils.setField(tasks,"schedulerAdapter",generator);
        controller = new DppEtlTaskController();
        ReflectionTestUtils.setField(controller,"resourceAccess",access);
        ReflectionTestUtils.setField(controller,"dppEtlTaskService",tasks);
        // Match the mandatory draft columns instead of allowing the permissive category-test schema.
        for (String column : List.of("name","type","code","project_id","project_code","person_charge","execution_type"))
            jdbc.execute("ALTER TABLE DPP_ETL_TASK ALTER COLUMN " + column + " SET NOT NULL");
        for (String column : List.of("task_id","task_code","start_time","end_time","cron_expression","ds_id"))
            jdbc.execute("ALTER TABLE DPP_ETL_SCHEDULER ALTER COLUMN " + column + " SET NOT NULL");
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new tech.qiantong.qdata.security.web.exception.GlobalExceptionHandler()).build();
    }
    private void login() {
        var user = new SysUser(); user.setUserId(926L); user.setUserName("offline-fixture"); user.setRoles(List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(926L,null,user,Set.of()),null,List.of()));
    }
    @AfterEach void finish() { SecurityContextHolder.clearContext(); }

    private Map<String,Object> draft() {
        var body = new LinkedHashMap<String,Object>();
        body.put("projectId",5); body.put("projectCode",5005); body.put("personCharge","926");
        body.put("name","offline-http-task"); body.put("executionType","PARALLEL");
        body.put("scheduler","QUARTZ"); body.put("crontab","0 0 0 1 1 ? 2099");
        return body;
    }
    private org.springframework.test.web.servlet.ResultActions post(String route,Map<String,Object> body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/dpp/etlTask/"+route)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }
    private void rejected(Map<String,Object> body,String message) throws Exception {
        var result=post("createEtlTaskFront",body)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg").value(message))
                .andReturn();
        safe(result.getResponse().getContentAsString());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_scheduler",Integer.class));
        assertEquals(9000000000000000L,sequence.get(),"validation must precede code generation");
    }
    private void safe(String body) {
        for (String detail:List.of("SQLException","Mapper","INSERT INTO","java.sql","person_charge","Caused by:"))
            assertFalse(body.contains(detail),body);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
        "personCharge,责任人不能为空", "name,任务名称不能为空", "projectId,项目ID不能为空",
        "projectCode,项目编码不能为空", "executionType,执行策略不能为空", "crontab,调度表达式不能为空"})
    void omittedRequiredFieldFailsBeforeAnyWrite(String field,String message) throws Exception {
        var body=draft(); body.remove(field); rejected(body,message);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
        "personCharge,责任人不能为空", "name,任务名称不能为空", "executionType,执行策略不能为空", "crontab,调度表达式不能为空"})
    void blankRequiredTextFailsBeforeAnyWrite(String field,String message) throws Exception {
        for (String blank:List.of("","  ","\t\n")) {
            var body=draft(); body.put(field,blank); rejected(body,message);
        }
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
        "personCharge,32,责任人长度不能超过32个字符", "name,128,任务名称长度不能超过128个字符",
        "executionType,32,执行策略长度不能超过32个字符", "catCode,32,类目编码长度不能超过32个字符",
        "contactNumber,32,联系电话长度不能超过32个字符", "type,10,任务类型长度不能超过10个字符",
        "scheduler,32,调度器长度不能超过32个字符", "actuator,32,执行器长度不能超过32个字符", "crontab,255,调度表达式长度不能超过255个字符"})
    void schemaLengthLimitsFailBeforePersistence(String field,int max,String message) throws Exception {
        var body=draft(); body.put(field,"x".repeat(max+1)); rejected(body,message);
    }
    @ParameterizedTest @ValueSource(strings={"createEtlTaskFront","createEtlTask","createProcessDefinitionEx",
            "createEtlTaskFrontPostposition","updateEtlTask","updateProcessDefinition"})
    void allFullTaskWriteEntrypointsValidatePersonCharge(String route) throws Exception {
        var body=draft(); body.remove("personCharge");
        post(route,body).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg").value("责任人不能为空"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task",Integer.class));
        assertEquals(9000000000000000L,sequence.get());
    }
    @ParameterizedTest @ValueSource(strings={"1","3","default"})
    void completeDraftUsesRealPersistenceAndCreatesExactlyOneTaskAndSchedule(String type) throws Exception {
        var body=draft();
        if(!type.equals("default")) {
            body.put("type",type); body.put("catCode","A01");
            String table=type.equals("3")?"att_data_dev_cat":"att_task_cat";
            jdbc.update("INSERT INTO " + table + "(project_id,project_code,code,parent_id,name,valid_flag,del_flag) VALUES (5,'5005','A01',0,'fixture',1,'0')");
        }
        var response=post("createEtlTaskFront",body)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("-1"))
                .andReturn().getResponse().getContentAsByteArray();
        String id=json.readTree(response).path("data").path("id").asText();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_scheduler",Integer.class));
        var row=jdbc.queryForMap("SELECT project_id,person_charge,execution_type,type FROM dpp_etl_task WHERE id=?",id);
        assertEquals(5L,((Number)row.get("project_id")).longValue());
        assertEquals("926",row.get("person_charge")); assertEquals("PARALLEL",row.get("execution_type"));
        assertEquals(type.equals("default")?"1":type,row.get("type"));
        if(!type.equals("default")) assertEquals(1L,jdbc.queryForObject("SELECT cat_id FROM dpp_etl_task WHERE id=?",Long.class,id));
    }
    @Test void defaultValidationGroupStillAllowsLightweightCopyAndReleasePayloads() {
        var request=new DppEtlNewNodeSaveReqVO(); request.setProjectCode(5005L); request.setId("123");
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            assertFalse(factory.getValidator().validate(request,DppEtlNewNodeSaveReqVO.TaskSave.class).isEmpty());
        }
    }
    @ParameterizedTest @ValueSource(strings={"copyCreateEtl","updateReleaseTask","updateReleaseJobTask","updateReleaseSchedule","releaseTaskCrontab"})
    void lightweightEndpointsDoNotRequireFullTaskFields(String route) throws Exception {
        byte[] response=post("createEtlTaskFront",draft()).andReturn().getResponse().getContentAsByteArray();
        String id=json.readTree(response).path("data").path("id").asText();
        var lightweight=(tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService)Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService.class},
                (p,m,a)->m.getName().equals("copyCreateEtl") ? new tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskSaveReqVO() : Map.of());
        ReflectionTestUtils.setField(controller,"dppEtlTaskService",lightweight);
        post(route,Map.of("projectId",5,"projectCode",5005,"id",id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task",Integer.class));
    }
    @Test void malformedJsonTypeHasSafeReadableErrorAndNoWrites() throws Exception {
        var body=draft(); body.put("projectId","java.sql.SQLException INSERT INTO hidden");
        var response=post("createEtlTaskFront",body)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg").value("请求体格式不正确，请检查字段类型"))
                .andReturn().getResponse().getContentAsString();
        safe(response); assertEquals(9000000000000000L,sequence.get());
    }
    @ParameterizedTest @ValueSource(strings={"database","business","runtime"})
    void unexpectedServiceFailureReturnsSafeBody(String kind) throws Exception {
        var raw="java.sql.SQLException: private detail; tech.qiantong.HiddenMapper.insert-Inline; INSERT INTO hidden VALUES (1)";
        var failing=(tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService)Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService.class},
                (p,m,a)-> { throw switch(kind) {
                    case "database" -> new org.springframework.dao.DataIntegrityViolationException(raw,new java.sql.SQLException(raw));
                    case "business" -> new ServiceException(raw);
                    default -> new RuntimeException(raw);
                }; });
        ReflectionTestUtils.setField(controller,"dppEtlTaskService",failing);
        var response=post("createEtlTaskFront",draft())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500))
                .andReturn().getResponse().getContentAsString();
        safe(response); assertFalse(response.contains("private detail"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task",Integer.class));
    }
}
