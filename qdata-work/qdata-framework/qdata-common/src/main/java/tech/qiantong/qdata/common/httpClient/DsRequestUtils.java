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

package tech.qiantong.qdata.common.httpClient;

import lombok.extern.slf4j.Slf4j;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import cn.hutool.http.HttpException;
import cn.hutool.core.io.IORuntimeException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.qiantong.qdata.common.exception.ServiceException;

import java.net.URI;
import java.util.Map;

/**
 * <P>
 * Purpose: Scheduler request tool
 * </p>
 *
 * @author: FXB
 * @create: 2025-02-18 14:39
 **/
@Component
@Slf4j
public class DsRequestUtils {

    private static String baseUrl;//ds request interface prefix
    private static String token;//ds token

    @Value("${ds.token}")
    public void setToken(String token) {
        this.token = token;
    }

    @Value("${ds.base_url}")
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Validate before invoking the scheduler, including callers using a service proxy. */
    public static void validateConfiguration() {
        if (StringUtils.isBlank(baseUrl)) {
            throw new ServiceException("海豚调度器未配置，请联系管理员配置调度服务地址和访问令牌，或改用本地调度（Quartz）", 400);
        }
    }

    /**
     * Request method
     *
     * @param url interface path
     * @param method request method
     * @param body body parameter
     * @param params url splicing parameter map
     * @param resultClass result class
     * @return
     */
    public static <T> T request(String url, String method, Object body, Map<String, Object> params, Class<T> resultClass) {
        validateConfiguration();
        //Splicing url parameters
        if (params != null && !params.isEmpty()) {
            String paramsStr = HttpUtil.toParams(params);
            if (url.indexOf("?") > -1) {
                url = url + "&" + paramsStr;
            } else {
                url = url + "?" + paramsStr;
            }
        }

        try {
            URI endpoint = URI.create(baseUrl.trim());
            if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                    || StringUtils.isBlank(endpoint.getHost())) {
                throw new IllegalArgumentException("DS base URL must be an absolute HTTP(S) URL with a host");
            }
            HttpRequest request = HttpUtil.createRequest(Method.valueOf(method), baseUrl.trim() + url)
                    .header("token", token).timeout(10_000);
            if (body != null) {
                request.body(JSON.toJSONString(body));
            }
            try (HttpResponse response = request.execute()) {
                int status = response.getStatus();
                log.info("[DS-API] {} -> HTTP {}", method, status);
                if (status < 200 || status >= 300) {
                    throw requestFailure(new IllegalStateException("DS HTTP status " + status));
                }
                return JSON.parseObject(response.body(), resultClass);
            }
        } catch (IORuntimeException | HttpException | IllegalArgumentException | JSONException failure) {
            throw requestFailure(failure);
        }
    }

    private static ServiceException requestFailure(RuntimeException cause) {
        ServiceException failure = new ServiceException(
                "海豚调度服务请求失败，请联系管理员检查服务地址、网络和访问权限；涉及写入时请先核对调度端结果再重试", 400);
        failure.initCause(cause); // GlobalExceptionHandler logs the complete cause, without returning it to the client.
        return failure;
    }

    /**
     * Request method (form parameter passing)
     *
     * @param url interface path
     * @param method request method
     * @param params      map
     * @param resultClass result class
     * @return
     */
    public static <T> T requestForm(String url, String method, Map<String, Object> params, Class<T> resultClass) {
        //Encapsulate request object
        HttpRequest request = HttpUtil.createRequest(Method.valueOf(method), baseUrl + url)
                .header("token", token);
        if (params != null) {
            request.form(params);
        }
        //Get results
        HttpResponse response = request.execute();
        String respBody = String.valueOf(response.body());
        log.info("[DS-API] {} {} -> {} {}", method, url, response.getStatus(), respBody.substring(0, Math.min(300, respBody.length())));
        return JSON.parseObject(response.body(), resultClass);
    }

    /**
     * Replace project encoding
     *
     * @param url
     * @param projectCode
     * @return
     */
    public static String replaceProjectCode(String url, String projectCode) {
        return StringUtils.replace(url, "{projectCode}", projectCode);
    }

    /**
     * Replace project code and id
     *
     * @param url
     * @param projectCode
     * @param id
     * @return
     */
    public static String replaceProjectCodeAndId(String url, String projectCode, Long id) {
        return StringUtils.replace(StringUtils.replace(url, "{projectCode}", projectCode), "{id}", String.valueOf(id));
    }

    /**
     * Replace project encoding and code
     *
     * @param url
     * @param projectCode
     * @param code
     * @return
     */
    public static String replaceProjectCodeAndCode(String url, String projectCode, String code) {
        return StringUtils.replace(StringUtils.replace(url, "{projectCode}", projectCode), "{code}", String.valueOf(code));
    }
}
