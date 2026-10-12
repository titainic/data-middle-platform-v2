package tech.qiantong.qdata.module.dpp.service.etl.task;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tech.qiantong.qdata.redis.service.impl.RedisServiceImpl;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only unique lease-test keys; never scans or modifies application keys. */
@EnabledIfEnvironmentVariable(named="QDATA_NAMESPACE_INTEGRATION", matches="true")
class RedisRunLeaseIntegrationTest {
    @Test void requestWindowHasAnAtomicLimitAndExpiry() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> calls=new ArrayList<>();for(int i=0;i<40;i++)calls.add(pool.submit(()->leases.allowRequest(key,10,2)));
            int allowed=0;for(var call:calls)if(call.get(10,TimeUnit.SECONDS))allowed++;
            assertEquals(10,allowed);assertTrue(template.getExpire(key)>=0);
            Thread.sleep(2100);assertTrue(leases.allowRequest(key,10,2));
        }finally{pool.shutdownNow();}
    }
    @Test void executionLeaseRenewsPastItsOriginalExpiry() throws Exception {
        try(var lease=tech.qiantong.qdata.redis.service.OwnedRedisLease.acquire(leases,key,3)) {
            Thread.sleep(4200);
            lease.requireOwner();assertTrue(Boolean.TRUE.equals(template.hasKey(key)));
        }
        assertFalse(Boolean.TRUE.equals(template.hasKey(key)));
    }
    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private RedisServiceImpl leases;
    private String key;

    @BeforeEach void setup() throws Exception {
        var config = new RedisStandaloneConfiguration("127.0.0.1", 6380);
        config.setDatabase(Integer.parseInt(System.getenv().getOrDefault("QDATA_NAMESPACE_REDIS_DB", "0")));
        config.setPassword(RedisPassword.of(System.getenv("QDATA_NAMESPACE_REDIS_PASSWORD")));
        factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet(); factory.start();
        template = new StringRedisTemplate(factory); template.afterPropertiesSet();
        leases = new RedisServiceImpl();
        var field = RedisServiceImpl.class.getDeclaredField("stringRedisTemplate");
        field.setAccessible(true); field.set(leases, template);
        key = "qdata:namespace-lease-test:" + UUID.randomUUID();
    }

    @AfterEach void close() {
        if (template != null && key != null) {
            String owner = template.opsForValue().get(key);
            if (owner != null) leases.releaseLock(key, owner);
        }
        if (factory != null) factory.destroy();
    }

    @Test void onlyTheOwnerCanRenewAndRelease() {
        assertTrue(leases.tryAcquireLock(key, "owner-a", 10));
        assertFalse(leases.tryAcquireLock(key, "owner-b", 10));
        assertFalse(leases.renewLock(key, "owner-b", 30));
        assertFalse(leases.releaseLock(key, "owner-b"));
        assertEquals("owner-a", template.opsForValue().get(key));
        assertTrue(leases.renewLock(key, "owner-a", 30));
        assertTrue(template.getExpire(key) > 0);
        assertTrue(leases.releaseLock(key, "owner-a"));
        assertFalse(Boolean.TRUE.equals(template.hasKey(key)));
    }

    @Test void anExpiredHolderCannotReleaseAReplacementLease() throws Exception {
        assertTrue(leases.tryAcquireLock(key, "expired-owner", 1));
        Thread.sleep(1200);
        assertTrue(leases.tryAcquireLock(key, "new-owner", 10));
        assertFalse(leases.releaseLock(key, "expired-owner"));
        assertFalse(leases.renewLock(key, "expired-owner", 10));
        assertEquals("new-owner", template.opsForValue().get(key));
    }

    @Test void concurrentAcquisitionHasOneWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> { start.await(); return leases.tryAcquireLock(key, "a", 10); });
            Future<Boolean> second = pool.submit(() -> { start.await(); return leases.tryAcquireLock(key, "b", 10); });
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test void sequenceAllocatesDistinctCodesAtTheSameTimestampAcrossConcurrentCallers() throws Exception {
        long floor=1_788_000_000_000_000L;var pool=Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<Long>> pending=new java.util.ArrayList<>();
            for(int i=0;i<256;i++)pending.add(pool.submit(()->leases.nextSequence(key,floor)));
            java.util.Set<Long> codes=new java.util.HashSet<>();for(var future:pending)codes.add(future.get(20,TimeUnit.SECONDS));
            assertEquals(256,codes.size());assertEquals(floor,java.util.Collections.min(codes));assertEquals(floor+255,java.util.Collections.max(codes));
        } finally {pool.shutdownNow();}
    }
    @Test void sequencePreservesLongPrecisionAndDoesNotRegressWhenTheClockMovesBack() {
        long floor=9_007_199_254_741_009L;
        assertEquals(floor,leases.nextSequence(key,floor));assertEquals(floor+1,leases.nextSequence(key,floor));assertEquals(floor+2,leases.nextSequence(key,1));
    }
}
