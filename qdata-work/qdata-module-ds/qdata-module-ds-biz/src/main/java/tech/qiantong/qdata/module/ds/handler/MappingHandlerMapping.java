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

package tech.qiantong.qdata.module.ds.handler;


import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tech.qiantong.qdata.common.enums.RequestMethodEnum;
import tech.qiantong.qdata.module.ds.dal.dataobject.api.DsApiDO;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MappingHandlerMapping {

    private static Map<String, DsApiDO> mappings = new ConcurrentHashMap<>();
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    private RequestHandler handler;
    private Method method;

    {
        try {
            method = RequestHandler.class.getDeclaredMethod("invoke", HttpServletRequest.class, HttpServletResponse.class, Map.class, Map.class, Map.class);
        } catch (NoSuchMethodException e) {
        }
    }

    private String ignore = "services";
    private String prefix = "v1.0.0";
    private String separator = "/";

    public MappingHandlerMapping() {}

    public void setRequestMappingHandlerMapping(RequestMappingHandlerMapping requestMappingHandlerMapping) {
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
    }

    public void setHandler(RequestHandler handler) {
        this.handler = handler;
    }

    public static DsApiDO getMappingApiInfo(HttpServletRequest request) {
        NativeWebRequest webRequest = new ServletWebRequest(request);
        String requestMapping = (String) webRequest.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return getMappingApiInfo(buildMappingKey(request.getMethod(), requestMapping));
    }

    public static DsApiDO getMappingApiInfo(String key) {
        return mappings.get(key);
    }

    public static String buildMappingKey(String requestMethod, String requestMapping) {
        String valByKey = null;
        //Handle numeric requestMethod values.
        if("1".equals(requestMethod)||"2".equals(requestMethod)){
            valByKey = RequestMethodEnum.getValByKey(requestMethod.toUpperCase());
        }else{
            valByKey = requestMethod.toLowerCase();
        }
        return valByKey + ":" + requestMapping;
    }

    /**
     * Registers request mappings.
     *
     * @param api
     */
    private static final Object registryLock = new Object();

    public void registerMapping(DsApiDO api) {
        synchronized (registryLock) {
            String key = getMappingKey(api);
            DsApiDO before = mappings.get(key);
            if (before != null && !java.util.Objects.equals(before.getId(), api.getId()))
                throw new IllegalArgumentException("服务路径已由其他接口使用，请选择新的路径或版本");
            for (var entry : mappings.entrySet())
                if (!entry.getKey().equals(key) && java.util.Objects.equals(entry.getValue().getId(), api.getId()))
                    throw new IllegalArgumentException("修改已发布服务的路径、方法或版本前，请先下线原服务");
            // Spring accepts an identical handler registration. Publish the definition only after success.
            requestMappingHandlerMapping.registerMapping(getRequestMapping(api), handler, method);
            mappings.put(key, api);
            trackTransaction(key, before, api, true);
        }
    }

    public void unregisterMapping(DsApiDO api) {
        synchronized (registryLock) {
            String key = getMappingKey(api);
            DsApiDO before = mappings.get(key);
            if (before == null) return;
            if (!java.util.Objects.equals(before.getId(), api.getId()))
                throw new IllegalArgumentException("服务路径属于其他接口，不能移除其路由");
            requestMappingHandlerMapping.unregisterMapping(getRequestMapping(before));
            // Reserve ownership until the database outcome is known, so rollback can restore the route.
            if (!trackTransaction(key, before, before, false)) mappings.remove(key, before);
        }
    }

    private static final Object transactionResource = new Object();
    private static class RouteChange {
        final DsApiDO before;
        DsApiDO expected;
        boolean registered;
        RouteChange(DsApiDO before) { this.before = before; }
    }

    @SuppressWarnings("unchecked")
    private boolean trackTransaction(String key, DsApiDO before, DsApiDO expected, boolean registered) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return false;
        var changes = (java.util.Map<String, RouteChange>) org.springframework.transaction.support.TransactionSynchronizationManager.getResource(transactionResource);
        if (changes == null) {
            changes = new java.util.LinkedHashMap<>();
            org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(transactionResource, changes);
            final var retained = changes;
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void suspend() {
                        org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(transactionResource);
                    }
                    @Override public void resume() {
                        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(transactionResource, retained);
                    }
                    @Override public void afterCompletion(int status) {
                        try {
                            synchronized (registryLock) {
                                for (var entry : retained.entrySet()) {
                                    var change = entry.getValue();
                                    if (mappings.get(entry.getKey()) != change.expected) continue;
                                    if (status == STATUS_COMMITTED) {
                                        if (!change.registered) mappings.remove(entry.getKey(), change.expected);
                                    } else if (change.before == null) {
                                        requestMappingHandlerMapping.unregisterMapping(getRequestMapping(change.expected));
                                        mappings.remove(entry.getKey(), change.expected);
                                    } else {
                                        requestMappingHandlerMapping.registerMapping(getRequestMapping(change.before), handler, method);
                                        mappings.put(entry.getKey(), change.before);
                                    }
                                }
                            }
                        } finally {
                            org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(transactionResource);
                        }
                    }
                });
        }
        var change = changes.computeIfAbsent(key, ignored -> new RouteChange(before));
        change.expected = expected;
        change.registered = registered;
        return true;
    }

    private String getMappingKey(DsApiDO api) {
        if (api == null || api.getId() == null || api.getReqMethod() == null || api.getApiUrl() == null || api.getApiUrl().isBlank())
            throw new IllegalArgumentException("服务缺少路由身份");
        return buildMappingKey(api.getReqMethod().toUpperCase(), getRequestPath(api.getApiVersion(), api.getApiUrl()));
    }

    private RequestMappingInfo getRequestMapping(DsApiDO api) {
        return RequestMappingInfo.paths(getRequestPath(api.getApiVersion(), api.getApiUrl())).methods(RequestMethod.valueOf(RequestMethodEnum.getValByKey(api.getReqMethod()).toUpperCase())).build();
    }

    /**
     * Calls the API /services/v1.0.0/user/1.
     * @param version
     * @param path
     * @return
     */
    private String getRequestPath(String version, String path) {
        String actualVersion = version == null ? prefix : version;
        return separator + ignore + separator + actualVersion + (path.startsWith(separator) ? path : (separator + path));
    }
}
