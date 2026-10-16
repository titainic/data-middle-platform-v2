package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.yulichang.injector.MPJSqlInjector;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import tech.qiantong.qdata.module.att.api.cat.IAttCatService;
import tech.qiantong.qdata.module.att.api.cat.dto.AttDataDevCatRespDTO;
import tech.qiantong.qdata.module.att.api.cat.dto.AttTaskCatRespDTO;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttDataDevCatSaveReqVO;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatSaveReqVO;
import tech.qiantong.qdata.module.att.dal.dataobject.cat.AttDataDevCatDO;
import tech.qiantong.qdata.module.att.dal.dataobject.cat.AttTaskCatDO;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttCatMapper;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttDataDevCatMapper;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttTaskCatMapper;
import tech.qiantong.qdata.module.att.service.cat.IAttDataDevCatService;
import tech.qiantong.qdata.module.att.service.cat.IAttTaskCatService;
import tech.qiantong.qdata.module.att.service.cat.impl.AttCatServiceImpl;
import tech.qiantong.qdata.module.att.service.cat.impl.AttDataDevCatServiceImpl;
import tech.qiantong.qdata.module.att.service.cat.impl.AttTaskCatServiceImpl;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskPageReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskRespVO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskSaveReqVO;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlTaskTreeRespVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.dal.mapper.etl.DppEtlTaskMapper;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlTaskExtService;
import tech.qiantong.qdata.module.dpp.service.etl.impl.DppEtlTaskServiceImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real category/task services, MyBatis SQL, joins and Spring transactions. H2 is synthetic storage.
 * No application login, business HTTP, cluster connection or cleanup. Extension enrichment alone is a null fixture.
 * H2 is not evidence for MySQL repeatable-read locking or multi-instance runtime behavior.
 */
class CategoryProjectIsolationTest {
    enum Kind {
        INTEGRATION("ATT_TASK_CAT", "1"), DEVELOPMENT("ATT_DATA_DEV_CAT", "3");
        final String table, type;
        Kind(String table, String type) { this.table = table; this.type = type; }
    }
    private JdbcTemplate jdbc;
    private IAttTaskCatService integration;
    private IAttDataDevCatService development;
    private DppEtlTaskServiceImpl tasks;
    private DppEtlTaskMapper taskMapper;
    private IAttCatService categoryLookup;
    private Object previousBeanFactory;

    @BeforeEach void setup() throws Exception {
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        var beans = new DefaultListableBeanFactory();
        var messages = new ResourceBundleMessageSource();
        messages.setBasenames("i18n/messages", "i18n/log-messages");
        beans.registerSingleton("messageSource", messages);
        new SpringUtils().postProcessBeanFactory(beans);
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cat-project-" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=SYSTEM_USER;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        ds.setUser("sa"); jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ATT_PROJECT(id BIGINT PRIMARY KEY, del_flag VARCHAR(1) DEFAULT '0')");
        jdbc.update("INSERT INTO ATT_PROJECT(id) VALUES (1),(5)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.getTypeAliasRegistry().registerAlias(DppEtlTaskDO.class);
        var global = com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();
        global.setSqlInjector(new MPJSqlInjector());
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config, global);
        for (var mapper : List.of(AttTaskCatMapper.class, AttDataDevCatMapper.class, AttCatMapper.class, DppEtlTaskMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(config); factory.setGlobalConfig(global);
        var pagination = new MybatisPlusInterceptor(); pagination.addInnerInterceptor(new PaginationInnerInterceptor());
        factory.setPlugins(pagination);
        factory.setMapperLocations(new ClassPathResource("mapper/att/cat/AttCatMapper.xml"),
                new ClassPathResource("mapper/dpp/etl/DppEtlTaskMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        for (var entity : List.of(AttTaskCatDO.class, AttDataDevCatDO.class, DppEtlTaskDO.class)) {
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
        jdbc.execute("CREATE TABLE SYSTEM_USER(user_id BIGINT,nick_name VARCHAR(128),phonenumber VARCHAR(64),del_flag CHAR(1))");
        jdbc.execute("CREATE TABLE DPP_ETL_SCHEDULER(id BIGINT,task_id BIGINT,cron_expression VARCHAR(128),status VARCHAR(8),del_flag CHAR(1))");
        jdbc.execute("CREATE TABLE DPP_ETL_TASK_INSTANCE(id BIGINT,task_code VARCHAR(128),create_time TIMESTAMP,end_time TIMESTAMP,status VARCHAR(8),del_flag CHAR(1))");
        jdbc.execute("CREATE TABLE ATT_CLEAN_RULE(id BIGINT PRIMARY KEY,cat_code VARCHAR(128))");
        for (int n = 1; n <= 40; n++) jdbc.update("INSERT INTO ATT_CLEAN_RULE VALUES (?,?)", n, "A01A01");
        taskMapper = session.getMapper(DppEtlTaskMapper.class);
        tasks = new DppEtlTaskServiceImpl();
        ReflectionTestUtils.setField(tasks, "baseMapper", taskMapper);
        ReflectionTestUtils.setField(tasks, "dppEtlTaskMapper", taskMapper);
        IDppEtlTaskExtService extensions = (IDppEtlTaskExtService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDppEtlTaskExtService.class}, (p,m,a) -> null);
        ReflectionTestUtils.setField(tasks, "dppEtlTaskExtService", extensions);
        var lookup = new AttCatServiceImpl(); ReflectionTestUtils.setField(lookup, "attCatMapper", session.getMapper(AttCatMapper.class));
        categoryLookup = lookup; ReflectionTestUtils.setField(tasks, "attCatService", lookup);
        var a = new AttTaskCatServiceImpl(); var b = new AttDataDevCatServiceImpl();
        var aMapper = session.getMapper(AttTaskCatMapper.class); var bMapper = session.getMapper(AttDataDevCatMapper.class);
        ReflectionTestUtils.setField(a, "baseMapper", aMapper); ReflectionTestUtils.setField(a, "attTaskCatMapper", aMapper);
        ReflectionTestUtils.setField(b, "baseMapper", bMapper); ReflectionTestUtils.setField(b, "attDataDevCatMapper", bMapper);
        ReflectionTestUtils.setField(a, "dppEtlTaskService", tasks); ReflectionTestUtils.setField(b, "dppEtlTaskService", tasks);
        integration = (IAttTaskCatService) transactional(a, ds);
        development = (IAttDataDevCatService) transactional(b, ds);
    }
    private Object transactional(Object target, JdbcDataSource ds) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        return proxy.getProxy();
    }
    @AfterEach void finish() { ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeanFactory); }
    private long category(Kind kind, long project, long parent, String code) {
        jdbc.update("INSERT INTO " + kind.table + "(project_id,project_code,parent_id,code,name,valid_flag) VALUES (?,?,?,?,?,TRUE)", project,"p"+project,parent,code,"same-name");
        return jdbc.queryForObject("SELECT MAX(id) FROM " + kind.table, Long.class);
    }
    private long task(Kind kind, long project, long category, String code) {
        jdbc.update("INSERT INTO DPP_ETL_TASK(project_id,cat_id,cat_code,type,status,name) VALUES (?,?,?,?,'-1','task')",project,category,code,kind.type);
        return jdbc.queryForObject("SELECT MAX(id) FROM DPP_ETL_TASK", Long.class);
    }
    private boolean has(Kind k, long id) { return k == Kind.INTEGRATION ? integration.hasIntegrationTask(id) : development.hasDataDevelopmentTask(id); }
    private long direct(Kind k, long id) { return k == Kind.INTEGRATION ? integration.getIntegrationTaskCount(id) : development.getDataDevelopmentTaskCount(id); }
    private int remove(Kind k, Long... ids) { return k == Kind.INTEGRATION ? integration.removeAttTaskCat(List.of(ids)) : development.removeAttDataDevCat(List.of(ids)); }
    private boolean name(Kind k, long project, long parent) { return k == Kind.INTEGRATION ? integration.isNameUsed(project,null,parent,"same-name") : development.isNameUsed(project,null,parent,"same-name"); }
    private Object snapshot(Kind k, long project) { return jdbc.queryForList("SELECT * FROM " + k.table + " WHERE project_id=? ORDER BY id",project); }
    private Object rules() { return jdbc.queryForList("SELECT * FROM ATT_CLEAN_RULE ORDER BY id"); }
    private long create(Kind k, long project, long parent, String name) {
        if (k == Kind.INTEGRATION) {
            var request = new AttTaskCatSaveReqVO(); request.setProjectId(project); request.setParentId(parent); request.setName(name); request.setValidFlag(true);
            return integration.createAttTaskCat(request);
        }
        var request = new AttDataDevCatSaveReqVO(); request.setProjectId(project); request.setParentId(parent); request.setName(name); request.setValidFlag(true);
        return development.createAttDataDevCat(request);
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void anotherProjectsSameCodeAndChildrenDoNotBlockAnEmptyCategory(Kind k) {
        long foreign = category(k,1,0,"bin11cat"), own = category(k,5,0,"bin11cat");
        category(k,1,foreign,"bin11catA01"); task(k,1,foreign,"bin11cat");
        Object before = snapshot(k,1), rules = rules();
        assertFalse(has(k,own)); assertEquals(0,direct(k,own)); assertEquals(1,remove(k,own));
        assertEquals(before,snapshot(k,1)); assertEquals(rules,rules());
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void directAndDescendantCountsAlwaysUseTheCategoryOwner(Kind k) {
        long a = category(k,1,0,"A01"), b = category(k,5,0,"A01");
        task(k,1,a,"A01"); task(k,1,b,"A01"); // legacy mismatched foreign cat_id cannot leak into b's count
        task(k,5,b,"A01"); task(k,5,999,"A01A01");
        assertEquals(1,direct(k,b)); assertTrue(has(k,b));
        assertEquals(2,tasks.getCountByCatCode(5L,"A01",List.of(k.type)));
        Object before = snapshot(k,5); assertThrows(ServiceException.class,()->remove(k,b)); assertEquals(before,snapshot(k,5));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void taskTypesDoNotBorrowAnotherCategoryFamilysCounts(Kind k) {
        long own=category(k,5,0,"A01");
        Kind other=k==Kind.INTEGRATION?Kind.DEVELOPMENT:Kind.INTEGRATION;
        task(other,5,own,"A01");
        assertFalse(has(k,own)); assertEquals(0,direct(k,own)); assertEquals(1,remove(k,own));
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void oldParentWithAnIrregularChildCodeIsStillProtected(Kind k) {
        long parent = category(k,5,0,"bin11cat"); category(k,5,parent,"historical-child");
        assertThrows(ServiceException.class,()->remove(k,parent));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM " + k.table + " WHERE del_flag=0",Integer.class));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void foreignParentReferenceDoesNotBecomeOwnChildCount(Kind k) {
        long parent = category(k,5,0,"bin11cat"); category(k,1,parent,"foreign-child");
        Object before = snapshot(k,1); assertEquals(1,remove(k,parent)); assertEquals(before,snapshot(k,1));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void wildcardCharactersInLegacyCodesAreLiteral(Kind k) {
        long own = category(k,5,0,"old_%"); category(k,5,0,"oldXY"); task(k,5,999,"oldZZ");
        assertFalse(has(k,own)); assertEquals(1,remove(k,own));
        assertEquals(1,tasks.getCountByCatCode(5L,"old",List.of(k.type)));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void batchValidationPrecedesAnyDeleteAndDeletedRowsAreExcluded(Kind k) {
        long empty = category(k,5,0,"A01"), used = category(k,5,0,"B01");
        long deleted = task(k,5,used,"B01");
        jdbc.update("UPDATE DPP_ETL_TASK SET del_flag=1 WHERE id=?",deleted);
        assertFalse(has(k,used)); task(k,5,used,"B01");
        Object before = snapshot(k,5); assertThrows(ServiceException.class,()->remove(k,empty,used)); assertEquals(before,snapshot(k,5));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void parentNameAggregationNeverBorrowsAnotherProjectsRoot(Kind k) {
        category(k,1,0,"A01"); assertTrue(name(k,1,0)); assertFalse(name(k,5,0));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void emptyProjectAndLegacyParentCodeAllocationStayInTheirOwnProject(Kind k) {
        category(k,1,0,"bin11cat"); category(k,1,0,"Z99");
        long root = create(k,5,0,"new-root");
        assertEquals("A01",jdbc.queryForObject("SELECT code FROM " + k.table + " WHERE id=?",String.class,root));
        long parent = category(k,5,0,"bin11cat"); create(k,5,parent,"child");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM " + k.table + " WHERE project_id=5 AND code='bin11catA01'",Integer.class));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void categoryIdLookupAndTaskSaveChooseTheirOwnProject(Kind k) {
        long a=category(k,1,0,"A01"), b=category(k,5,0,"A01");
        assertEquals(a,categoryLookup.getCatIdByTableNameAndCatCode(k.table,"A01",1L));
        assertEquals(b,categoryLookup.getCatIdByTableNameAndCatCode(k.table,"A01",5L));
        var request = new DppEtlTaskSaveReqVO(); request.setName("offline"); request.setProjectId(5L); request.setType(k.type); request.setCatCode("A01");
        long id = tasks.createDppEtlTask(request);
        assertEquals(b,jdbc.queryForObject("SELECT cat_id FROM DPP_ETL_TASK WHERE id=?",Long.class,id));
        long other = category(k,5,0,"B01");
        var update = new DppEtlTaskSaveReqVO(); update.setId(id); update.setCatCode("B01");
        assertEquals(1,tasks.updateDppEtlTask(update));
        assertEquals(other,jdbc.queryForObject("SELECT cat_id FROM DPP_ETL_TASK WHERE id=?",Long.class,id));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void crossProjectParallelCountsAndDeletesDoNotTouchProtectedProject(Kind k) throws Exception {
        long own=category(k,1,0,"bin11cat"); task(k,1,own,"bin11cat");
        var ids = new ArrayList<Long>(); for(int n=0;n<8;n++) ids.add(category(k,5,0,"bin11cat"+n));
        Object before=snapshot(k,1), rules=rules();
        var pool=Executors.newFixedThreadPool(8); var start=new CountDownLatch(1); var jobs=new ArrayList<Future<Integer>>();
        try {
            for(long id:ids) jobs.add(pool.submit(()-> {assertTrue(start.await(10,TimeUnit.SECONDS)); assertTrue(has(k,own)); assertEquals(1,direct(k,own)); return remove(k,id);}));
            start.countDown(); for(var job:jobs) assertEquals(1,job.get(15,TimeUnit.SECONDS));
        } finally {start.countDown(); pool.shutdownNow();}
        assertEquals(before,snapshot(k,1)); assertEquals(rules,rules());
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void concurrentCreatesUnderSameLegacyCodeRemainIndependentAcrossProjects(Kind k) throws Exception {
        long first=category(k,1,0,"bin11cat"), second=category(k,5,0,"bin11cat");
        Object rules=rules();
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);var jobs=new ArrayList<Future<Long>>();
        try {
            for(int n=0;n<8;n++) {
                int number=n;long project=n%2==0?1:5;long parent=project==1?first:second;
                jobs.add(pool.submit(()-> {assertTrue(start.await(10,TimeUnit.SECONDS));return create(k,project,parent,"parallel-"+(number/2));}));
            }
            start.countDown();for(var job:jobs) assertNotNull(job.get(15,TimeUnit.SECONDS));
        } finally {start.countDown();pool.shutdownNow();}
        var a=jdbc.queryForList("SELECT code FROM " + k.table + " WHERE parent_id=? ORDER BY code",String.class,first);
        var b=jdbc.queryForList("SELECT code FROM " + k.table + " WHERE parent_id=? ORDER BY code",String.class,second);
        assertEquals(List.of("bin11catA01","bin11catA02","bin11catA03","bin11catA04"),a);assertEquals(a,b);
        assertEquals(0,direct(k,first));assertEquals(0,direct(k,second));assertEquals(rules,rules());
    }

    @Test void missingProjectOrBlankCodeFailsClosed() {
        for(Long p:new Long[]{null,0L,-1L}) {
            assertThrows(ServiceException.class,()->tasks.getCountByCatCode(p,"A01",List.of("1")));
            assertThrows(ServiceException.class,()->tasks.getCountByCatId(p,1L,List.of("1")));
            assertThrows(ServiceException.class,()->categoryLookup.getCatIdByTableNameAndCatCode("ATT_TASK_CAT","A01",p));
        }
        for(String code:new String[]{null,""," "}) assertThrows(ServiceException.class,()->tasks.getCountByCatCode(5L,code,List.of("1")));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void legacyCategoryWithoutProjectOrCodeCannotTriggerUnscopedDeletion(Kind k) {
        long id=category(k,5,0,""); assertThrows(ServiceException.class,()->remove(k,id));
        jdbc.update("UPDATE " + k.table + " SET project_id=NULL,code='A01' WHERE id=?",id);
        assertThrows(ServiceException.class,()->remove(k,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM " + k.table + " WHERE del_flag=0",Integer.class));
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void listJoinsNeverMultiplyTasksOrUseAnotherProjectsLabel(Kind k) {
        category(k,1,0,"A01"); long own=category(k,5,0,"A01");
        jdbc.update("UPDATE " + k.table + " SET name='own-label' WHERE id=?",own);
        task(k,5,own,"A01");
        var request=new DppEtlTaskPageReqVO();request.setType(k.type);request.setProjectId(5L);
        request.setPageNum(1);request.setPageSize(10);
        // Both Java-wrapper and XML mapper paths execute their real joins.
        var page=taskMapper.selectPage(request);
        assertEquals(1,page.getTotal());assertEquals(1,page.getRows().size());
        assertEquals("own-label",page.getRows().getFirst().getCatName());
        var xml=taskMapper.getDppEtlTaskPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1,10),request);
        assertEquals(1,xml.getTotal());assertEquals(1,xml.getRecords().size());
        assertEquals("own-label",xml.getRecords().getFirst().getCatName());
    }

    private DppEtlTaskTreeRespVO tree(Kind k, List<DppEtlTaskRespVO> rows, long parent) {
        if(k==Kind.INTEGRATION) {
            var a=new AttTaskCatRespDTO(); a.setId(10L);a.setProjectId(1L);a.setCode("A01");a.setName("p1");a.setParentId(parent);
            var b=new AttTaskCatRespDTO(); b.setId(20L);b.setProjectId(5L);b.setCode("A01");b.setName("p5");b.setParentId(0L);
            return tasks.buildTaskCatTree(rows,List.of(a,b));
        }
        var a=new AttDataDevCatRespDTO(); a.setId(10L);a.setProjectId(1L);a.setCode("A01");a.setName("p1");a.setParentId(parent);
        var b=new AttDataDevCatRespDTO(); b.setId(20L);b.setProjectId(5L);b.setCode("A01");b.setName("p5");b.setParentId(0L);
        return ReflectionTestUtils.invokeMethod(tasks,"buildTaskDevCaTree",rows,List.of(a,b));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void taskTreeAggregatesByProjectAndCodeAndRejectsAForeignParentLink(Kind k) {
        var a=new DppEtlTaskRespVO();a.setId(101L);a.setProjectId(1L);a.setCatCode("A01");a.setType(k.type);
        var b=new DppEtlTaskRespVO();b.setId(501L);b.setProjectId(5L);b.setCatCode("A01");b.setType(k.type);
        var root=tree(k,List.of(a,b),20L);
        assertEquals(2,root.getChildren().size());
        for(var node:root.getChildren()) {
            assertEquals(1,node.getDppEtlTaskCount()); assertEquals(1,node.getChildren().size());
            assertEquals(node.getProjectId(),node.getChildren().getFirst().getProjectId());
        }
        assertEquals(2,root.getDppEtlTaskCount());
    }
}
