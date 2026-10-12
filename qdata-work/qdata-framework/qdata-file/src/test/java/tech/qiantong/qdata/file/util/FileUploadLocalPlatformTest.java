package tech.qiantong.qdata.file.util;

import org.dromara.x.file.storage.core.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class FileUploadLocalPlatformTest {
    @AfterEach void resetStaticConfiguration() {
        FileUploadUtil.setOwnership(null, null);
        FileUploadUtil.init(null, null, null);
    }

    @Test void uploadRouteOnlyAcceptsTheConfiguredLocalPlatform() {
        var storage = mock(FileStorageService.class);
        var file = mock(MultipartFile.class);
        FileUploadUtil.init(storage, null, null);
        FileUploadUtil.setOwnership(() -> null, "local");

        assertThrows(IllegalArgumentException.class,
                () -> FileUploadUtil.uploadByParam(file, null, "local-rogue-oss"));
        assertThrows(IllegalArgumentException.class,
                () -> FileUploadUtil.uploadByParam(file, null, "aliyun-oss-1"));
        verifyNoInteractions(storage);

        when(storage.of(file)).thenThrow(new ReachedConfiguredStorage());
        assertThrows(ReachedConfiguredStorage.class,
                () -> FileUploadUtil.uploadByParam(file, null, "local"));
        verify(storage).of(file);
    }

    private static final class ReachedConfiguredStorage extends RuntimeException { }
}
