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

package tech.qiantong.qdata.security.web.exception;

import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tech.qiantong.qdata.common.constant.HttpStatus;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.exception.DemoModeException;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.MessageUtils;
import tech.qiantong.qdata.common.utils.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Global exception handler
 * System exceptions and business exceptions uniformly return i18n messages (corresponding copy is returned according to the request language zh_CN / en_US / ja_JP)
 *
 * @author qdata
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Legacy business exceptions sometimes concatenate upstream errors. Never echo those details.
    private static final Pattern INTERNAL_DETAILS = Pattern.compile(
            "(?is)SQLException|SQL\\w*Exception|SQLState|jdbc:|mybatis|Mapper(?:\\b|\\.)|"
            + "(?:java|javax|jakarta|org\\.springframework|tech\\.qiantong)\\.|"
            + "\\b(?:insert\\s+into|select\\s+.+?\\s+from|update\\s+.+?\\s+set|delete\\s+from|"
            + "(?:alter|drop|create|truncate)\\s+table)\\b|"
            + "(?:doesn't have a default value|cannot be null|Caused by:|\\bat\\s+[\\w.$]+\\([^)]*\\.java:\\d+\\))");

    private String systemErrorMessage() {
        return MessageUtils.messageWithFallback("sys.error", "系统异常，请联系管理员");
    }

    private String businessMessage(Throwable failure) {
        String message = failure.getMessage();
        if (StringUtils.isBlank(message) || INTERNAL_DETAILS.matcher(message).find()) {
            return systemErrorMessage();
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException || cause instanceof DataAccessException) {
                return systemErrorMessage();
            }
        }
        return message;
    }

    @ExceptionHandler(tech.qiantong.qdata.common.exception.DependencyUnavailableException.class)
    public AjaxResult handleDependencyUnavailable(tech.qiantong.qdata.common.exception.DependencyUnavailableException failure,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setStatus(503);
        log.warn("Dependency unavailable", failure);
        return AjaxResult.error(503,businessMessage(failure));
    }

    @ExceptionHandler({RecoverableDataAccessException.class, DataAccessResourceFailureException.class})
    public AjaxResult handleDatabaseUnavailable(DataAccessException failure,
            HttpServletRequest request, HttpServletResponse response) {
        log.warn("Database dependency unavailable at {} ({})", request.getRequestURI(),
                failure.getClass().getSimpleName());
        response.setStatus(503);
        return AjaxResult.error(503, MessageUtils.messageWithFallback(
                "sys.error.database.unavailable", "数据库暂不可用，请稍后重试"));
    }

    /**
     * Permission verification exception
     */
    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("Access denied at {}", requestURI, e);
        String message = MessageUtils.messageWithFallback("sys.error", "Internal server error, please contact administrator");
        return AjaxResult.error(HttpStatus.FORBIDDEN, message);
    }

    /**
     * The request method is not supported
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("Unsupported request method at {}: {}", requestURI, e.getMethod());
        String message = MessageUtils.messageWithFallback("sys.error.method", "请求方法不支持");
        return AjaxResult.error(message);
    }

    /**
     * Business abnormality
     * Prioritize using the i18nCode set in ServiceException to obtain internationalized messages.
     * Retain readable business messages, excluding upstream database and implementation details.
     */
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request)
    {
        log.error(e.getMessage(), e);
        Integer code = e.getCode();
        String message = businessMessage(e);
        return StringUtils.isNotNull(code) ? AjaxResult.error(code, message) : AjaxResult.error(message);
    }

    /** Existing input/business guards use IllegalArgumentException as a readable rejection. */
    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request) {
        log.error("Request rejected at {}", request.getRequestURI(), e);
        return AjaxResult.error(businessMessage(e));
    }

    /**
     * A required path variable is missing from the request path
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public AjaxResult handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        String variableName = e.getVariableName();
        log.error("Missing path variable at {}", requestURI, e);
        String message = StringUtils.isNotEmpty(variableName) ? MessageUtils.messageWithFallback("sys.error.path.missing", "Missing required path variable: " + variableName)
                : MessageUtils.messageWithFallback("sys.error.path.missing", "Missing required path variable");
        return AjaxResult.error(message);
    }

    /**
     * Request parameter type mismatch
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public AjaxResult handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("Request parameter type mismatch at {}", requestURI, e);
        return AjaxResult.error("参数[" + e.getName() + "]类型不正确");
    }

    /**
     * Intercept unknown runtime exceptions
     */
    @ExceptionHandler(RuntimeException.class)
    public AjaxResult handleRuntimeException(RuntimeException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("Unexpected runtime failure at {}", requestURI, e);
        return AjaxResult.error(systemErrorMessage());
    }

    /**
     * System exception
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("Unexpected failure at {}", requestURI, e);
        return AjaxResult.error(systemErrorMessage());
    }

    /**
     * Custom validation exception
     */
    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e)
    {
        log.error(e.getMessage(), e);
        String message = e.getAllErrors().stream().map(error ->
                error instanceof org.springframework.validation.FieldError field && field.isBindingFailure()
                        ? "参数[" + field.getField() + "]类型不正确" : error.getDefaultMessage())
                .filter(StringUtils::isNotBlank).findFirst().orElse("请求参数校验失败");
        return AjaxResult.error(message);
    }

    /**
     * Custom validation exception
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e)
    {
        log.error(e.getMessage(), e);
        String message = e.getBindingResult().getAllErrors().stream().map(error -> error.getDefaultMessage())
                .filter(StringUtils::isNotBlank).findFirst().orElse("请求参数校验失败");
        return AjaxResult.error(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public AjaxResult handleConstraintViolationException(ConstraintViolationException e) {
        log.warn("Request parameter validation failed", e);
        String message = e.getConstraintViolations().stream().map(violation -> violation.getMessage())
                .filter(StringUtils::isNotBlank).findFirst().orElse("请求参数校验失败");
        return AjaxResult.error(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("Request body is invalid", e);
        return AjaxResult.error("请求体格式不正确，请检查字段类型");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public AjaxResult handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        return AjaxResult.error("参数[" + e.getParameterName() + "]不能为空");
    }

    /**
     * Demo mode exception
     */
    @ExceptionHandler(DemoModeException.class)
    public AjaxResult handleDemoModeException(DemoModeException e)
    {
        String message = MessageUtils.messageWithFallback("biz.error.demo", "Demo mode, operation not allowed");
        return AjaxResult.error(message);
    }
}
