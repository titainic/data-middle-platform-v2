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
 * Membership and the Redis code generator are explicit local fixtures; no HTTP/security filters,
 * external scheduler, business database, Hive/HDFS connection or cleanup is used.
 */
class DevelopmentCategoryAccessTest {
    private JdbcTemplate jdbc;
    private DevelopmentResourceAccess access;
    private DppEtlTaskController controller;
    private AttTaskCatServiceImpl integration;

    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:development-access-" + UUID.randomUUID()
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
        integration = new AttTaskCatServiceImpl();
        var catMapper = session.getMapper(AttTaskCatMapper.class);
        ReflectionTestUtils.setField(integration,"baseMapper",catMapper);
        ReflectionTestUtils.setField(integration,"attTaskCatMapper",catMapper);
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
        jdbc.execute("CREATE TABLE ATT_CLEAN_RULE(id BIGINT PRIMARY KEY,cat_code VARCHAR(128))");
        for (int n=1;n<=40;n++) jdbc.update("INSERT INTO ATT_CLEAN_RULE VALUES (?,?)",n,"A01A01");
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
        var sequence = new AtomicLong(9000000000000000L);
        ISchedulerAdapter generator = (ISchedulerAdapter) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ISchedulerAdapter.class}, (p,m,a) -> {
                    if (m.getName().equals("generateTaskCode")) return sequence.incrementAndGet();
                    throw new UnsupportedOperationException(m.getName());
                });
        ReflectionTestUtils.setField(tasks,"schedulerAdapter",generator);
        controller = new DppEtlTaskController();
        ReflectionTestUtils.setField(controller,"resourceAccess",access);
        ReflectionTestUtils.setField(controller,"dppEtlTaskService",tasks);
    }
    private void login() {
        var user = new SysUser(); user.setUserId(926L); user.setUserName("offline-fixture"); user.setRoles(List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(926L,null,user,Set.of()),null,List.of()));
    }
    @AfterEach void finish() { SecurityContextHolder.clearContext(); }
    private String table(String type) { return "3".equals(type) ? "att_data_dev_cat" : "att_task_cat"; }
    private long category(String type,long project,String code) {
        jdbc.update("INSERT INTO " + table(type) + "(project_id,code,parent_id,name,valid_flag) VALUES (?,?,0,'fixture',1)",project,code);
        return jdbc.queryForObject("SELECT MAX(id) FROM " + table(type),Long.class);
    }
    private DppEtlNewNodeSaveReqVO draft(String type,Long project,String category) {
        var request = new DppEtlNewNodeSaveReqVO(); request.setType(type); request.setProjectId(project);
        request.setProjectCode(project==null ? null : project*1000+project);
        request.setCatCode(category); request.setName("offline-task"); request.setScheduler("QUARTZ");
        request.setPersonCharge("926"); request.setCrontab("0 0 0 1 1 ? 2099"); return request;
    }
    private DppEtlNewNodeSaveReqVO save(DppEtlNewNodeSaveReqVO request) {
        var result = controller.createEtlTaskFront(request);
        assertEquals(200,result.getCode()); return (DppEtlNewNodeSaveReqVO) result.getData();
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table,Long.class); }

    @ParameterizedTest @ValueSource(strings={"1","3"})
    void sameCodeInProjects5And6CreatesOneTaskPerRequestInItsOwnCategory(String type) {
        long a=category(type,5,"A01"), b=category(type,6,"A01");
        var first=save(draft(type,5L,"A01")); assertEquals(1,count("dpp_etl_task"));
        assertEquals(a,jdbc.queryForObject("SELECT cat_id FROM dpp_etl_task WHERE id=?",Long.class,first.getId()));
        var second=save(draft(type,6L,"A01")); assertEquals(2,count("dpp_etl_task"));
        assertEquals(b,jdbc.queryForObject("SELECT cat_id FROM dpp_etl_task WHERE id=?",Long.class,second.getId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task WHERE project_id=? AND type=?",Integer.class,5,type));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task WHERE project_id=? AND type=?",Integer.class,6,type));
        assertEquals(2,count("dpp_etl_scheduler")); assertNotEquals(first.getCode(),second.getCode());
    }
    @Test void omittedTypeStillUsesTheIntegrationCategory() {
        category("1",5,"A01"); category("1",6,"A01");
        var saved=save(draft(null,5L,"A01")); assertEquals("1",saved.getType()); assertEquals(1,count("dpp_etl_task"));
    }
    @Test void duplicateNameAndInvalidNameFollowTheSameExistingBusinessErrorConvention() {
        var request = new AttTaskCatSaveReqVO(); request.setProjectId(5L); request.setParentId(0L);
        request.setName("same-name"); integration.createAttTaskCat(request);
        var duplicate = assertThrows(ServiceException.class,()->integration.createAttTaskCat(request));
        request.setName(" ");
        var invalid = assertThrows(ServiceException.class,()->integration.createAttTaskCat(request));
        var handler = new tech.qiantong.qdata.security.web.exception.GlobalExceptionHandler();
        var http = new org.springframework.mock.web.MockHttpServletRequest();
        for(var exception : List.of(duplicate,invalid)) {
            assertNull(exception.getCode());
            assertEquals(500,handler.handleServiceException(exception,http).get("code"));
            assertFalse(exception.getMessage().isBlank());
        }
        assertEquals("att.error.cat.name.duplicate",duplicate.getI18nCode());
        assertEquals("att.error.cat.name.invalid",invalid.getI18nCode());
        assertEquals(1,count("att_task_cat"));
    }
    @ParameterizedTest @ValueSource(strings={"1","3"})
    void foreignOnlyDeletedOrAmbiguousOwnCategoryIsDeniedBeforeAnyWrite(String type) {
        category(type,6,"foreign");
        category(type,5,"deleted"); jdbc.update("UPDATE " + table(type) + " SET del_flag=1 WHERE code='deleted'");
        category(type,6,"deleted"); category(type,5,"duplicate"); category(type,5,"duplicate");
        for(String code:List.of("foreign","deleted","duplicate","missing"))
            assertThrows(AccessDeniedException.class,()->save(draft(type,5L,code)));
        assertEquals(0,count("dpp_etl_task")); assertEquals(0,count("dpp_etl_scheduler"));
    }
    @ParameterizedTest @ValueSource(strings={"1","3"})
    void oldBin11catAndFortySyntheticRulesRemainUnchanged(String type) {
        category(type,5,"bin11cat"); category(type,6,"bin11cat");
        var before=jdbc.queryForList("SELECT * FROM " + table(type) + " ORDER BY id");
        var rules=jdbc.queryForList("SELECT * FROM att_clean_rule ORDER BY id");
        save(draft(type,5L,"bin11cat"));
        assertEquals(before,jdbc.queryForList("SELECT * FROM " + table(type) + " ORDER BY id"));
        assertEquals(rules,jdbc.queryForList("SELECT * FROM att_clean_rule ORDER BY id"));
    }
    @Test void missingInvalidInvisibleOrMismatchedProjectAndReadonlyPermissionFailClosed() {
        category("3",5,"A01"); category("3",7,"A01");
        for(Long project:new Long[]{null,0L,-1L,999L,8L,7L})
            assertThrows(AccessDeniedException.class,()->save(draft("3",project,"A01")));
        var request=draft("3",5L,"A01"); request.setProjectCode(6006L);
        assertThrows(AccessDeniedException.class,()->save(request)); assertEquals(0,count("dpp_etl_task"));
    }
    @ParameterizedTest @ValueSource(strings={"1","3"})
    void emptyProjectsAndEightConcurrentCreatesCannotBorrowForeignCategoryIds(String type) throws Exception {
        category(type,5,"A01"); category(type,6,"A01");
        assertEquals(0,count("dpp_etl_task"));
        try(var pool=Executors.newFixedThreadPool(8)) {
            var ready=new CountDownLatch(8); var start=new CountDownLatch(1);
            var pending=new ArrayList<Future<DppEtlNewNodeSaveReqVO>>();
            for(int n=0;n<8;n++) { final long project=n%2==0?5:6;
                pending.add(pool.submit(()->{login(); ready.countDown();
                    try { assertTrue(start.await(10,TimeUnit.SECONDS)); return save(draft(type,project,"A01")); }
                    finally { SecurityContextHolder.clearContext(); }
                }));
            }
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown(); var codes=new HashSet<String>();
            for(var future:pending) assertTrue(codes.add(future.get(20,TimeUnit.SECONDS).getCode()));
        }
        assertEquals(8,count("dpp_etl_task")); assertEquals(8,count("dpp_etl_scheduler"));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task WHERE project_id=5",Integer.class));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task WHERE project_id=6",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dpp_etl_task t JOIN " + table(type)
                + " c ON t.cat_id=c.id WHERE t.project_id<>c.project_id OR t.cat_code<>c.code",Integer.class));
    }
    private DppEtlNewNodeSaveReqVO definitions(String json) {
        var request=draft("3",5L,null); request.setTaskDefinitionList(json); return request;
    }
    @ParameterizedTest @ValueSource(strings={"dpp_etl_node","dpp_etl_node_log"})
    void globalNodeCodeOwnershipIsPreservedIncludingHistoricalRecords(String table) {
        jdbc.update("INSERT INTO " + table + " VALUES ('own',5,'0'),('foreign',6,'0'),('history',6,'1'),('shared',5,'0'),('shared',6,'0')");
        assertDoesNotThrow(()->access.request(definitions("[{\"code\":\"own\"},{\"code\":\"new\"}]"),"add"));
        for(String code:List.of("foreign","history","shared"))
            assertThrows(AccessDeniedException.class,()->access.request(definitions("[{\"code\":\""+code+"\"}]"),"add"));
        assertEquals(0,count("dpp_etl_task"));
    }
    private void task(long project,String code,int deleted) {
        jdbc.update("INSERT INTO dpp_etl_task(project_id,project_code,code,type,del_flag) VALUES (?,?,?,'3',?)",project,""+(project*1000+project),code,deleted);
    }
    private DppEtlNewNodeSaveReqVO subprocess(String code) {
        return definitions("[{\"code\":\"new-node\",\"taskParams\":{\"processDefinitionCode\":\""+code+"\"}}]");
    }
    @Test void subprocessUsesRequestProjectEvenWhenTaskCodeAlsoExistsInAnotherProject() {
        task(5,"same-task",0); task(6,"same-task",0);
        assertDoesNotThrow(()->access.request(subprocess("same-task"),"add"));
        var omittedCode=subprocess("same-task"); omittedCode.setProjectCode(null);
        assertDoesNotThrow(()->access.request(omittedCode,"add"));
        assertEquals(5L,((Number)access.taskCode("same-task","5005").get("project_id")).longValue());
        assertThrows(AccessDeniedException.class,()->access.taskCode("same-task",null));
    }
    @Test void foreignOnlyDeletedAmbiguousOrUnreadableSubprocessStillFailsClosed() {
        task(6,"foreign",0); task(5,"deleted",1); task(6,"deleted",0);
        task(5,"duplicate",0); task(5,"duplicate",0);
        for(String code:List.of("foreign","deleted","duplicate","missing"))
            assertThrows(AccessDeniedException.class,()->access.request(subprocess(code),"add"));
        task(5,"unreadable",0);
        jdbc.update("UPDATE system_menu SET perms='dpp:developTask:add' WHERE menu_id=1");
        assertThrows(AccessDeniedException.class,()->access.request(subprocess("unreadable"),"add"));
    }
}
