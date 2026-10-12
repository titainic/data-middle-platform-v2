package tech.qiantong.qdata.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class SpaRequestMatcherTest {
    @Test void onlyExplicitHtmlGetNavigationCanReceiveThePublicShell() {
        var matcher=new SpaRequestMatcher();var request=new MockHttpServletRequest("GET","/dm/model/warehouse");
        request.addHeader("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        assertTrue(matcher.matches(request));
        request.removeHeader("Accept");request.addHeader("Accept","application/json, */*");
        assertFalse(matcher.matches(request));
        request.removeHeader("Accept");request.addHeader("Accept","text/html;q=0");
        assertFalse(matcher.matches(request));
    }
    @Test void postAndMalformedRequestsDoNotBecomeHtmlNavigations() {
        var matcher=new SpaRequestMatcher();var request=new MockHttpServletRequest("POST","/mc/task");
        request.addHeader("Accept","text/html");assertFalse(matcher.matches(request));
        request.setMethod("GET");request.removeHeader("Accept");request.addHeader("Accept","not a media type");
        assertFalse(matcher.matches(request));
    }
}
