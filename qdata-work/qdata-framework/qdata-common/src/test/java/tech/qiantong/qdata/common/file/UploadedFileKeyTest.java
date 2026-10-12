package tech.qiantong.qdata.common.file;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UploadedFileKeyTest {
 @Test void canonicalRelativeAndProfileKeysMatch(){assertEquals("/profile/2026/file.csv",UploadedFileKey.of("2026/./file.csv"));assertEquals(UploadedFileKey.hash(UploadedFileKey.of("2026/file.csv")),UploadedFileKey.hash(UploadedFileKey.of("/profile/2026/file.csv")));}
 @Test void identityIsCaseSensitive(){assertNotEquals(UploadedFileKey.hash(UploadedFileKey.of("a.csv")),UploadedFileKey.hash(UploadedFileKey.of("A.csv")));}
 @Test void rejectsTraversalAbsolutePathsAndExternalUrls(){for(String value:new String[]{"../a.csv","/etc/passwd","/profile/../a.csv","a/../../b","http://example.com/profile/a.csv","/profile/a\\b.csv","/profile/a\0.csv","/profile/a.csv?x=1","/profile/a.csv#fragment","/profile/a.csv;v=1"})assertThrows(IllegalArgumentException.class,()->UploadedFileKey.of(value),value);}
 @Test void percentSignsAreNotDecodedTwice(){assertEquals("/profile/%2e%2e%2fdata.csv",UploadedFileKey.of("/profile/%2e%2e%2fdata.csv"));assertEquals("/profile/100%.csv",UploadedFileKey.of("100%.csv"));}
 @Test void rejectsEmptyDirectoryAndOversizedKey(){for(String value:new String[]{""," ","/profile/",".","a".repeat(1100)})assertThrows(IllegalArgumentException.class,()->UploadedFileKey.of(value));}
}
