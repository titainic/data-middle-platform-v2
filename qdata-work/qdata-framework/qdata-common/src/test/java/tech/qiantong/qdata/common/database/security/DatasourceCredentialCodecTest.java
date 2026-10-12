package tech.qiantong.qdata.common.database.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasourceCredentialCodecTest {

    private static final long DATASOURCE_ID = 9071L;
    private static final String CANARY = "synthetic-canary-水空气土壤-☁";

    @Test
    void roundTripsUnicodeCredentialWithoutExposingItInTheEnvelope() {
        byte[] suppliedKey = syntheticKey((byte) 17);
        DatasourceCredentialCodec codec = new DatasourceCredentialCodec("current-2026", Map.of("current-2026", suppliedKey));
        Arrays.fill(suppliedKey, (byte) 0);

        String envelope = codec.encrypt(DATASOURCE_ID, "password", CANARY);

        assertTrue(envelope.startsWith("qdata:ds:v1:current-2026:"));
        assertFalse(envelope.contains(CANARY));
        assertEquals(CANARY, codec.decrypt(DATASOURCE_ID, "password", envelope));
        assertEquals("", codec.decrypt(DATASOURCE_ID, "keySecret",
                codec.encrypt(DATASOURCE_ID, "keySecret", "")));
    }

    @Test
    void freshNinetySixBitNonceChangesCiphertextForIdenticalInput() {
        DatasourceCredentialCodec codec = codec("active", syntheticKey((byte) 23));

        String first = codec.encrypt(DATASOURCE_ID, "password", CANARY);
        String second = codec.encrypt(DATASOURCE_ID, "password", CANARY);

        assertNotEquals(first, second);
        assertEquals(12, Base64.getUrlDecoder().decode(first.split(":")[4]).length);
        assertEquals(CANARY, codec.decrypt(DATASOURCE_ID, "password", first));
        assertEquals(CANARY, codec.decrypt(DATASOURCE_ID, "password", second));
    }

    @Test
    void bindsKeyIdDatasourceIdentityAndFieldToTheAuthenticationTag() {
        byte[] sameMaterial = syntheticKey((byte) 31);
        DatasourceCredentialCodec codec = new DatasourceCredentialCodec("active",
                Map.of("active", sameMaterial, "alias", sameMaterial.clone()));
        String envelope = codec.encrypt(DATASOURCE_ID, "password", CANARY);

        assertThrows(IllegalArgumentException.class,
                () -> codec.decrypt(DATASOURCE_ID + 1, "password", envelope));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decrypt(DATASOURCE_ID, "keySecret", envelope));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decrypt(DATASOURCE_ID, "password", envelope.replace(":active:", ":alias:")));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decrypt(DATASOURCE_ID, "password", envelope.replace(":active:", ":unknown:")));

        String[] parts = envelope.split(":", -1);
        byte[] encrypted = Base64.getUrlDecoder().decode(parts[5]);
        encrypted[0] ^= 1;
        parts[5] = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        assertThrows(IllegalArgumentException.class,
                () -> codec.decrypt(DATASOURCE_ID, "password", String.join(":", parts)));
    }

    @Test
    void acceptsOldKeyOnlyForDecryptionDuringExplicitRotation() {
        byte[] oldKey = syntheticKey((byte) 7);
        byte[] newKey = syntheticKey((byte) 41);
        String oldEnvelope = codec("old", oldKey).encrypt(DATASOURCE_ID, "password", CANARY);
        DatasourceCredentialCodec rotated = new DatasourceCredentialCodec("new",
                Map.of("old", oldKey, "new", newKey));

        assertEquals(CANARY, rotated.decrypt(DATASOURCE_ID, "password", oldEnvelope));
        String newEnvelope = rotated.encrypt(DATASOURCE_ID, "password", CANARY);
        assertTrue(newEnvelope.startsWith("qdata:ds:v1:new:"));
        assertEquals(CANARY, rotated.decrypt(DATASOURCE_ID, "password", newEnvelope));
        assertThrows(IllegalArgumentException.class,
                () -> codec("new", newKey).decrypt(DATASOURCE_ID, "password", oldEnvelope));
    }

    @Test
    void rejectsLegacyTextAndMalformedEnvelopesWithoutPlaintextFallback() {
        DatasourceCredentialCodec codec = codec("active", syntheticKey((byte) 23));
        String valid = codec.encrypt(DATASOURCE_ID, "password", CANARY);
        String[] parts = valid.split(":", -1);
        String legacyBase64 = Base64.getEncoder().encodeToString(new byte[16]);
        String shortNonce = "qdata:ds:v1:active:AA:" + parts[5];
        String shortCiphertext = "qdata:ds:v1:active:" + parts[4] + ":AA";
        String paddedNonce = "qdata:ds:v1:active:" + parts[4] + "=:" + parts[5];

        for (String malformed : new String[] {CANARY, legacyBase64, "", "qdata:ds:v2:x:y:z",
                valid + ":extra", shortNonce, shortCiphertext, paddedNonce, null}) {
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decrypt(DATASOURCE_ID, "password", malformed));
        }
        assertThrows(IllegalArgumentException.class,
                () -> codec.encrypt(0, "password", CANARY));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encrypt(DATASOURCE_ID, "bad:field", CANARY));
        assertThrows(IllegalArgumentException.class,
                () -> new DatasourceCredentialCodec("bad:key", Map.of("bad:key", syntheticKey((byte) 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new DatasourceCredentialCodec("active", Map.of("active", new byte[16])));
    }

    private static DatasourceCredentialCodec codec(String keyId, byte[] key) {
        return new DatasourceCredentialCodec(keyId, Map.of(keyId, key));
    }

    private static byte[] syntheticKey(byte marker) {
        byte[] key = new byte[32];
        Arrays.fill(key, marker);
        return key;
    }
}
