package tech.qiantong.qdata.module.att.cat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.spring.SpringUtils;
import tech.qiantong.qdata.module.att.controller.admin.cat.vo.AttCleanCatSaveReqVO;
import tech.qiantong.qdata.module.att.dal.mapper.cat.AttCleanCatMapper;
import tech.qiantong.qdata.module.att.dal.mapper.rule.AttCleanRuleMapper;
import tech.qiantong.qdata.module.att.service.cat.impl.AttCleanCatServiceImpl;
import tech.qiantong.qdata.module.att.service.rule.impl.AttCleanRuleServiceImpl;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Offline synthetic H2/MySQL-mode fixtures; actual MyBatis mapper SQL and service. */
class CleanCategoryCodeAllocationTest {
    private JdbcTemplate jdbc;
    private AttCleanCatServiceImpl service;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:clean-code-" + UUID.randomUUID() + ";MODE=MySQL");
        ds.setUser("sa");
        jdbc = new JdbcTemplate(ds);
        // Synthetic in-process contents disappear when the test JVM exits; no external DB or cleanup SQL.
        ds.setURL(ds.getURL() + ";DB_CLOSE_DELAY=-1");
        jdbc.execute("CREATE TABLE ATT_CLEAN_CAT (id BIGINT PRIMARY KEY, name VARCHAR(128), parent_id BIGINT, "
                + "sort_order INT, description VARCHAR(512), code VARCHAR(128), valid_flag BOOLEAN DEFAULT TRUE, "
                + "del_flag BOOLEAN DEFAULT FALSE, creator_id BIGINT, create_by VARCHAR(100), create_time TIMESTAMP, "
                + "updater_id BIGINT, update_by VARCHAR(100), update_time TIMESTAMP, remark VARCHAR(512))");
        jdbc.execute("CREATE TABLE ATT_CLEAN_RULE (id BIGINT PRIMARY KEY, cat_code VARCHAR(128), "
                + "valid_flag BOOLEAN DEFAULT TRUE, del_flag BOOLEAN DEFAULT FALSE)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AttCleanCatMapper.class);
        config.addMapper(AttCleanRuleMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        service = new AttCleanCatServiceImpl();
        var mapper = session.getMapper(AttCleanCatMapper.class);
        ReflectionTestUtils.setField(service, "attCleanCatMapper", mapper);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        var rules = new AttCleanRuleServiceImpl();
        ReflectionTestUtils.setField(rules, "attCleanRuleMapper", session.getMapper(AttCleanRuleMapper.class));
        ReflectionTestUtils.setField(service, "attCleanRuleService", rules);
    }

    private void category(long id, long parent, String code, boolean valid, boolean deleted) {
        jdbc.update("INSERT INTO ATT_CLEAN_CAT (id,parent_id,code,valid_flag,del_flag) VALUES (?,?,?,?,?)",
                id, parent, code, valid, deleted);
    }
    private void rule(long id, String code, boolean valid, boolean deleted) {
        jdbc.update("INSERT INTO ATT_CLEAN_RULE (id,cat_code,valid_flag,del_flag) VALUES (?,?,?,?)",
                id, code, valid, deleted);
    }
    private void seed40Rules() {
        int[] counts = {13,6,5,5,6,5}; long id = 0;
        for (int root = 1; root <= counts.length; root++)
            for (int n = 0; n < counts[root-1]; n++) rule(++id, "A0" + root + "A01", n % 2 == 0, false);
    }

    @Test void createAllocatesInsteadOfTrustingCallerCodeAndLeavesRulesUntouched() {
        seed40Rules();
        var before = jdbc.queryForList("SELECT * FROM ATT_CLEAN_RULE ORDER BY id");
        var request = new AttCleanCatSaveReqVO();
        request.setName("offline-only"); request.setParentId(0L); request.setCode("A01");
        Long id = service.createAttCleanCat(request);
        assertEquals("A07", jdbc.queryForObject("SELECT code FROM ATT_CLEAN_CAT WHERE id=?",String.class,id));
        assertEquals(before, jdbc.queryForList("SELECT * FROM ATT_CLEAN_RULE ORDER BY id"));
    }
    @Test void deletedParentCannotBeUsed() {
        category(1,0,"A01",true,true);
        assertThrows(ServiceException.class, () -> service.createCode(1L,null));
    }
    @Test void tooDeepParentFailsBeforeDatabaseInsert() {
        category(1,0,"A01".repeat(42),true,false);
        assertThrows(ServiceException.class, () -> service.createCode(1L,null));
    }
    @Test void lowerCaseHistoricalRulePrefixIsReserved() {
        rule(1,"a01a01",true,false);
        assertEquals("A02",service.createCode(0L,null));
    }
    @Test void emptyTablesStartAtA01() { assertEquals("A01", service.createCode(0L, null)); }
    @Test void emptyCatalogWith40RulesSkipsSixRoots() {
        seed40Rules(); assertEquals("A07", service.createCode(0L, null));
        assertEquals(40, jdbc.queryForObject("SELECT COUNT(*) FROM ATT_CLEAN_RULE", Integer.class));
    }
    @Test void twoConflictingExistingRootsStillSkipAllRuleBranches() {
        seed40Rules(); category(1,0,"A01",false,false); category(2,0,"A02",false,false);
        assertEquals("A07", service.createCode(0L, null));
    }
    @Test void childWithoutSiblingsSkipsRuleDescendantPrefixes() {
        category(1,0,"A01",true,false); rule(1,"A01A01A03",false,false);
        assertEquals("A01A02", service.createCode(1L, null));
    }
    @Test void orphanCategoryDescendantReservesItsMissingBranch() {
        category(1,99,"A01A01",true,false); assertEquals("A02", service.createCode(0L, null));
    }
    @Test void disabledAndDeletedHistoryRemainsReserved() {
        category(1,0,"A01",false,false); category(2,0,"A02",false,true);
        rule(1,"A03A01",false,false); rule(2,"A04A01",true,true);
        assertEquals("A05", service.createCode(0L, null));
    }
    @Test void unrelatedBranchesDoNotOccupyChildren() {
        category(1,0,"A01",true,false); rule(1,"A02A01",true,false);
        assertEquals("A01A01", service.createCode(1L, null));
    }
    @Test void malformedTailStillReservesMatchingPrefix() {
        rule(1,"A01bad",true,false); assertEquals("A02", service.createCode(0L, null));
    }
    @Test void gapsCanBeUsedWithoutReusingHistory() {
        category(1,0,"A01",true,false); category(2,0,"A03",true,false);
        assertEquals("A02", service.createCode(0L, null));
    }
    @Test void boundaryRollsA99ToB01() {
        for (int n=1; n<=99; n++) rule(n,String.format("A%02dA01",n),true,false);
        assertEquals("B01", service.createCode(0L, null));
    }
    @Test void exhaustedLevelFailsInsteadOfCreatingAnotherDepth() {
        int id=0;
        for (char c='A'; c<='Z'; c++) for (int n=1; n<=99; n++) rule(++id,String.format("%c%02dA01",c,n),true,false);
        assertThrows(ServiceException.class, () -> service.createCode(0L, null));
    }
    @Test void missingParentIsBusinessFailure() { assertThrows(ServiceException.class, () -> service.createCode(99L,null)); }
    @Test void nullParentIsBusinessFailure() { assertThrows(ServiceException.class, () -> service.createCode(null,null)); }
    @Test void invalidOrDisabledParentIsBusinessFailure() {
        category(1,0,"A00",true,false); category(2,0,"A02",false,false);
        assertThrows(ServiceException.class, () -> service.createCode(1L,null));
        assertThrows(ServiceException.class, () -> service.createCode(2L,null));
    }
    @Test void suppliedParentCodeCannotOverrideStoredPath() {
        category(1,0,"A01",true,false);
        assertThrows(ServiceException.class, () -> service.createCode(1L,"A02"));
    }
    @Test void referencedDisabledRulesStillProtectDeleteAndDisable() {
        var old = ReflectionTestUtils.getField(SpringUtils.class,"beanFactory");
        var messages = new ResourceBundleMessageSource(); messages.setBasename("i18n/att-messages");
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("messageSource",messages);
        new SpringUtils().postProcessBeanFactory(beans);
        try {
            category(1,0,"A01",true,false); rule(1,"A01A01",false,false);
            assertThrows(ServiceException.class, () -> service.removeAttCleanCat(1L));
            var req = new AttCleanCatSaveReqVO(); req.setId(1L); req.setValidFlag(false);
            assertThrows(ServiceException.class, () -> service.updateAttCleanCat(req));
            assertTrue(jdbc.queryForObject("SELECT valid_flag FROM ATT_CLEAN_CAT WHERE id=1",Boolean.class));
            assertFalse(jdbc.queryForObject("SELECT del_flag FROM ATT_CLEAN_CAT WHERE id=1",Boolean.class));
        } finally { ReflectionTestUtils.setField(SpringUtils.class,"beanFactory",old); }
    }
}
