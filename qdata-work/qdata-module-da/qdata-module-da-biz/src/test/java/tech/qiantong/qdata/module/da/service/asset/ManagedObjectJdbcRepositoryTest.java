package tech.qiantong.qdata.module.da.service.asset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagedObjectJdbcRepositoryTest {
    private static final UUID ID = UUID.fromString("7b2aa4e1-7f30-4264-802d-32f3a94357a8");
    private static final long ASSET = 301L;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RecordingTransactions transactions = new RecordingTransactions();
    private final ManagedObjectBindingWitness witness = mock(ManagedObjectBindingWitness.class);
    private final ManagedObjectJdbcRepository repository =
            new ManagedObjectJdbcRepository(jdbc, transactions, witness, "OSS", "private-test");

    @Test void pendingInsertHasFixedTargetAndIndependentCommit() {
        when(jdbc.update(eq(ManagedObjectJdbcRepository.INSERT_SQL), any(Object[].class)))
                .thenReturn(1);
        repository.insert(pending());
        verify(jdbc).update(ManagedObjectJdbcRepository.INSERT_SQL,
                ID.toString(), 144L, 927L, "ASSET_FILE", "OSS", "private-test",
                "managed/" + ID, "text/csv", 2L, "a".repeat(64));
        assertEquals(List.of(TransactionDefinition.PROPAGATION_REQUIRES_NEW),
                transactions.propagations);
        assertEquals(1, transactions.commits);
    }

    @Test void enclosingRollbackCannotRollBackPreviouslyCommittedPendingInsert() {
        when(jdbc.update(eq(ManagedObjectJdbcRepository.INSERT_SQL), any(Object[].class)))
                .thenReturn(1);
        new TransactionTemplate(transactions).execute(outer -> {
            repository.insert(pending());
            outer.setRollbackOnly();
            return null;
        });
        assertEquals(List.of(TransactionDefinition.PROPAGATION_REQUIRED,
                TransactionDefinition.PROPAGATION_REQUIRES_NEW), transactions.propagations);
        assertEquals(1, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    @Test void findMapsOnlyTheConfiguredTargetAndPendingCanOnlyTransitionOnce() {
        Map<String, Object> row = new HashMap<>();
        row.put("file_id", ID.toString());
        row.put("project_id", 144L);
        row.put("uploader_user_id", 927L);
        row.put("purpose", "ASSET_FILE");
        row.put("object_key", "managed/" + ID);
        row.put("media_type", "text/csv");
        row.put("byte_length", 2L);
        row.put("sha256", "a".repeat(64));
        row.put("state", "PENDING");
        row.put("bound_asset_id", null);
        when(jdbc.queryForList(ManagedObjectJdbcRepository.FIND_SQL,
                ID.toString(), "OSS", "private-test")).thenReturn(List.of(row));
        assertEquals(pending(), repository.find(ID).orElseThrow());
        when(jdbc.update(eq(ManagedObjectJdbcRepository.TRANSITION_SQL), any(Object[].class)))
                .thenReturn(1, 0);
        assertTrue(repository.transition(ID, ManagedObjectFlow.Status.PENDING,
                ManagedObjectFlow.Status.READY));
        assertFalse(repository.transition(ID, ManagedObjectFlow.Status.PENDING,
                ManagedObjectFlow.Status.READY));
        assertThrows(IllegalArgumentException.class,
                () -> repository.transition(ID, ManagedObjectFlow.Status.READY,
                        ManagedObjectFlow.Status.FAILED));
        assertEquals(2, transactions.commits);
    }

    @Test void bindUsesOneGuardedUpdateInsideRequiresNewTransaction() {
        when(jdbc.queryForList(ManagedObjectJdbcRepository.BIND_OBJECT_LOCK_SQL,
                ID.toString(), "OSS", "private-test")).thenReturn(List.of(lockedObject()));
        when(witness.authorized(927L, 144L, ASSET,
                ManagedObjectFlow.Purpose.ASSET_FILE)).thenReturn(true);
        when(jdbc.update(eq(ManagedObjectJdbcRepository.BIND_SQL), any(Object[].class)))
                .thenReturn(1, 0);
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(927L);
            assertTrue(repository.bind(ID, 927L, ASSET));
            assertFalse(repository.bind(ID, 927L, ASSET));
            assertFalse(repository.bind(ID, 927L, 0));
        }
        verify(jdbc, times(2)).update(ManagedObjectJdbcRepository.BIND_SQL,
                ASSET, ASSET, ID.toString(), "OSS", "private-test");
        assertEquals(List.of(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                TransactionDefinition.PROPAGATION_REQUIRES_NEW), transactions.propagations);
        assertTrue(ManagedObjectJdbcRepository.BIND_SQL.contains("o.state='READY' AND o.bound_asset_id IS NULL"));
        assertTrue(ManagedObjectJdbcRepository.BIND_SQL.contains("a.dataset_id IS NULL"));
        assertTrue(ManagedObjectJdbcRepository.BIND_SQL.contains("a.type='6'"));
        assertTrue(ManagedObjectJdbcRepository.BIND_SQL.contains("a.type='4'"));
        assertTrue(ManagedObjectJdbcRepository.BIND_SQL.contains("COUNT(DISTINCT r.project_id)=1"));
        assertTrue(ManagedObjectJdbcRepository.BIND_SQL.contains("MIN(r.project_id)=o.project_id"));
    }

    @Test void missingWitnessNeverReachesFinalUpdate() {
        when(jdbc.queryForList(ManagedObjectJdbcRepository.BIND_OBJECT_LOCK_SQL,
                ID.toString(), "OSS", "private-test")).thenReturn(List.of(lockedObject()));
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(927L);
            assertFalse(repository.bind(ID, 927L, ASSET));
        }
        verify(jdbc, never()).update(eq(ManagedObjectJdbcRepository.BIND_SQL), any(Object[].class));
    }

    @Test void lockConflictFailsClosedButMissingIndexIsVisibleAsSchemaError() {
        when(jdbc.queryForList(ManagedObjectJdbcRepository.BIND_OBJECT_LOCK_SQL,
                ID.toString(), "OSS", "private-test"))
                .thenThrow(new CannotAcquireLockException("synthetic contention"))
                .thenReturn(List.of(lockedObject()));
        when(witness.authorized(927L, 144L, ASSET,
                ManagedObjectFlow.Purpose.ASSET_FILE))
                .thenThrow(new BadSqlGrammarException("witness", "FORCE INDEX missing",
                        new java.sql.SQLException("synthetic missing index")));
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(927L);
            assertFalse(repository.bind(ID, 927L, ASSET));
            assertThrows(BadSqlGrammarException.class, () -> repository.bind(ID, 927L, ASSET));
        }
        assertEquals(2, transactions.rollbacks);
        verify(jdbc, never()).update(eq(ManagedObjectJdbcRepository.BIND_SQL), any(Object[].class));
    }

    @Test void unsafeMetadataAndMissingTargetFailBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class,
                () -> new ManagedObjectJdbcRepository(jdbc, transactions, witness, "", "private-test"));
        assertThrows(IllegalArgumentException.class,
                () -> new ManagedObjectJdbcRepository(jdbc, transactions, witness, "OSS", ""));
        var ready = new ManagedObjectFlow.Record(ID, 144, 927,
                ManagedObjectFlow.Purpose.ASSET_FILE, "managed/" + ID, "text/csv", 2,
                "a".repeat(64), ManagedObjectFlow.Status.READY, null);
        assertThrows(IllegalArgumentException.class, () -> repository.insert(ready));
        verifyNoInteractions(jdbc);
        assertEquals(0, transactions.commits);
    }

    @Test void defaultPropertyKeepsRepositoryBeanAbsent() {
        var condition = ManagedObjectJdbcRepository.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("qdata.managed-object", condition.prefix());
        assertArrayEquals(new String[]{"enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }

    @Test void springContextRequiresExplicitEnableAndFixedTargetConfiguration() {
        var context = new ApplicationContextRunner()
                .withUserConfiguration(ManagedObjectJdbcRepository.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(ManagedObjectBindingWitness.class, () -> witness)
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> transactions);
        context.run(beans -> assertEquals(0,
                beans.getBeansOfType(ManagedObjectJdbcRepository.class).size()));
        context.withPropertyValues("qdata.managed-object.enabled=true")
                .run(beans -> assertNotNull(beans.getStartupFailure()));
        context.withPropertyValues("qdata.managed-object.enabled=true",
                "qdata.managed-object.backend=OSS",
                "qdata.managed-object.target-alias=private-test")
                .run(beans -> assertEquals(1,
                        beans.getBeansOfType(ManagedObjectJdbcRepository.class).size()));
    }

    private static ManagedObjectFlow.Record pending() {
        return new ManagedObjectFlow.Record(ID, 144, 927,
                ManagedObjectFlow.Purpose.ASSET_FILE, "managed/" + ID,
                "text/csv", 2, "a".repeat(64), ManagedObjectFlow.Status.PENDING, null);
    }

    private static Map<String, Object> lockedObject() {
        Map<String, Object> locked = new HashMap<>();
        locked.put("project_id", 144L);
        locked.put("uploader_user_id", 927L);
        locked.put("purpose", "ASSET_FILE");
        locked.put("object_key", "managed/" + ID);
        locked.put("state", "READY");
        locked.put("bound_asset_id", null);
        return locked;
    }

    private static final class RecordingTransactions implements PlatformTransactionManager {
        final List<Integer> propagations = new ArrayList<>();
        int commits, rollbacks;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            propagations.add(definition.getPropagationBehavior());
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) {
            if (status.isRollbackOnly()) rollbacks++;
            else commits++;
        }
        @Override public void rollback(TransactionStatus status) { rollbacks++; }
    }
}
