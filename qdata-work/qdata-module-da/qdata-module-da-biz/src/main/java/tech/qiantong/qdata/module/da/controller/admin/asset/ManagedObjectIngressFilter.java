package tech.qiantong.qdata.module.da.controller.admin.asset;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow;

/** Reject multipart before DispatcherServlet can invoke the global 3000 MB multipart resolver. */
@Component
// Authentication runs first; this filter still precedes DispatcherServlet multipart resolution.
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
@ConditionalOnProperty(prefix = "qdata.managed-object", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class ManagedObjectIngressFilter extends OncePerRequestFilter {
    private static final String ROOT = "/da/managed-objects";

    @Override protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!managedPath(path) && !managedPath(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        String type = request.getContentType();
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            response.setStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
            return;
        }
        if ("HEAD".equals(request.getMethod())) {
            response.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
            return;
        }
        if ("POST".equals(request.getMethod()) && path.equals(ROOT)) {
            String encoding = request.getHeader("Content-Encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                response.setStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
                return;
            }
            if (request.getContentLengthLong() > ManagedObjectFlow.MAX_BYTES) {
                response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
                return;
            }
        } else if (request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null
                || type != null) {
            // Bind carries only a query assetId. No servlet body parser is needed on other routes.
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean managedPath(String path) {
        if (path == null) return false;
        // Match conservatively before MVC can normalize encoded slashes, letters, or matrix params.
        if (path.contains("/da") && path.contains("managed-objects")) return true;
        try {
            String decoded = UriUtils.decode(path, StandardCharsets.UTF_8);
            return decoded.contains("/da") && decoded.contains("managed-objects");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
