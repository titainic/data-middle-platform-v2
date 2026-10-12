package tech.qiantong.qdata.acceptance;

import java.net.URI;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tech.qiantong.qdata.common.config.AniviaConfig;
import tech.qiantong.qdata.common.file.UploadedFileAccess;
import tech.qiantong.qdata.config.interceptor.UploadedFileInterceptor;
import tech.qiantong.qdata.config.web.UploadedFileController;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UploadedFileHttpBoundaryTest {
 @TempDir Path root;String previous;MockMvc mvc;UploadedFileAccess access;
 @BeforeEach void setup()throws Exception {
  previous=AniviaConfig.getProfile();new AniviaConfig().setProfile(root.toString());access=mock(UploadedFileAccess.class);ObjectProvider<UploadedFileAccess> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(access);
  mvc=MockMvcBuilders.standaloneSetup(new UploadedFileController()).addInterceptors(new UploadedFileInterceptor(provider)).build();Files.writeString(root.resolve("own.csv"),"medium,value\nwater,7.1\n");Files.writeString(root.resolve("private.csv"),"private-fixture");
  doThrow(new AccessDeniedException("not owned")).when(access).read("/profile/private.csv");
 }
 @AfterEach void close(){new AniviaConfig().setProfile(previous);}
 @Test void readsAuthorizedFileWithPrivateCacheAndAttachment()throws Exception {mvc.perform(get("/profile/own.csv")).andExpect(status().isOk()).andExpect(content().string("medium,value\nwater,7.1\n")).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("Content-Disposition","attachment")).andExpect(header().string("X-Content-Type-Options","nosniff"));verify(access).read("/profile/own.csv");}
 @Test void rejectsBeforeReturningForeignBytes()throws Exception {var result=mvc.perform(get("/profile/private.csv")).andExpect(status().isForbidden()).andReturn();assertFalse(result.getResponse().getContentAsString().contains("private-fixture"));assertTrue(result.getResponse().getContentAsString().contains("403"));}
 @Test void missingFileReturns404InsteadOfEmpty200()throws Exception {mvc.perform(get("/profile/missing.csv")).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/json"));}
 @Test void pathIsNotDecodedAgainByAUrlResourceResolver()throws Exception {String name="folder%2f..%2fprivate.csv";Files.createDirectories(root.resolve("folder"));Files.writeString(root.resolve(name),"literal-owned-file");mvc.perform(get(URI.create("/profile/folder%252f..%252fprivate.csv"))).andExpect(status().isOk()).andExpect(content().string("literal-owned-file"));verify(access).read("/profile/"+name);}
 @Test void decodedTraversalNeverReturnsAFile()throws Exception {mvc.perform(get(URI.create("/profile/%2e%2e/private.csv"))).andExpect(status().is4xxClientError());verifyNoInteractions(access);}
 @Test void htmlCannotBecomeAnActiveInlinePage()throws Exception {Files.writeString(root.resolve("example.html"),"<script>document.title='fixture'</script>");mvc.perform(get("/profile/example.html")).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment")).andExpect(header().string("Content-Security-Policy","sandbox; default-src 'none'"));}
 @Test void mediaRangeRequestsRemainSupported()throws Exception {Files.writeString(root.resolve("clip.mp4"),"0123456");mvc.perform(get("/profile/clip.mp4").header("Range","bytes=0-2")).andExpect(status().isPartialContent()).andExpect(content().string("012")).andExpect(header().string("Content-Range","bytes 0-2/7"));}
 @Test void headChecksOwnershipWithoutReturningFileBody()throws Exception {mvc.perform(head("/profile/own.csv")).andExpect(status().isOk()).andExpect(content().string(""));verify(access).read("/profile/own.csv");}
 @Test void commonCsvAndExcelInspectionAuthorizeBeforeDerivedWrites()throws Exception {
  var controller=new tech.qiantong.qdata.module.system.controller.admin.common.CommonController();ObjectProvider<UploadedFileAccess> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(access);org.springframework.test.util.ReflectionTestUtils.setField(controller,"uploadedFiles",provider);tech.qiantong.qdata.common.file.DevelopmentFileAccess dev=mock(tech.qiantong.qdata.common.file.DevelopmentFileAccess.class);ObjectProvider<tech.qiantong.qdata.common.file.DevelopmentFileAccess> devProvider=mock(ObjectProvider.class);when(devProvider.getObject()).thenReturn(dev);org.springframework.test.util.ReflectionTestUtils.setField(controller,"developmentFiles",devProvider);doThrow(new AccessDeniedException("not owned")).when(dev).authorizeSource(null,"/profile/private.csv");
  var csv=new tech.qiantong.qdata.module.system.domain.vo.CsvColumnReqVO();csv.setFile("/profile/private.csv");csv.setTaskType("SPARK");assertThrows(AccessDeniedException.class,()->controller.getCsvColumn(csv));
  var excel=new tech.qiantong.qdata.module.system.domain.vo.ExcelColumnReqVO();excel.setExcelFile("/profile/private.csv");excel.setTaskType("SPARK");assertThrows(AccessDeniedException.class,()->controller.getExcelColumn(excel));assertFalse(Files.exists(root.resolve("etl")));
 }
}
