package tech.qiantong.qdata.module.system.controller.admin.common;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.qiantong.qdata.common.exception.ServiceException;
import java.net.URI;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No Hadoop connection is opened; the entire filesystem is a mock. */
class CommonHdfsUploadSafetyTest {
    @TempDir java.nio.file.Path temporary;
    @Test void anExistingHdfsTargetIsNeverOpenedWithOverwriteEnabled() throws Exception {
        var file=Files.writeString(temporary.resolve("source.csv"),"device_id\nunit-device\n");
        var fs=mock(FileSystem.class);
        when(fs.create(any(Path.class),eq(false))).thenThrow(new FileAlreadyExistsException("Retained target"));
        try(var filesystem=mockStatic(FileSystem.class)) {
            filesystem.when(()->FileSystem.get(any(URI.class),any(Configuration.class),eq("hadoop"))).thenReturn(fs);
            assertThrows(ServiceException.class,()->new CommonController().uploadHdfs("hdfs://unit.invalid","/tmp/etl",file.toString(),"retained.csv"));
            verify(fs).create(new Path("/tmp/etl/retained.csv"),false);
            verify(fs,never()).create(any(Path.class),eq(true));
        }
    }
}
