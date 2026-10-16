package tech.qiantong.qdata.module.att.cat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttTaskCatSaveReqVO;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttTaskCatMapper;
import tech.qiantong.qdata.module.att.service.cat.IAttTaskCatService;
import tech.qiantong.qdata.module.att.service.cat.impl.AttTaskCatServiceImpl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real service, transaction proxy and MyBatis SQL; only storage is synthetic in-process H2/MySQL mode.
 * No external connections, cleanup statements, mocked locks or mocked code allocation.
 */
class TaskCategoryCodeAllocationTest {
    private JdbcTemplate jdbc;
    private IAttTaskCatService service;

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:task-code-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        ds.setUser("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ATT_PROJECT (id BIGINT PRIMARY KEY, del_flag VARCHAR(1) DEFAULT '0')");
        jdbc.update("INSERT INTO ATT_PROJECT(id) VALUES (1),(5)");
        jdbc.execute("CREATE TABLE ATT_TASK_CAT (id BIGINT AUTO_INCREMENT PRIMARY KEY, project_id BIGINT, "
                + "project_code VARCHAR(128), name VARCHAR(128), parent_id BIGINT, sort_order BIGINT, "
                + "description VARCHAR(512), code VARCHAR(128), valid_flag BOOLEAN DEFAULT TRUE, "
                + "del_flag BOOLEAN DEFAULT FALSE, creator_id BIGINT, create_by VARCHAR(100), create_time TIMESTAMP, "
                + "updater_id BIGINT, update_by VARCHAR(100), update_time TIMESTAMP, remark VARCHAR(512))");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AttTaskCatMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AttTaskCatMapper.class);
        var target = new AttTaskCatServiceImpl();
        ReflectionTestUtils.setField(target, "baseMapper", mapper);
        ReflectionTestUtils.setField(target, "attTaskCatMapper", mapper);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),
                new AnnotationTransactionAttributeSource()));
        service = (IAttTaskCatService) proxy.getProxy();
    }

    private Long category(long project, long parent, String code) {
        jdbc.update("INSERT INTO ATT_TASK_CAT(project_id,parent_id,code) VALUES (?,?,?)", project, parent, code);
        return jdbc.queryForObject("SELECT MAX(id) FROM ATT_TASK_CAT", Long.class);
    }

    private Long create(long project, Long parent, String name) {
        var request = new AttTaskCatSaveReqVO();
        request.setProjectId(project); request.setProjectCode("project-" + project);
        request.setParentId(parent); request.setName(name); request.setValidFlag(true);
        request.setCode("untrusted-client-code");
        return service.createAttTaskCat(request);
    }

    private String code(Long id) {
        return jdbc.queryForObject("SELECT code FROM ATT_TASK_CAT WHERE id=?", String.class, id);
    }

    @Test void oldBin11catInAnotherProjectCannotBreakTheReportedPostServicePath() {
        Long old = category(1, 0, "bin11cat");
        assertEquals("A01", code(create(5, 0L, "new-root")));
        assertEquals("bin11cat", code(old));
    }

    @Test void sameParentAndNameInDifferentProjectsAllocateIndependently() {
        category(1, 0, "Z99");
        assertEquals("A01", code(create(5, 0L, "same-name")));
        assertEquals("A01", code(create(1, 0L, "same-name")));
        assertEquals("A02", code(create(5, 0L, "second-root")));
    }

    @Test void malformedEmptyAndNullRootCodesNeverReachNumericParsing() {
        for (String value : new String[]{"bin11cat", "letters", "Z", "123", "", " ", null, "A00", "A1", "A01bad", "a02"}) {
            category(5, 0, value);
        }
        var before = jdbc.queryForList("SELECT * FROM ATT_TASK_CAT ORDER BY id");
        assertEquals("A03", code(create(5, 0L, "new-root")));
        assertEquals(before, jdbc.queryForList("SELECT * FROM ATT_TASK_CAT WHERE id<=? ORDER BY id", before.size()));
    }

    @Test void legacyParentIsAnOpaquePrefixAndUnrelatedSiblingPrefixesAreIgnored() {
        Long parent = category(5, 0, "bin11cat");
        category(5, parent, "otherZ99"); category(5, parent, "bin11catletters");
        category(5, parent, "bin11catA01");
        category(1, parent, "bin11catA02");
        assertEquals("bin11catA02", code(create(5, parent, "child")));
        assertEquals("bin11cat", code(parent));
    }

    @Test void validParentAndItsOtherBranchesKeepTheirHierarchy() {
        Long parent = category(5, 0, "A01");
        category(5, parent, "A01A01"); category(5, parent, "A02Z99");
        assertEquals("A01A02", code(create(5, parent, "child")));
    }

    @Test void missingForeignDisabledDeletedOrBlankParentFailsBeforeInsert() {
        var parents = new ArrayList<Long>();
        parents.add(999L); parents.add(category(1, 0, "A01"));
        parents.add(category(5, 0, null)); parents.add(category(5, 0, ""));
        parents.add(category(5, 0, " "));
        Long disabled = category(5, 0, "A02");
        jdbc.update("UPDATE ATT_TASK_CAT SET valid_flag=FALSE WHERE id=?", disabled);
        parents.add(disabled);
        Long deleted = category(5, 0, "A03");
        jdbc.update("UPDATE ATT_TASK_CAT SET del_flag=TRUE WHERE id=?", deleted);
        parents.add(deleted);
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM ATT_TASK_CAT", Integer.class);
        for (Long parent : parents) assertThrows(ServiceException.class, () -> create(5, parent, "bad-parent"));
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM ATT_TASK_CAT", Integer.class));
    }

    @Test void noProjectInvalidProjectAndNegativeParentAreBusinessFailures() {
        var request = new AttTaskCatSaveReqVO(); request.setName("missing-project"); request.setParentId(0L);
        assertThrows(ServiceException.class, () -> service.createAttTaskCat(request));
        assertThrows(ServiceException.class, () -> create(999, 0L, "missing-project"));
        assertThrows(ServiceException.class, () -> create(5, -1L, "negative-parent"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ATT_TASK_CAT", Integer.class));
    }

    @Test void nullParentNormalizesToRootAndClientCodeIsNotTrusted() {
        Long id = create(5, null, "new-root");
        assertEquals("A01", code(id));
        assertEquals(0L, jdbc.queryForObject("SELECT parent_id FROM ATT_TASK_CAT WHERE id=?", Long.class, id));
    }

    @Test void historyAndOrphanDescendantsReserveTheirProjectPrefix() {
        Long deleted = category(5, 0, "A01");
        jdbc.update("UPDATE ATT_TASK_CAT SET del_flag=TRUE WHERE id=?", deleted);
        category(5, 999, "A02A01");
        assertEquals("A03", code(create(5, 0L, "new-root")));
    }

    @Test void boundaryAdvancesFromA99ToB01WithoutChangingDepth() {
        for (int n = 1; n <= 99; n++) category(5, 0, String.format("A%02d", n));
        assertEquals("B01", code(create(5, 0L, "new-root")));
    }

    @Test void exhaustedLevelFailsInsteadOfStealingADescendantBranch() {
        var rows = new ArrayList<Object[]>();
        for (char letter = 'A'; letter <= 'Z'; letter++) for (int n = 1; n <= 99; n++) {
            rows.add(new Object[]{5L, 0L, String.format("%c%02d", letter, n)});
        }
        jdbc.batchUpdate("INSERT INTO ATT_TASK_CAT(project_id,parent_id,code) VALUES (?,?,?)", rows);
        assertThrows(ServiceException.class, () -> create(5, 0L, "no-space"));
        assertEquals(2574, jdbc.queryForObject("SELECT COUNT(*) FROM ATT_TASK_CAT", Integer.class));
    }

    @Test void codeLengthLimitFailsBeforeInsert() {
        Long parent = category(5, 0, "A01".repeat(42));
        assertThrows(ServiceException.class, () -> create(5, parent, "too-deep"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ATT_TASK_CAT", Integer.class));
    }

    @Test void fortyExistingSyntheticRulesAndLegacyCategoryStayByteForByteUnchanged() {
        jdbc.execute("CREATE TABLE ATT_CLEAN_RULE(id BIGINT PRIMARY KEY, cat_code VARCHAR(128))");
        for (int n = 1; n <= 40; n++) jdbc.update("INSERT INTO ATT_CLEAN_RULE VALUES (?,?)", n, "A01A01");
        category(1, 0, "bin11cat");
        var rules = jdbc.queryForList("SELECT * FROM ATT_CLEAN_RULE ORDER BY id");
        var old = jdbc.queryForList("SELECT * FROM ATT_TASK_CAT ORDER BY id");
        create(5, 0L, "new-root");
        assertEquals(rules, jdbc.queryForList("SELECT * FROM ATT_CLEAN_RULE ORDER BY id"));
        assertEquals(old, jdbc.queryForList("SELECT * FROM ATT_TASK_CAT WHERE project_id=1 ORDER BY id"));
    }

    private List<Long> parallelCreates(Long parent, boolean sameName) throws Exception {
        int count = 8;
        var executor = Executors.newFixedThreadPool(count);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(count);
        var futures = new ArrayList<Future<Long>>();
        try {
            for (int n = 0; n < count; n++) {
                int number = n;
                futures.add(executor.submit(() -> {
                    ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { return create(5, parent, sameName ? "same-name" : "parallel-" + number); }
                    catch (ServiceException duplicate) { if (!sameName) throw duplicate; return null; }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var ids = new ArrayList<Long>();
            for (var future : futures) { Long id = future.get(15, TimeUnit.SECONDS); if (id != null) ids.add(id); }
            return ids;
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void concurrentCreatesInAnEmptyProjectUseDistinctCodes() throws Exception {
        var ids = parallelCreates(0L, false);
        assertEquals(8, ids.size());
        assertEquals(8, new HashSet<>(ids.stream().map(this::code).toList()).size());
    }

    @Test void concurrentCreatesUnderLegacyParentUseDistinctChildren() throws Exception {
        Long parent = category(5, 0, "bin11cat");
        var ids = parallelCreates(parent, false);
        assertEquals(8, ids.size());
        var codes = ids.stream().map(this::code).toList();
        assertEquals(8, new HashSet<>(codes).size());
        assertTrue(codes.stream().allMatch(value -> value.startsWith("bin11catA")));
    }

    @Test void concurrentSameNameCreatesInsertExactlyOneCategory() throws Exception {
        assertEquals(1, parallelCreates(0L, true).size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ATT_TASK_CAT", Integer.class));
    }
}
