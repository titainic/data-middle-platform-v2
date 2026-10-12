/*
 * Copyright © 2025-present Jiangsu Qiantong Technology Co., Ltd.
 *
 * This file is part of qData Data Middle Platform (Open Source Edition).
 *
 * qData is licensed under Apache License 2.0 with additional qData terms.
 * You may use qData for commercial purposes, but you may not remove, hide,
 * modify, or replace the qData logo, copyright notices, license notices,
 * or attribution information without a separate commercial license.
 *
 * White-label use, OEM distribution, rebranding, or presenting qData as
 * another product requires separate commercial authorization from
 * Jiangsu Qiantong Technology Co., Ltd.
 *
 * Business License: https://community.qdata.tech/business/policy.html
 * See the LICENSE file in the project root for full license information.
 */

package tech.qiantong.qdata.common.database.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned, authenticated storage envelope for a datasource credential field.
 *
 * <p>Keys must come from server-side runtime configuration. Keep the active key and
 * any older decryption keys in the supplied map during a controlled rotation. The
 * datasource ID must be assigned before encryption and must never be changed for
 * the lifetime of the record. Legacy CBC values are deliberately not accepted;
 * their migration needs an explicit, audited path.</p>
 */
public final class DatasourceCredentialCodec {

    private static final String PREFIX = "qdata:ds:v1:";
    private static final String AAD_DOMAIN = "qdata.datasource.credential.v1";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int KEY_BYTES = 32;
    private static final int MAX_ENVELOPE_CHARS = 65_536;

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();

    /**
     * Copies all runtime-supplied AES-256 keys. The caller may clear its input
     * arrays after construction. No key is loaded from source code or the browser.
     */
    public DatasourceCredentialCodec(String activeKeyId, Map<String, byte[]> runtimeKeys) {
        this.activeKeyId = requireKeyId(activeKeyId);
        Objects.requireNonNull(runtimeKeys, "runtimeKeys");
        Map<String, SecretKeySpec> copied = new HashMap<>();
        runtimeKeys.forEach((id, bytes) -> {
            requireKeyId(id);
            if (bytes == null || bytes.length != KEY_BYTES) {
                throw new IllegalArgumentException("Datasource encryption key must be 256 bits");
            }
            byte[] keyCopy = bytes.clone();
            try {
                copied.put(id, new SecretKeySpec(keyCopy, "AES"));
            } finally {
                Arrays.fill(keyCopy, (byte) 0);
            }
        });
        if (!copied.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("Active datasource encryption key is unavailable");
        }
        this.keys = Map.copyOf(copied);
    }

    public String encrypt(long datasourceId, String field, String plaintext) {
        validateContext(datasourceId, field);
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] clear = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(activeKeyId, datasourceId, field));
            encrypted = cipher.doFinal(clear);
            return PREFIX + activeKeyId + ':'
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + ':'
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Datasource credential encryption failed");
        } finally {
            Arrays.fill(clear, (byte) 0);
            if (encrypted != null) {
                Arrays.fill(encrypted, (byte) 0);
            }
        }
    }

    /** Fails closed on unknown keys, modified content, wrong context, and legacy text. */
    public String decrypt(long datasourceId, String field, String envelope) {
        validateContext(datasourceId, field);
        if (envelope == null || envelope.length() > MAX_ENVELOPE_CHARS || !envelope.startsWith(PREFIX)) {
            throw invalidEnvelope();
        }
        String[] parts = envelope.split(":", -1);
        if (parts.length != 6 || !"qdata".equals(parts[0]) || !"ds".equals(parts[1])
                || !"v1".equals(parts[2]) || !KEY_ID.matcher(parts[3]).matches()) {
            throw invalidEnvelope();
        }
        SecretKeySpec key = keys.get(parts[3]);
        if (key == null) {
            throw new IllegalArgumentException("Unknown datasource credential key ID");
        }
        byte[] nonce = decodeCanonical(parts[4]);
        byte[] encrypted = decodeCanonical(parts[5]);
        if (nonce.length != NONCE_BYTES || encrypted.length < TAG_BYTES) {
            throw invalidEnvelope();
        }
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(parts[3], datasourceId, field));
            clear = cipher.doFinal(encrypted);
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(clear));
            return decoded.toString();
        } catch (GeneralSecurityException | CharacterCodingException exception) {
            throw invalidEnvelope();
        } finally {
            if (clear != null) {
                Arrays.fill(clear, (byte) 0);
            }
        }
    }

    private static byte[] aad(String keyId, long datasourceId, String field) {
        return (AAD_DOMAIN + '\0' + keyId + '\0' + datasourceId + '\0' + field)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodeCanonical(String encoded) {
        if (encoded.isEmpty()) {
            throw invalidEnvelope();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw invalidEnvelope();
            }
            return bytes;
        } catch (IllegalArgumentException exception) {
            throw invalidEnvelope();
        }
    }

    private static void validateContext(long datasourceId, String field) {
        if (datasourceId <= 0 || field == null || !FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("Invalid datasource credential context");
        }
    }

    private static String requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Invalid datasource credential key ID");
        }
        return keyId;
    }

    private static IllegalArgumentException invalidEnvelope() {
        return new IllegalArgumentException("Invalid datasource credential envelope");
    }
}
