package tech.qiantong.qdata.common.utils.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SafeUploadPathTest {
    @TempDir Path directory;
    @Test void acceptsNormalUploadUrlsAndRelativePaths() throws Exception {
        Path root=Files.createDirectory(directory.resolve("uploads"));
        Path folder=Files.createDirectories(root.resolve("2026/09/10"));
        Path csv=Files.writeString(folder.resolve("sample.csv"),"device_id,value\na,1\n");
        assertEquals(csv.toRealPath(),SafeUploadPath.resolve(root,"/profile/2026/09/10/sample.csv"));
        assertEquals(csv.toRealPath(),SafeUploadPath.resolve(root,"2026/09/10/sample.csv"));
    }
    @Test void blocksTraversalAbsolutePathsAndNonFiles() throws Exception {
        Path root=Files.createDirectory(directory.resolve("uploads"));
        Path outside=Files.writeString(directory.resolve("outside.csv"),"private_sentinel\n");
        assertThrows(IllegalArgumentException.class,()->SafeUploadPath.resolve(root,"/profile/../outside.csv"));
        assertThrows(IllegalArgumentException.class,()->SafeUploadPath.resolve(root,outside.toString()));
        assertThrows(IllegalArgumentException.class,()->SafeUploadPath.resolve(root,"missing.csv"));
        assertThrows(IllegalArgumentException.class,()->SafeUploadPath.resolve(root,"."));
    }
    @Test void keepsMigratedRootSymlinksButRejectsLinksToOutsideFiles() throws Exception {
        Path root=Files.createDirectory(directory.resolve("uploads"));
        Path csv=Files.writeString(root.resolve("sample.csv"),"device_id\na\n");
        Path migrated=Files.createSymbolicLink(directory.resolve("profile"),root);
        assertEquals(csv.toRealPath(),SafeUploadPath.resolve(migrated,"sample.csv"));
        Path outside=Files.writeString(directory.resolve("outside.csv"),"private_sentinel\n");
        Files.createSymbolicLink(root.resolve("escaped.csv"),outside);
        assertThrows(IllegalArgumentException.class,()->SafeUploadPath.resolve(root,"escaped.csv"));
    }
}
