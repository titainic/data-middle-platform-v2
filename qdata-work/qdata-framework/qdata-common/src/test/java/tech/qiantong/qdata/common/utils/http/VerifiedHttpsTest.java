package tech.qiantong.qdata.common.utils.http;

import java.io.IOException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedHttpsTest {
    @Test void retainsJdkCertificateAndHostnameValidationWithoutOpeningSocket() throws Exception {
        var connection = HttpUtils.openVerifiedConnection(new URL("https://certificate-test.invalid/a"));
        assertSame(HttpsURLConnection.getDefaultSSLSocketFactory(), connection.getSSLSocketFactory());
        assertSame(HttpsURLConnection.getDefaultHostnameVerifier(), connection.getHostnameVerifier());
        assertFalse(connection.getInstanceFollowRedirects());
        assertEquals(5000, connection.getConnectTimeout());
        assertEquals(15000, connection.getReadTimeout());
        assertThrows(IOException.class, () -> HttpUtils.openVerifiedConnection(new URL("http://certificate-test.invalid/a")));
    }

    @Test void invalidProtocolIsReportedAsFailure() {
        assertThrows(IllegalStateException.class, () -> HttpUtils.sendSSLPost("http://certificate-test.invalid/a", "x=1"));
    }
}
