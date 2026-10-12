package tech.qiantong.qdata.security.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/** HTML navigation may receive the public shell after denial; this never authorizes an API. */
@Component
public class SpaRequestMatcher implements RequestMatcher {

    @Override
    public boolean matches(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        try {
            String accept = request.getHeader("Accept");
            return accept != null && MediaType.parseMediaTypes(accept).stream().anyMatch(type ->
                    !type.isWildcardType() && !type.isWildcardSubtype() && type.getQualityValue() > 0
                            && type.isCompatibleWith(MediaType.TEXT_HTML));
        } catch (Exception unavailable) {
            return false;
        }
    }
}
