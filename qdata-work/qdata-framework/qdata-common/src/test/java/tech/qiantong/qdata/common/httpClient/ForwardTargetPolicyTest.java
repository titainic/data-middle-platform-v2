package tech.qiantong.qdata.common.httpClient;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.qiantong.qdata.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;

class ForwardTargetPolicyTest {
    @Test void emptyConfigurationDeniesHostAndLiteralBeforeConnection() throws Exception {
        var policy = new ForwardTargetPolicy();
        assertThrows(ServiceException.class, () -> policy.checkUri(URI.create("https://unregistered.invalid/a")));
        assertThrows(ServiceException.class, () -> policy.checkUri(URI.create("http://127.0.0.1/a")));
        try (var forwarder = new BoundedHttpForwarder(100,100,100,1024,new DependencyGuard(),()->policy)) {
            var response = new MockHttpServletResponse();
            assertThrows(ServiceException.class, () -> forwarder.forward("GET", "http://127.0.0.1/a", Map.of(), List.of(), response));
            assertFalse(response.isCommitted());
            assertEquals(0, response.getContentAsByteArray().length);
        }
    }

    @Test void exactHostAllowsOnlyItselfAndRejectsSpecialDestinations() throws Exception {
        var policy = new ForwardTargetPolicy(); policy.setAllowedHosts(List.of("API.INTERNAL."));
        policy.checkUri(URI.create("https://api.internal/path"));
        policy.checkAddresses("api.internal", addresses("10.2.3.4", "2001:db8::1"));
        assertThrows(ServiceException.class, () -> policy.checkUri(URI.create("https://api.internal.attacker.invalid")));
        for (String ip : List.of("169.254.169.254", "0.0.0.0", "224.0.0.1", "fe80::1", "::"))
            assertThrows(ServiceException.class, () -> policy.checkAddresses("api.internal", addresses(ip)));
    }

    @Test void cidrChecksAllDnsAnswersAndIpv6Boundaries() throws Exception {
        var policy = new ForwardTargetPolicy(); policy.setAllowedCidrs(List.of("10.2.0.0/16", "2001:db8::/32"));
        policy.checkAddresses("service.internal", addresses("10.2.255.255", "2001:db8:1::2"));
        policy.checkUri(URI.create("http://10.2.0.1/a"));
        policy.checkUri(URI.create("https://[2001:db8::1]/a"));
        assertThrows(ServiceException.class, () -> policy.checkAddresses("service.internal", addresses("10.2.1.1", "192.0.2.1")));
        assertThrows(ServiceException.class, () -> policy.checkUri(URI.create("http://10.3.0.1/a")));
        assertThrows(ServiceException.class, () -> policy.checkUri(URI.create("https://[2001:db9::1]/a")));
    }

    @Test void rejectsUnsupportedSchemesCredentialsAndInvalidConfiguration() {
        var policy = new ForwardTargetPolicy(); policy.setAllowedHosts(List.of("api.internal"));
        for (String uri : List.of("file:///etc/hosts", "ftp://api.internal/a", "http://user@api.internal/a", "http://api.internal/a#x", "http://api.internal:65536/a"))
            assertThrows(ServiceException.class, () -> policy.checkUri(URI.create(uri)));
        assertThrows(IllegalArgumentException.class, () -> policy.setAllowedHosts(List.of("*.internal")));
        assertThrows(IllegalArgumentException.class, () -> policy.setAllowedCidrs(List.of("10.0.0.0/33")));
    }

    @Test void configurationBindsFromDocumentedProperties() {
        var source = new MapConfigurationPropertySource(Map.of(
                "qdata.forwarding.allowed-hosts[0]", "API.INTERNAL", "qdata.forwarding.allowed-cidrs[0]", "10.2.0.0/16"));
        var policy = new Binder(source).bind("qdata.forwarding", ForwardTargetPolicy.class).get();
        assertEquals(List.of("api.internal"), policy.getAllowedHosts());
        assertEquals(List.of("10.2.0.0/16"), policy.getAllowedCidrs());
    }

    private static InetAddress[] addresses(String... values) throws Exception {
        InetAddress[] result = new InetAddress[values.length];
        for (int i=0;i<values.length;i++) result[i]=InetAddress.getByName(values[i]);
        return result;
    }
}
