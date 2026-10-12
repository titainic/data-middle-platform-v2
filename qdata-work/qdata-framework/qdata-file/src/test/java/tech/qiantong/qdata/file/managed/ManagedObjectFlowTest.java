package tech.qiantong.qdata.file.managed;

import org.junit.jupiter.api.Test;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow.Purpose;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow.ReadMode;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow.Record;
import tech.qiantong.qdata.file.managed.ManagedObjectFlow.Status;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ManagedObjectFlowTest {
    private static final long OWNER = 101, READER = 102, FOREIGN = 103;
    private static final long PROJECT = 201, OTHER_PROJECT = 202, ASSET = 301;
    private static final byte[] DATA = "private data".getBytes(StandardCharsets.UTF_8);

    private final MemoryRepository repository = new MemoryRepository();
    private final MemoryStore store = new MemoryStore();
    private final Permissions permissions = new Permissions();
    private final ManagedObjectFlow flow = new ManagedObjectFlow(repository, store, permissions);

    @Test void opaqueServerKeyAndPrivateUnboundFile() throws Exception {
        UUID id = upload();
        Record record = repository.find(id).orElseThrow();
        assertEquals("managed/" + id, record.objectKey());
        assertEquals(Status.READY, record.status());
        permissions.export = true;
        assertArrayEquals(DATA, flow.read(id, OWNER, ReadMode.ORIGINAL));
        assertThrows(SecurityException.class, () -> flow.read(id, READER, ReadMode.PREVIEW));
        assertThrows(SecurityException.class, () -> flow.read(id, FOREIGN, ReadMode.PREVIEW));
        assertThrows(SecurityException.class, () -> flow.read(id, 0, ReadMode.PREVIEW));
    }

    @Test void bindingChecksActualAssetProjectAndUploader() throws Exception {
        UUID id = upload();
        permissions.assetProject = OTHER_PROJECT;
        assertThrows(SecurityException.class, () -> flow.bind(id, OWNER, ASSET));
        permissions.assetProject = PROJECT;
        assertThrows(SecurityException.class, () -> flow.bind(id, READER, ASSET));
        flow.bind(id, OWNER, ASSET);
        assertEquals(ASSET, repository.find(id).orElseThrow().assetId());
        assertThrows(SecurityException.class, () -> flow.read(id, READER, ReadMode.PREVIEW));
        assertEquals(0, store.openCalls, "preview must not open the original object");
        permissions.export = true;
        assertArrayEquals(DATA, flow.read(id, READER, ReadMode.ORIGINAL));
        assertThrows(SecurityException.class, () -> flow.read(id, FOREIGN, ReadMode.PREVIEW));
    }

    @Test void fileCannotBindToAnAssetOfAnotherPurpose() throws Exception {
        UUID id = upload();
        permissions.assetPurpose = Purpose.ASSET_GEO;
        assertThrows(SecurityException.class, () -> flow.bind(id, OWNER, ASSET));
        assertNull(repository.find(id).orElseThrow().assetId());
        permissions.assetPurpose = Purpose.ASSET_FILE;
        flow.bind(id, OWNER, ASSET);
        assertEquals(ASSET, repository.find(id).orElseThrow().assetId());
    }

    @Test void boundObjectBecomesUnreadableIfAssetTypeChanges() throws Exception {
        UUID id = upload();
        flow.bind(id, OWNER, ASSET);
        permissions.export = true;
        assertArrayEquals(DATA, flow.read(id, READER, ReadMode.ORIGINAL));
        int previouslyOpened = store.openCalls;
        permissions.assetPurpose = Purpose.ASSET_GEO;
        assertThrows(SecurityException.class, () -> flow.read(id, READER, ReadMode.ORIGINAL));
        assertEquals(previouslyOpened, store.openCalls);
    }

    @Test void unboundUploaderAlsoNeedsCurrentExportGrantForOriginal() throws Exception {
        UUID id = upload();
        assertThrows(SecurityException.class, () -> flow.read(id, OWNER, ReadMode.ORIGINAL));
        permissions.export = true;
        assertArrayEquals(DATA, flow.read(id, OWNER, ReadMode.ORIGINAL));
        permissions.owner = false;
        assertThrows(SecurityException.class, () -> flow.read(id, OWNER, ReadMode.ORIGINAL));
    }

    @Test void unboundReadPassesRecordedPurposeToCurrentAuthorization() throws Exception {
        UUID id = upload();
        permissions.export = true;
        Record original = repository.find(id).orElseThrow();
        repository.rows.put(id, new Record(id, original.projectId(), original.uploaderId(),
                Purpose.ASSET_GEO, original.objectKey(), original.mediaType(), original.length(),
                original.sha256(), original.status(), null));
        assertThrows(SecurityException.class, () -> flow.read(id, OWNER, ReadMode.ORIGINAL));
        assertEquals(0, store.openCalls);
    }

    @Test void currentMembershipAndOriginalExportAreRecheckedOnEveryRead() throws Exception {
        UUID id = upload();
        flow.bind(id, OWNER, ASSET);
        assertThrows(SecurityException.class, () -> flow.read(id, READER, ReadMode.ORIGINAL));
        permissions.export = true;
        assertArrayEquals(DATA, flow.read(id, READER, ReadMode.ORIGINAL));
        permissions.assetProject = null; // asset archived or project relation removed
        assertThrows(SecurityException.class, () -> flow.read(id, READER, ReadMode.ORIGINAL));
        permissions.assetProject = PROJECT;
        permissions.reader = false; // membership revoked after upload
        assertThrows(SecurityException.class, () -> flow.read(id, READER, ReadMode.ORIGINAL));
    }

    @Test void failedPutIsNeverReadyAndNoAutomaticDeleteOccurs() {
        store.failPut = true;
        assertThrows(IOException.class, this::upload);
        Record record = repository.only();
        assertEquals(Status.FAILED, record.status());
        assertThrows(SecurityException.class, () -> flow.read(record.id(), OWNER, ReadMode.PREVIEW));
    }

    @Test void failedFinalRegistrationKeepsPendingRecordForReconciliation() {
        repository.failReady = true;
        assertThrows(IllegalStateException.class, this::upload);
        Record record = repository.only();
        assertEquals(Status.PENDING, record.status());
        assertTrue(store.objects.containsKey(record.objectKey()));
        assertThrows(SecurityException.class, () -> flow.read(record.id(), OWNER, ReadMode.PREVIEW));
    }

    @Test void corruptObjectOrTamperedStorageKeyNeverReturnsBytes() throws Exception {
        UUID id = upload();
        permissions.export = true;
        Record original = repository.find(id).orElseThrow();
        store.objects.put(original.objectKey(), "private fake".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> flow.read(id, OWNER, ReadMode.ORIGINAL));
        repository.rows.put(id, new Record(id, PROJECT, OWNER, Purpose.ASSET_FILE,
                "other/unauthorized", original.mediaType(), original.length(),
                original.sha256(), Status.READY, null));
        assertThrows(SecurityException.class, () -> flow.read(id, OWNER, ReadMode.ORIGINAL));
    }

    @Test void oversizedRemoteStreamStopsAfterRegisteredLengthPlusOne() throws Exception {
        UUID id = upload();
        permissions.export = true;
        CountingInfiniteStream huge = new CountingInfiniteStream();
        store.override = huge;
        assertThrows(IOException.class, () -> flow.read(id, OWNER, ReadMode.ORIGINAL));
        assertEquals(DATA.length + 1, huge.bytesConsumed);
        assertTrue(huge.closed);
    }

    @Test void oversizedRecordedLengthIsRejectedBeforeOpeningStorage() throws Exception {
        UUID id = upload();
        permissions.export = true;
        Record old = repository.find(id).orElseThrow();
        repository.rows.put(id, new Record(id, old.projectId(), old.uploaderId(), old.purpose(),
                old.objectKey(), old.mediaType(), ManagedObjectFlow.MAX_BYTES + 1L,
                old.sha256(), old.status(), old.assetId()));
        assertThrows(IOException.class, () -> flow.read(id, OWNER, ReadMode.ORIGINAL));
        assertEquals(0, store.openCalls);
    }

    @Test void invalidIdentityTypeOrSizeCannotCreateMetadata() {
        assertThrows(SecurityException.class,
                () -> flow.upload(0, PROJECT, Purpose.ASSET_FILE, "text/csv", DATA));
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "text/html", DATA));
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "text/csv", new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "text/csv",
                        new byte[ManagedObjectFlow.MAX_BYTES + 1]));
        assertTrue(repository.rows.isEmpty());
        assertTrue(store.objects.isEmpty());
    }

    @Test void declaredMediaTypeMustMatchBasicBytesBeforeAnyRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "image/png", DATA));
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "image/jpeg", DATA));
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "application/pdf",
                        "%PDFx fake".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "text/csv",
                        "  <html>not csv</html>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "text/csv",
                        new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class,
                () -> flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "text/csv",
                        new byte[]{'a', 0, 'b'}));
        assertTrue(repository.rows.isEmpty());
        assertTrue(store.objects.isEmpty());
    }

    @Test void utf8CsvWithBomRemainsAvailable() throws Exception {
        UUID id = flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "text/csv",
                "\ufeffwater,air\r\n1,2".getBytes(StandardCharsets.UTF_8));
        assertEquals(Status.READY, repository.find(id).orElseThrow().status());
    }

    private UUID upload() throws IOException {
        return flow.upload(OWNER, PROJECT, Purpose.ASSET_FILE, "text/csv", DATA);
    }

    private static final class MemoryRepository implements ManagedObjectFlow.Repository {
        final Map<UUID, Record> rows = new HashMap<>();
        boolean failReady;
        @Override public void insert(Record pending) {
            if (rows.putIfAbsent(pending.id(), pending) != null) throw new IllegalStateException("duplicate id");
        }
        @Override public Optional<Record> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
        @Override public boolean transition(UUID id, Status expected, Status next) {
            if (failReady && next == Status.READY) return false;
            Record old = rows.get(id);
            if (old == null || old.status() != expected) return false;
            rows.put(id, new Record(old.id(), old.projectId(), old.uploaderId(), old.purpose(),
                    old.objectKey(), old.mediaType(), old.length(), old.sha256(), next, old.assetId()));
            return true;
        }
        @Override public boolean bind(UUID id, long userId, long assetId) {
            Record old = rows.get(id);
            if (old == null || old.status() != Status.READY || old.assetId() != null
                    || old.uploaderId() != userId) return false;
            rows.put(id, new Record(old.id(), old.projectId(), old.uploaderId(), old.purpose(),
                    old.objectKey(), old.mediaType(), old.length(), old.sha256(), old.status(), assetId));
            return true;
        }
        Record only() { assertEquals(1, rows.size()); return rows.values().iterator().next(); }
    }

    private static final class MemoryStore implements ManagedObjectFlow.Store {
        final Map<String, byte[]> objects = new HashMap<>();
        boolean failPut;
        int openCalls;
        InputStream override;
        @Override public void put(String key, byte[] bytes, String mediaType) throws IOException {
            if (failPut) throw new IOException("simulated store failure");
            objects.put(key, bytes.clone());
        }
        @Override public InputStream open(String key) {
            openCalls++;
            if (override != null) return override;
            byte[] bytes = objects.get(key);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }

    private static final class CountingInfiniteStream extends InputStream {
        int bytesConsumed;
        boolean closed;
        @Override public int read() { bytesConsumed++; return 'x'; }
        @Override public int read(byte[] target, int offset, int length) {
            Arrays.fill(target, offset, offset + length, (byte) 'x');
            bytesConsumed += length;
            return length;
        }
        @Override public void close() { closed = true; }
    }

    private static final class Permissions implements ManagedObjectFlow.Authorizer {
        Long assetProject = PROJECT;
        Purpose assetPurpose = Purpose.ASSET_FILE;
        Purpose ownPurpose = Purpose.ASSET_FILE;
        boolean owner = true, reader = true, export;
        @Override public boolean canUpload(long user, long project, Purpose purpose) {
            return user == OWNER && project == PROJECT && owner;
        }
        @Override public Long projectOfAsset(long asset, Purpose purpose) {
            return asset == ASSET && purpose == assetPurpose ? assetProject : null;
        }
        @Override public boolean canBind(long user, long project, long asset, Purpose purpose) {
            return user == OWNER && project == PROJECT && asset == ASSET
                    && purpose == assetPurpose && owner;
        }
        @Override public boolean canReadOwn(long user, long project, Purpose purpose, ReadMode mode) {
            return user == OWNER && project == PROJECT && purpose == ownPurpose && owner
                    && (mode == ReadMode.PREVIEW || export);
        }
        @Override public boolean canReadAsset(long user, long project, long asset,
                                               Purpose purpose, ReadMode mode) {
            if (project != PROJECT || asset != ASSET || purpose != assetPurpose
                    || !reader || user == FOREIGN) return false;
            return mode == ReadMode.PREVIEW || export;
        }
    }
}
