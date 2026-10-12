package tech.qiantong.qdata.ai.server.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tech.qiantong.qdata.common.core.domain.CommonResult;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "tech.qiantong.qdata.ai.controller")
public class AiAccessExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<CommonResult<Void>> accessDenied() {
        return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON)
                .body(CommonResult.error(403, "AI resource access denied"));
    }
}
