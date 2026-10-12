package tech.qiantong.qdata.file.managed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fail-closed core for a future private object-storage endpoint. This class is deliberately not
 * registered as a controller or bean: the existing local upload routes must keep rejecting OSS.
 * Repository and storage adapters must be supplied only after a private target is approved.
 */
public final class ManagedObjectFlow {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final String PREFIX = "managed/";
    private static final Set<String> MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "application/pdf", "text/csv");

    public enum Purpose { ASSET_FILE, ASSET_GEO }
    public enum Status { PENDING, READY, FAILED }
    public enum ReadMode { PREVIEW, ORIGINAL }

    public record Record(UUID id, long projectId, long uploaderId, Purpose purpose,
                         String objectKey, String mediaType, long length, String sha256,
                         Status status, Long assetId) { }

    public interface Repository {
        void insert(Record pending);
        Optional<Record> find(UUID id);
        /** Compare-and-set the status; PENDING is never readable. */
        boolean transition(UUID id, Status expected, Status next);
        /** Atomically bind a READY, still-unbound record to one asset. */
        boolean bind(UUID id, long userId, long assetId);
    }

    public interface Store {
        void put(String serverKey, byte[] bytes, String mediaType) throws IOException;
        /** Return a streaming, timeout-bounded source; never materialize the whole remote object. */
        InputStream open(String serverKey) throws IOException;
    }

    public interface Authorizer {
        boolean canUpload(long userId, long projectId, Purpose purpose);
        /**
         * Resolve an active asset's one current project and verify its type matches purpose.
         * Return null for no project, multiple projects, archived assets or a type mismatch.
         */
        Long projectOfAsset(long assetId, Purpose purpose);
        boolean canBind(long userId, long projectId, long assetId, Purpose purpose);
        /** ORIGINAL must represent a current export grant, even for an unbound uploader. */
        boolean canReadOwn(long userId, long projectId, Purpose purpose, ReadMode mode);
        /** Must use current asset membership and capability, never a cached upload-time grant. */
        boolean canReadAsset(long userId, long projectId, long assetId,
                             Purpose purpose, ReadMode mode);
    }

    private final Repository repository;
    private final Store store;
    private final Authorizer authorizer;

    public ManagedObjectFlow(Repository repository, Store store, Authorizer authorizer) {
        this.repository = Objects.requireNonNull(repository);
        this.store = Objects.requireNonNull(store);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    /** Returns only an opaque identity; the storage key and any URL stay server-side. */
    public UUID upload(long userId, long projectId, Purpose purpose, String mediaType,
                       byte[] bytes) throws IOException {
        requireIdentity(userId, projectId);
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES || !MEDIA_TYPES.contains(mediaType)
                || !matchesDeclaredMediaType(mediaType, bytes)) {
            throw new IllegalArgumentException("文件大小或媒体类型不受支持");
        }
        if (!authorizer.canUpload(userId, projectId, purpose)) throw denied();

        UUID id = UUID.randomUUID();
        String key = PREFIX + id;
        Record pending = new Record(id, projectId, userId, purpose, key, mediaType,
                bytes.length, sha256(bytes), Status.PENDING, null);
        repository.insert(pending);
        try {
            store.put(key, bytes, mediaType);
        } catch (IOException | RuntimeException failure) {
            try {
                repository.transition(id, Status.PENDING, Status.FAILED);
            } catch (RuntimeException recordFailure) {
                failure.addSuppressed(recordFailure);
            }
            // Never delete an uncertain remote object automatically. FAILED/PENDING cannot be read.
            throw failure;
        }
        // If the final database update fails, leave PENDING for reconciliation; do not expose id.
        if (!repository.transition(id, Status.PENDING, Status.READY)) {
            throw new IllegalStateException("对象已上传但登记未完成，需核对待处理记录");
        }
        return id;
    }

    /** The asset project is resolved again on every bind; caller input cannot move an object. */
    public void bind(UUID id, long userId, long assetId) {
        Record record = ready(id);
        requireIdentity(userId, record.projectId());
        if (assetId <= 0 || record.assetId() != null || userId != record.uploaderId()
                || !Objects.equals(record.projectId(), authorizer.projectOfAsset(assetId, record.purpose()))
                || !authorizer.canBind(userId, record.projectId(), assetId, record.purpose())) throw denied();
        if (!repository.bind(id, userId, assetId)) throw denied();
    }

    /**
     * Returns original bytes only after a current export grant. A safe derived preview does not
     * exist yet, so PREVIEW must not expose these same bytes under a weaker query capability.
     */
    public byte[] read(UUID id, long userId, ReadMode mode) throws IOException {
        Record record = ready(id);
        requireIdentity(userId, record.projectId());
        Objects.requireNonNull(mode, "mode");
        if (mode == ReadMode.PREVIEW) {
            throw new SecurityException("受管对象安全预览尚未接入");
        }
        if (record.assetId() == null) {
            if (userId != record.uploaderId()
                    || !authorizer.canReadOwn(userId, record.projectId(),
                            record.purpose(), ReadMode.PREVIEW)
                    || !authorizer.canReadOwn(userId, record.projectId(),
                            record.purpose(), ReadMode.ORIGINAL)) {
                throw denied();
            }
        } else {
            long assetId = record.assetId();
            if (!Objects.equals(record.projectId(), authorizer.projectOfAsset(assetId, record.purpose()))
                    || !authorizer.canReadAsset(userId, record.projectId(), assetId,
                            record.purpose(), ReadMode.PREVIEW)
                    || !authorizer.canReadAsset(userId, record.projectId(), assetId,
                            record.purpose(), ReadMode.ORIGINAL)) {
                throw denied();
            }
        }
        if (record.length() <= 0 || record.length() > MAX_BYTES) {
            throw new IOException("对象登记长度超出允许范围");
        }
        byte[] bytes = new byte[(int) record.length()];
        try (InputStream source = store.open(record.objectKey())) {
            if (source == null) throw new IOException("对象读取流不可用");
            int offset = 0;
            while (offset < bytes.length) {
                int count = source.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("对象内容短于登记长度");
                if (count == 0) {
                    int next = source.read();
                    if (next < 0) throw new IOException("对象内容短于登记长度");
                    bytes[offset++] = (byte) next;
                } else {
                    offset += count;
                }
            }
            // At most MAX_BYTES + 1 bytes are consumed, even if the remote object is unbounded.
            if (source.read() != -1) throw new IOException("对象内容超过登记长度");
        }
        if (!sha256(bytes).equals(record.sha256())) {
            throw new IOException("对象内容与登记校验值不一致");
        }
        return bytes;
    }

    private Record ready(UUID id) {
        Objects.requireNonNull(id, "fileId");
        Record record = repository.find(id).orElseThrow(ManagedObjectFlow::denied);
        if (!id.equals(record.id()) || !Objects.equals(PREFIX + id, record.objectKey())
                || record.projectId() <= 0 || record.uploaderId() <= 0
                || record.purpose() == null
                || record.status() != Status.READY) throw denied();
        return record;
    }

    private static void requireIdentity(long userId, long projectId) {
        if (userId <= 0 || projectId <= 0) throw denied();
    }

    private static SecurityException denied() {
        return new SecurityException("文件不属于当前可访问项目，或未获得相应权限");
    }

    /** Basic fail-closed type check; a future HTTP route must still force attachment and nosniff. */
    private static boolean matchesDeclaredMediaType(String mediaType, byte[] bytes) {
        return switch (mediaType) {
            case "image/png" -> startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47,
                    0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> bytes.length >= 5
                    && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                    && (bytes[2] & 0xff) == 0xff
                    && (bytes[bytes.length - 2] & 0xff) == 0xff
                    && (bytes[bytes.length - 1] & 0xff) == 0xd9;
            case "application/pdf" -> startsWith(bytes, new byte[]{0x25, 0x50, 0x44, 0x46, 0x2d});
            case "text/csv" -> isPlainUtf8Csv(bytes);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) return false;
        }
        return true;
    }

    private static boolean isPlainUtf8Csv(byte[] bytes) {
        for (byte value : bytes) {
            int octet = value & 0xff;
            if (octet == 0 || octet < 0x20 && octet != '\t' && octet != '\n' && octet != '\r') {
                return false;
            }
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            int index = 0;
            if (!text.isEmpty() && text.charAt(0) == '\ufeff') index++;
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            return index < text.length() && text.charAt(index) != '<';
        } catch (CharacterCodingException malformed) {
            return false;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 不可用", unavailable);
        }
    }
}
