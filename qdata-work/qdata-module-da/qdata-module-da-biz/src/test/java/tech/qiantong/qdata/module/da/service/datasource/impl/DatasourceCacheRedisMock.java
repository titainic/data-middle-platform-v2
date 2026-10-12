package tech.qiantong.qdata.module.da.service.datasource.impl;

import tech.qiantong.qdata.redis.service.IRedisService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Synthetic Redis fence that also exposes the old hash calls to legacy assertions. */
final class DatasourceCacheRedisMock {
    private DatasourceCacheRedisMock() {
    }

    static IRedisService create() {
        IRedisService redis = mock(IRedisService.class);
        Map<String, String> fences = new ConcurrentHashMap<>();
        Object atomicCommand = new Object();
        doAnswer(call -> {
            synchronized (atomicCommand) {
                String field = call.getArgument(0);
                fences.put(field, "P:" + call.getArgument(1));
                redis.hashDelete("datasource", field);
                redis.hashDelete(DatasourceCacheMetadata.REDIS_HASH, field);
                return null;
            }
        }).when(redis).fenceAndEvictDatasource(anyString(), anyString());
        doAnswer(call -> {
            synchronized (atomicCommand) {
                String field = call.getArgument(0);
                String expected = call.getArgument(1);
                String actual = fences.get(field);
                if (expected == null && actual != null && !actual.startsWith("C:")) {
                    redis.hashDelete("datasource", field);
                    redis.hashDelete(DatasourceCacheMetadata.REDIS_HASH, field);
                    return false;
                }
                if (expected != null && !("P:" + expected).equals(actual)) {
                    return false;
                }
                redis.hashPut("datasource", field, call.getArgument(2));
                String shadow = call.getArgument(3);
                if (shadow != null) {
                    redis.hashPut(DatasourceCacheMetadata.REDIS_HASH, field, shadow);
                } else {
                    redis.hashDelete(DatasourceCacheMetadata.REDIS_HASH, field);
                }
                if (expected != null) {
                    fences.put(field, "C:" + expected);
                }
                return true;
            }
        }).when(redis).publishDatasourceIfCurrent(anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class));
        doAnswer(call -> {
            synchronized (atomicCommand) {
                String field = call.getArgument(0);
                String actual = fences.get(field);
                if (actual != null && !actual.startsWith("C:")) {
                    redis.hashDelete("datasource", field);
                    redis.hashDelete(DatasourceCacheMetadata.REDIS_HASH, field);
                    return false;
                }
                redis.hashDelete("datasource", field);
                redis.hashDelete(DatasourceCacheMetadata.REDIS_HASH, field);
                return true;
            }
        }).when(redis).evictDatasourceIfUnfenced(anyString());
        return redis;
    }
}
