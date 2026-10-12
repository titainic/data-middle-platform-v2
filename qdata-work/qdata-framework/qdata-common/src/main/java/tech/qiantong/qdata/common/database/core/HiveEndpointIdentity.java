package tech.qiantong.qdata.common.database.core;

import com.alibaba.fastjson2.JSON;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;

/** Stable registered endpoint identity. Credentials and default database do not identify a server. */
public final class HiveEndpointIdentity {
    private HiveEndpointIdentity() {}
    public static String fingerprint(DbQueryProperty property) {
        if(property==null || property.getDbType()==null || property.getHost()==null || property.getHost().isBlank())throw new IllegalArgumentException("Hive连接地址不完整");
        Map<String,Object> identity=new TreeMap<>();
        identity.put("type",property.getDbType().toLowerCase(Locale.ROOT));
        identity.put("host",property.getHost().toLowerCase(Locale.ROOT));
        identity.put("port",property.getPort());
        if(property.getConfig()!=null)for(String key:List.of("serviceDiscoveryMode","zooKeeperNamespace","transportMode","httpPath"))
            if(property.getConfig().containsKey(key))identity.put(key,property.getConfig().get(key));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(identity).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("无法生成Hive连接标识",failure);}
    }
    /** Deny duplicate direct endpoints through DNS aliases without changing stored fingerprints or grants. */
    public static boolean sharesDirectEndpoint(DbQueryProperty first,DbQueryProperty second) {
        if(first==null || second==null || !"hive".equalsIgnoreCase(first.getDbType())
                || !"hive".equalsIgnoreCase(second.getDbType()))return false;
        Set<String> addresses=resolvedAddresses(first.getHost());
        addresses.retainAll(resolvedAddresses(second.getHost()));
        for(String address:addresses) {
            var a=first.copy();a.setHost(address);
            var b=second.copy();b.setHost(address);
            if(fingerprint(a).equals(fingerprint(b)))return true;
        }
        return false;
    }
    /** A shared metastore identifies one physical table catalog even behind distinct HS2/NAT addresses. */
    public static boolean sharesMetastore(String first, String second) {
        Set<String> endpoints = metastoreEndpoints(first);
        endpoints.retainAll(metastoreEndpoints(second));
        return !endpoints.isEmpty();
    }
    private static Set<String> metastoreEndpoints(String setting) {
        if (setting == null || setting.isBlank()) throw new IllegalArgumentException("Hive元数据服务身份不可用");
        Set<String> endpoints = new HashSet<>();
        for (String part : setting.split(",")) {
            URI uri;
            try { uri = URI.create(part.trim()); }
            catch (IllegalArgumentException malformed) { throw new IllegalArgumentException("Hive元数据服务地址无效", malformed); }
            if (!"thrift".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() <= 0)
                throw new IllegalArgumentException("Hive元数据服务地址无效");
            endpoints.add(uri.getHost().toLowerCase(Locale.ROOT) + ":" + uri.getPort());
            for (String address : resolvedAddresses(uri.getHost()))
                endpoints.add(address.toLowerCase(Locale.ROOT) + ":" + uri.getPort());
        }
        if (endpoints.isEmpty()) throw new IllegalArgumentException("Hive元数据服务身份不可用");
        return endpoints;
    }
    private static Set<String> resolvedAddresses(String host) {
        if(host==null || host.isBlank())return new HashSet<>();
        try {
            Set<String> result=new HashSet<>();
            for(InetAddress address:InetAddress.getAllByName(host.trim()))result.add(address.getHostAddress());
            return result;
        } catch(UnknownHostException unresolved) {
            return new HashSet<>();
        }
    }
}
