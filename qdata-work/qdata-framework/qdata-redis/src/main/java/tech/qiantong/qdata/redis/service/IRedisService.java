/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.redis.service;

import java.util.List;
import java.util.Map;

public interface IRedisService {

    /**
     * Settings
     *
     * @param key
     * @param value
     */
    void set(String key, String value);

    /**
     * Settings, with timeout
     *
     * @param key
     * @param value
     * @param timeout
     */
    void set(String key, String value, long timeout);

    /** Acquire a lease atomically; an existing holder is never removed. */
    boolean tryAcquireLock(String key, String owner, long seconds);

    /** Renew only the lease held by this owner. */
    boolean renewLock(String key, String owner, long seconds);

    /** Release only the lease held by this owner. */
    boolean releaseLock(String key, String owner);
    /** Allocate a globally monotonic positive ID at or above the given floor. */
    long nextSequence(String key,long minimum);
    /** Count one request in a fixed window and expire the counter atomically. */
    boolean allowRequest(String key,long limit,long seconds);

    /** Atomically sets a non-negative integer value only when it is greater. */
    boolean setIfGreater(String key, String value);

    /** Atomically compares and stores a group of formatted time cursors. */
    boolean setDatesIfLater(Map<String, String> values, Map<String, Long> epochMillis,
                            Map<String, Long> legacyEpochMillis);

    String get(String key);

    boolean delete(String key);

    boolean hasKey(String key);

    void leftPush(String key, String value);

    void rightPush(String key, String value);

    void leftPushAll(String key, List<String> value);

    String rightPop(String key);

    String leftPop(String key);

    String rightRead(String key);

    List<String> range(String key, Integer start, Integer end);

    Long getListSize(String key);

    void hashPut(String key, String hashKey, String value);

    String hashGet(String key, String hashKey);

    Long hashIncrement(String key, String hashKey, long delta);

    Long hashDelete(String key, Object... hashKeys);

    Map<String, Object> hashGetAll(String key);

    List<Object> hashMultiGet(String key, List<String> hashKeys);

    /**
     * Determine whether the specified field exists in the Hash
     *
     * @param key Redis key
     * @param hashKey Hash field
     * @return does it exist
     */
    Boolean hashHasKey(String key, String hashKey);

    /**
     * Invalidate one datasource field and install a unique publish fence in one Redis command.
     * A pending fence is retained on SQL rollback so an older publisher cannot restore stale credentials.
     */
    void fenceAndEvictDatasource(String field, String fenceToken);

    /**
     * Publish the unchanged Worker payload only if the latest invalidation still owns the fence.
     * A null expected token is reserved for startup warmup and succeeds only
     * without a pending fence (an absent or committed fence is allowed).
     */
    boolean publishDatasourceIfCurrent(String field, String expectedFenceToken,
                                       String workerPayload, String shadowPayload);

    /** Evict a disabled row during startup only if no transaction is pending for this field. */
    boolean evictDatasourceIfUnfenced(String field);
}
