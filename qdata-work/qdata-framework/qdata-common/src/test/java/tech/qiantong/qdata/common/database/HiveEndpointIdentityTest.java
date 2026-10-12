package tech.qiantong.qdata.common.database;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.common.database.constants.DbQueryProperty;
import tech.qiantong.qdata.common.database.core.HiveEndpointIdentity;
import static org.junit.jupiter.api.Assertions.*;

class HiveEndpointIdentityTest {
    DbQueryProperty property(String host,long port,String config) {
        return new DbQueryProperty("Hive",host,port,config);
    }
    @Test void credentialsAndDefaultDatabaseDoNotCreateAnotherServerIdentity() {
        var a=property("HIVE.EXAMPLE",10000,"{\"dbname\":\"qdata_test\",\"username\":\"reader-a\",\"password\":\"\"}");
        var b=property("hive.example",10000,"{\"dbname\":\"ods_iot_db\",\"username\":\"reader-b\",\"password\":\"\"}");
        assertEquals(HiveEndpointIdentity.fingerprint(a),HiveEndpointIdentity.fingerprint(b));
    }
    @Test void endpointChangesAreDistinct() {
        var a=property("host-a",10000,"{}");
        assertNotEquals(HiveEndpointIdentity.fingerprint(a),HiveEndpointIdentity.fingerprint(property("host-b",10000,"{}")));
        assertNotEquals(HiveEndpointIdentity.fingerprint(a),HiveEndpointIdentity.fingerprint(property("host-a",10001,"{}")));
    }
    @Test void transportConfigurationIsOrderIndependentAndRemainsPartOfIdentity() {
        var a=property("host-a",10000,"{\"config\":{\"transportMode\":\"http\",\"httpPath\":\"cliservice\"}}");
        var b=property("host-a",10000,"{\"config\":{\"httpPath\":\"cliservice\",\"transportMode\":\"http\",\"unrelated\":true}}");
        assertEquals(HiveEndpointIdentity.fingerprint(a),HiveEndpointIdentity.fingerprint(b));
        assertNotEquals(HiveEndpointIdentity.fingerprint(a),HiveEndpointIdentity.fingerprint(property("host-a",10000,"{}")));
    }
    @Test void missingEndpointFailsClosed() {
        assertThrows(IllegalArgumentException.class,()->HiveEndpointIdentity.fingerprint(null));
        assertThrows(IllegalArgumentException.class,()->HiveEndpointIdentity.fingerprint(property(" ",10000,"{}")));
    }
    @Test void dnsAliasCanBeDeniedWithoutChangingTheStoredEndpointFingerprint() {
        var direct=property("127.0.0.1",10000,"{}");
        var alias=property("localhost",10000,"{}");
        assertNotEquals(HiveEndpointIdentity.fingerprint(direct),HiveEndpointIdentity.fingerprint(alias));
        assertTrue(HiveEndpointIdentity.sharesDirectEndpoint(direct,alias));
        assertFalse(HiveEndpointIdentity.sharesDirectEndpoint(direct,property("localhost",10001,"{}")));
        assertFalse(HiveEndpointIdentity.sharesDirectEndpoint(direct,property("localhost",10000,
                "{\"config\":{\"transportMode\":\"http\",\"httpPath\":\"cliservice\"}}")));
    }
    @Test void differentHiveAddressesSharingOneMetastoreAreOnePhysicalCatalog() {
        assertTrue(HiveEndpointIdentity.sharesMetastore(
                "thrift://metastore.example:9083", "thrift://metastore.example:9083"));
        assertTrue(HiveEndpointIdentity.sharesMetastore(
                "thrift://localhost:9083,thrift://other.example:9083", "thrift://127.0.0.1:9083"));
        assertFalse(HiveEndpointIdentity.sharesMetastore(
                "thrift://metastore-a.example:9083", "thrift://metastore-b.example:9083"));
        assertFalse(HiveEndpointIdentity.sharesMetastore(
                "thrift://metastore.example:9083", "thrift://metastore.example:9084"));
        assertThrows(IllegalArgumentException.class,
                () -> HiveEndpointIdentity.sharesMetastore("<undefined>", "thrift://metastore.example:9083"));
    }
}
