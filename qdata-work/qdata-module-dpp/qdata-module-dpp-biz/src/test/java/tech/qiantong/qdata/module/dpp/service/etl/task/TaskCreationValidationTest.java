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
    private SqlSessionTemplate session;
    private final List<tech.qiantong.qdata.quartz.domain.ScheduleCommand> registered = new CopyOnWriteArrayList<>();
    private final List<Long> triggered = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:task-validation-" + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=SYSTEM_USER;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        ds.setUser("sa"); jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ATT_PROJECT(id BIGINT PRIMARY KEY,code VARCHAR(128),scheduler_type VARCHAR(32),"
                + "valid_flag VARCHAR(1) DEFAULT '1',del_flag VARCHAR(1) DEFAULT '0')");
        jdbc.update("INSERT INTO ATT_PROJECT(id,code,scheduler_type) VALUES (5,'5005','QUARTZ'),(6,'6006','QUARTZ'),(7,'7007','QUARTZ'),(8,'8008','QUARTZ')");
        jdbc.execute("CREATE TABLE SYSTEM_USER_ROLE(user_id BIGINT,role_id BIGINT)");
        jdbc.execute("CREATE TABLE dpp_etl_task_instance(task_id BIGINT,status VARCHAR(8),del_flag CHAR(1) DEFAULT '0')");
        jdbc.execute("CREATE TABLE SYSTEM_USER(user_id BIGINT,nick_name VARCHAR(128),del_flag CHAR(1) DEFAULT '0')");
        jdbc.execute("CREATE TABLE SYSTEM_ROLE(role_id BIGINT,project_id BIGINT,role_key VARCHAR(64),status VARCHAR(1),del_flag VARCHAR(1))");
        jdbc.execute("CREATE TABLE SYSTEM_ROLE_MENU(role_id BIGINT,menu_id BIGINT)");
        jdbc.execute("CREATE TABLE SYSTEM_MENU(menu_id BIGINT,perms VARCHAR(128),status VARCHAR(1))");
        jdbc.update("INSERT INTO SYSTEM_ROLE VALUES (5,5,'writer5','0','0'),(6,6,'writer6','0','0'),(7,7,'reader7','0','0')");
        jdbc.update("INSERT INTO SYSTEM_USER_ROLE VALUES (926,5),(926,6),(926,7)");
        jdbc.update("INSERT INTO SYSTEM_ROLE_MENU VALUES (5,1),(6,1),(7,2)");
        jdbc.update("INSERT INTO SYSTEM_MENU VALUES (1,'dpp:*','0'),(2,'dpp:developTask:list','0')");
        login();
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.getTypeAliasRegistry().registerAlias(DppEtlNodeDO.class);
        var global = com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();
        global.setSqlInjector(new com.github.yulichang.injector.MPJSqlInjector());
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config, global);
        for (var mapper : List.of(AttCatMapper.class, AttTaskCatMapper.class, AttDataDevCatMapper.class,
                DppEtlTaskMapper.class, DppEtlSchedulerMapper.class, DppEtlNodeMapper.class, DppEtlNodeLogMapper.class,
                DppEtlTaskLogMapper.class, DppEtlTaskNodeRelMapper.class, DppEtlTaskNodeRelLogMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setGlobalConfig(global);
        factory.setPlugins(new com.github.yulichang.interceptor.MPJInterceptor());
        // 加载真实类目查询和二次保存替换节点使用的 XML。
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/att/cat/AttCatMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mapper/dpp/etl/DppEtlNodeMapper.xml"));
        session = new SqlSessionTemplate(factory.getObject());
        for (var entity : List.of(AttTaskCatDO.class, AttDataDevCatDO.class, DppEtlTaskDO.class, DppEtlSchedulerDO.class,
                DppEtlNodeDO.class, DppEtlNodeLogDO.class, DppEtlTaskLogDO.class, DppEtlTaskNodeRelDO.class, DppEtlTaskNodeRelLogDO.class)) {
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
                    if (m.getName().equals("createSchedule")) {
                        registered.add((tech.qiantong.qdata.quartz.domain.ScheduleCommand) a[0]); return 42L;
                    }
                    if (m.getName().equals("trigger")) {
                        triggered.add(((tech.qiantong.qdata.quartz.domain.ScheduleCommand) a[0]).getId()); return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        ReflectionTestUtils.setField(tasks,"schedulerAdapter",generator);
        var quartz = new tech.qiantong.qdata.module.dpp.service.scheduler.DppTaskQuartzService();
        ReflectionTestUtils.setField(quartz, "schedulerAdapter", generator);
        ReflectionTestUtils.setField(tasks, "dppTaskQuartzService", quartz);
        ReflectionTestUtils.setField(tasks, "quartzDefinitionVersioning", new QuartzDefinitionVersioning(jdbc));
        ReflectionTestUtils.setField(tasks, "dppEtlTaskInstanceService", org.mockito.Mockito.mock(
                tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskInstanceService.class));
        Object[][] collaborators = {
            {"iDppEtlNodeService", new DppEtlNodeServiceImpl(), "dppEtlNodeMapper", session.getMapper(DppEtlNodeMapper.class)},
            {"iDppEtlNodeLogService", new DppEtlNodeLogServiceImpl(), "dppEtlNodeLogMapper", session.getMapper(DppEtlNodeLogMapper.class)},
            {"iDppEtlTaskLogService", new DppEtlTaskLogServiceImpl(), "dppEtlTaskLogMapper", session.getMapper(DppEtlTaskLogMapper.class)},
            {"iDppEtlTaskNodeRelService", new DppEtlTaskNodeRelServiceImpl(), "dppEtlTaskNodeRelMapper", session.getMapper(DppEtlTaskNodeRelMapper.class)},
            {"iDppEtlTaskNodeRelLogService", new DppEtlTaskNodeRelLogServiceImpl(), "dppEtlTaskNodeRelLogMapper", session.getMapper(DppEtlTaskNodeRelLogMapper.class)},
        };
        for (var collaborator : collaborators) {
            ReflectionTestUtils.setField(collaborator[1], "baseMapper", collaborator[3]);
            ReflectionTestUtils.setField(collaborator[1], (String) collaborator[2], collaborator[3]);
            ReflectionTestUtils.setField(tasks, (String) collaborator[0], collaborator[1]);
        }
        controller = new DppEtlTaskController();
        ReflectionTestUtils.setField(controller,"resourceAccess",access);
        ReflectionTestUtils.setField(controller,"dppEtlTaskService",tasks);
        var transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        var proxy = new org.springframework.aop.framework.ProxyFactory(tasks);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(controller,"dppEtlTaskService",proxy.getProxy());
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

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
        "personCharge,责任人不能为空", "crontab,调度表达式不能为空", "projectId,项目ID不能为空"})
    void developmentSaveReportsTheThreeOriginalMissingFields(String field, String message) throws Exception {
        var body = draft(); body.put("id", "1"); body.remove(field);
        post("updateProcessDefinition", body)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg").value(message));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task", Integer.class));
    }

    @Test void duplicateNameIsRejectedBeforeAllocatingAnotherCodeOrSchedule() throws Exception {
        post("createEtlTaskFront", draft());
        long allocated = sequence.get();
        var duplicate = draft(); duplicate.put("name", "  offline-http-task  ");
        post("createEtlTaskFront", duplicate)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg")
                        .value("当前项目已存在同名任务，请使用其他名称"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_scheduler", Integer.class));
        assertEquals(allocated, sequence.get());
    }

    @Test void otherProjectsAndSoftDeletedNamesDoNotConflict() throws Exception {
        post("createEtlTaskFront", draft());
        var other = draft(); other.put("projectId", 6); other.put("projectCode", 6006);
        post("createEtlTaskFront", other)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        jdbc.update("UPDATE dpp_etl_task SET del_flag=1 WHERE project_id=5");
        post("createEtlTaskFront", draft())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task", Integer.class));
    }

    @Test void renameExcludesItselfButCannotTakeAnotherActiveTasksName() throws Exception {
        byte[] created = post("createEtlTaskFront", draft()).andReturn().getResponse().getContentAsByteArray();
        long id = json.readTree(created).path("data").path("id").asLong();
        var tasks = (tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskService)
                ReflectionTestUtils.getField(controller, "dppEtlTaskService");
        var rename = new tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskSaveReqVO();
        rename.setId(id); rename.setName("offline-http-task");
        assertEquals(1, tasks.updateDppEtlTask(rename));
        var other = draft(); other.put("name", "fix_second_task"); post("createEtlTaskFront", other);
        rename.setName("fix_second_task");
        assertEquals("当前项目已存在同名任务，请使用其他名称", assertThrows(ServiceException.class,
                () -> tasks.updateDppEtlTask(rename)).getMessage());
        assertEquals("offline-http-task", jdbc.queryForObject("SELECT name FROM dpp_etl_task WHERE id=?", String.class, id));
    }

    @Test void concurrentRequestsCreateOneTaskAndOneSchedule() throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> create = () -> {
                login(); ready.countDown();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    byte[] body = post("createEtlTaskFront", draft()).andReturn().getResponse().getContentAsByteArray();
                    return json.readTree(body).path("code").asInt();
                } finally { SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(create); var second = executor.submit(create);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertEquals(Set.of(200, 500), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_scheduler", Integer.class));
    }

    @Test void firstDevelopmentSaveRegistersPausedQuartzAndPersistsVersionedGraphBeforeTrigger() throws Exception {
        var body = draft(); body.put("type", "3");
        byte[] created = post("createEtlTaskFront", body).andReturn().getResponse().getContentAsByteArray();
        String id = json.readTree(created).path("data").path("id").asText();
        String code = json.readTree(created).path("data").path("code").asText();
        body.put("id", id); body.put("code", code);
        body.put("taskDefinitionList", "[{\"code\":\"" + code + "\",\"name\":\"fix_sql\",\"taskType\":\"SQL\",\"componentType\":\"51\",\"taskParams\":{\"sql\":\"select 1\"}}]");
        body.put("taskRelationJson", "[{\"preTaskCode\":0,\"preTaskVersion\":0,\"postTaskCode\":\"" + code + "\"}]");
        // 兼容旧编辑器错误地把草稿送到update接口，服务端仍执行首次完整保存。
        post("updateProcessDefinition", body)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        assertEquals(1, registered.size());
        assertEquals("1", registered.getFirst().getStatus(), "注册后暂停，不能自动启动周期调度");
        assertEquals("dppQuartzJob.dataDevelopment(" + id + "L)", registered.getFirst().getInvokeTarget());
        assertEquals(42L, jdbc.queryForObject("SELECT quartz_id FROM dpp_etl_task WHERE id=?", Long.class, id));
        assertEquals(42L, jdbc.queryForObject("SELECT quartz_id FROM dpp_etl_scheduler WHERE task_id=?", Long.class, id));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM dpp_etl_task WHERE id=?", Long.class, id));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM dpp_etl_node WHERE code=?", Long.class, code));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task_log WHERE version=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_node_log WHERE version=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task_node_rel WHERE post_node_id>0 AND post_node_version=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task_node_rel_log WHERE post_node_id>0 AND post_node_version=1", Integer.class));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dpp/etlTask/updateQuery/" + id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.taskConfig.projectId").value(5))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.taskConfig.personCharge").value("926"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.taskConfig.crontab").value("0 0 0 1 1 ? 2099"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.taskDefinitionList[0].taskParams.sql").value("select 1"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/dpp/etlTask/startDppEtlTask/" + id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        assertEquals(List.of(42L), triggered);
        assertEquals("0", jdbc.queryForObject("SELECT status FROM dpp_etl_scheduler WHERE task_id=?", String.class, id));
    }
    private Map<String,Object> developmentDefinition(String type) throws Exception {
        var body = draft(); body.put("type", type);
        byte[] created = post("createEtlTaskFront", body).andReturn().getResponse().getContentAsByteArray();
        String id = json.readTree(created).path("data").path("id").asText();
        String code = json.readTree(created).path("data").path("code").asText();
        body.put("id", id); body.put("code", code);
        body.put("taskDefinitionList", "[{\"code\":\"" + code + "\",\"name\":\"fix_sql\",\"taskType\":\"SQL\",\"componentType\":\"51\",\"taskParams\":{\"sql\":\"select 1\"}}]");
        body.put("taskRelationJson", "[{\"preTaskCode\":0,\"preTaskVersion\":0,\"postTaskCode\":\"" + code + "\"}]");
        return body;
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"3,true", "3,false", "4,true", "4,false"})
    void updateEtlTaskKeepsPersistedDevelopmentTypeAndUsesDefinitionSave(String type, boolean sendType) throws Exception {
        var body = developmentDefinition(type);
        if (!sendType) body.remove("type");
        for (int version = 1; version <= 2; version++) {
            if (version > 1) body.put("version", version - 1);
            post("updateEtlTask", body)
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
            assertEquals(type, jdbc.queryForObject("SELECT type FROM dpp_etl_task WHERE id=?", String.class, body.get("id")));
            assertEquals((long)version, jdbc.queryForObject("SELECT version FROM dpp_etl_task WHERE id=?", Long.class, body.get("id")));
            assertEquals(type, jdbc.queryForObject("SELECT task_type FROM dpp_etl_node WHERE code=?", String.class, body.get("code")));
        }
        assertEquals(1, registered.size());
    }

    @Test void repeatedInitializationIsRejectedWithoutCreatingNewVersionsAndUpdateStillWorks() throws Exception {
        var body = developmentDefinition("3");
        post("createProcessDefinitionEx", body)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        body.put("version", 1);
        post("createProcessDefinitionEx", body)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(500))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg").value("任务已完成首次保存，请刷新后重试"));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM dpp_etl_task WHERE id=?", Long.class, body.get("id")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task_log", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_node_log", Integer.class));
        assertEquals(1, registered.size());
        post("updateProcessDefinition", body)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
        assertEquals(2L, jdbc.queryForObject("SELECT version FROM dpp_etl_task WHERE id=?", Long.class, body.get("id")));
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
