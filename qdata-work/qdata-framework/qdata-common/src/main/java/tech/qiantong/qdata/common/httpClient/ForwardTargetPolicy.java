package tech.qiantong.qdata.common.httpClient;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import tech.qiantong.qdata.common.exception.ServiceException;

/** API/GIS 转发专用策略；空配置拒绝全部目标，不影响其他业务客户端。 */
@Component
@ConfigurationProperties(prefix = "qdata.forwarding")
public class ForwardTargetPolicy {
    private static final Logger log = LoggerFactory.getLogger(ForwardTargetPolicy.class);
    private List<String> allowedHosts = List.of();
    private List<String> allowedCidrs = List.of();

    public List<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(List<String> hosts) {
        allowedHosts = hosts.stream().map(ForwardTargetPolicy::normalize).toList();
        if (allowedHosts.stream().anyMatch(h -> !h.matches("[a-z0-9][a-z0-9.-]*") || h.contains("..")))
            throw new IllegalArgumentException("转发域名白名单必须是精确域名，不支持通配符或URL");
    }
    public List<String> getAllowedCidrs() { return allowedCidrs; }
    public void setAllowedCidrs(List<String> cidrs) {
        cidrs.forEach(ForwardTargetPolicy::parseCidr);
        allowedCidrs = List.copyOf(cidrs);
    }

    void checkUri(URI uri) {
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535)
            throw denied("invalid-uri");
        String host = normalize(uri.getHost());
        if (InetAddresses.isInetAddress(host)) checkAddresses(host, new InetAddress[]{InetAddresses.forString(host)});
        else if (!allowedHosts.contains(host) && allowedCidrs.isEmpty()) throw denied(host);
    }

    // 在真正建立连接的 DNS resolver 内校验，避免“预检查解析一次、连接再解析一次”。
    InetAddress[] resolve(String host) throws UnknownHostException {
        String normalized = normalize(host);
        if (!allowedHosts.contains(normalized) && allowedCidrs.isEmpty()) throw denied(normalized);
        return checkAddresses(normalized, InetAddress.getAllByName(normalized));
    }

    InetAddress[] checkAddresses(String host, InetAddress[] addresses) {
        if (addresses.length == 0) throw denied(host);
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()
                    || !(allowedHosts.contains(normalize(host)) || allowedCidrs.stream().anyMatch(c -> contains(c, address))))
                throw denied(host);
        }
        return addresses;
    }

    private static boolean contains(String cidr, InetAddress address) {
        String[] parts = cidr.split("/", -1);
        byte[] network = InetAddresses.forString(parts[0]).getAddress(), actual = address.getAddress();
        if (network.length != actual.length) return false;
        int bits = Integer.parseInt(parts[1]);
        for (int i = 0; i < network.length && bits > 0; i++, bits -= 8) {
            int mask = 0xff << (8 - Math.min(bits, 8));
            if ((network[i] & mask) != (actual[i] & mask)) return false;
        }
        return true;
    }

    private static void parseCidr(String cidr) {
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("转发网段必须使用CIDR格式");
        int size = InetAddresses.forString(parts[0]).getAddress().length * 8;
        int bits = Integer.parseInt(parts[1]);
        if (bits < 0 || bits > size) throw new IllegalArgumentException("转发CIDR前缀长度无效");
    }

    private static String normalize(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private static ServiceException denied(String host) {
        // 只记主机，不记录URL路径、查询参数及凭据。
        log.warn("拒绝业务转发：目标未获白名单许可或地址格式无效，host={}", host);
        return new ServiceException("转发目标未获白名单许可或地址格式无效");
    }
}
