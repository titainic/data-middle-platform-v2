package tech.qiantong.qdata.acceptance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tech.qiantong.qdata.module.da.service.asset.impl.DaAssetServiceImpl;
import tech.qiantong.qdata.module.da.service.assetchild.files.IDaAssetFilesService;
import tech.qiantong.qdata.module.da.controller.admin.asset.vo.DaAssetRespVO;
import tech.qiantong.qdata.module.da.dal.dataobject.assetchild.files.DaAssetFilesDO;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FileAssetBoundaryTest {
    @TempDir Path root;Path upload;DaAssetServiceImpl service;DaAssetFilesDO file;
    @BeforeEach void setup()throws Exception{
        upload=Files.createDirectory(root.resolve("upload"));Files.writeString(upload.resolve("safe.csv"),"marker,value\nINSIDE,1\nSECOND,2\n");Files.writeString(root.resolve("outside.csv"),"marker,value\nOUTSIDE,7\n");
        service=spy(new DaAssetServiceImpl());ReflectionTestUtils.setField(service,"profile",upload.toString());
        var asset=new DaAssetRespVO();asset.setId(1L);asset.setType("6");doReturn(asset).when(service).getDaAssetById(1L);
        var files=mock(IDaAssetFilesService.class);file=new DaAssetFilesDO();file.setId(1L);file.setAssetId(1L);file.setUrl("/profile/safe.csv");file.setType(".csv");when(files.getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(file);ReflectionTestUtils.setField(service,"daAssetFilesService",files);
    }
    Map<String,Object> preview(){return service.getColumnData(new cn.hutool.json.JSONObject(Map.of("id",1,"pageNum",1,"pageSize",10)));}
    @Test void uploadedCsvIsReadable(){var result=preview();assertEquals(2L,((Number)result.get("total")).longValue());assertTrue(result.get("tableData").toString().contains("INSIDE"));}
    @Test void storedExtensionWithoutDotDoesNotBypassNormalPreview(){file.setType("csv");assertEquals(2L,((Number)preview().get("total")).longValue());}
    @Test void parentTraversalIsRejected(){file.setUrl("/profile/../outside.csv");assertThrows(IllegalArgumentException.class,this::preview);}
    @Test void absoluteServerPathIsRejected(){file.setUrl(root.resolve("outside.csv").toString());assertThrows(IllegalArgumentException.class,this::preview);}
    @Test void escapingSymlinkIsRejected()throws Exception{Files.createSymbolicLink(upload.resolve("escape.csv"),root.resolve("outside.csv"));file.setUrl("/profile/escape.csv");assertThrows(IllegalArgumentException.class,this::preview);}
    @Test void remoteUrlCannotTriggerNetworkFetch(){file.setUrl("http://127.0.0.1:1/private.csv");assertThrows(IllegalArgumentException.class,this::preview);}
    @Test void headerExtractionRejectsTraversalBeforeCreatingDerivedFiles(){ReflectionTestUtils.setField(service,"resourceUrl",root.resolve("derived").toString());assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(service,"getCsvColumn","/profile/../outside.csv",1L));assertFalse(Files.exists(root.resolve("derived")));}
    @Test void spreadsheetHeaderExtractionUsesTheSameBoundary(){assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(service,"getExcelColumn","/profile/../outside.xlsx",1,1,1L));}
}
