package tech.qiantong.qdata.module.system.controller.admin.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tech.qiantong.qdata.common.exception.ServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class HdfsUploadRegressionTest {
    @TempDir Path directory;

    @Test
    void uploadFailureIsReported() {
        CommonController controller = new CommonController();
        assertThrows(ServiceException.class, () -> controller.uploadHdfs("file:///", directory.toString(),
                directory.resolve("missing.csv").toString(), "output.csv"));
    }

    @Test
    @EnabledIfSystemProperty(named="qdata.hdfs.integration", matches="true")
    void hiveImportUploadsIntoConfiguredHaFilesystem() throws Exception {
        CommonController controller = new CommonController();
        String confDir = "/home/binend/soft/spark/hadoop-conf";
        var field = CommonController.class.getDeclaredField("hadoopConfDir");
        field.setAccessible(true); field.set(controller, confDir);
        String content = "id,name\n1,中文导入\n";
        Path input = directory.resolve("input.csv"); Files.writeString(input, content);
        String filename = "codex_upload_" + System.currentTimeMillis() + ".csv";
        controller.uploadHdfs("hdfs://mycluster", "/tmp/etl", input.toString(), filename);
        org.apache.hadoop.conf.Configuration config = new org.apache.hadoop.conf.Configuration();
        config.addResource(new org.apache.hadoop.fs.Path(confDir, "core-site.xml"));
        config.addResource(new org.apache.hadoop.fs.Path(confDir, "hdfs-site.xml"));
        config.setBoolean("dfs.client.use.datanode.hostname", true);
        try (var fs = org.apache.hadoop.fs.FileSystem.get(new URI("hdfs://mycluster"), config, "hadoop");
             var stream = fs.open(new org.apache.hadoop.fs.Path("/tmp/etl/" + filename))) {
            assertEquals(content, new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        System.out.println("HDFS_TEST_FILE=hdfs://mycluster/tmp/etl/" + filename);
    }
    @Test
    @EnabledIfSystemProperty(named="qdata.hdfs.integration", matches="true")
    void csvParsingStagesLocallyAndPublishesToHaHdfs() throws Exception {
        String oldProfile = tech.qiantong.qdata.common.config.AniviaConfig.getProfile();
        var configBean = new tech.qiantong.qdata.common.config.AniviaConfig();
        configBean.setProfile(directory.toString() + "/");
        try {
            CommonController controller = new CommonController();
            String confDir = "/home/binend/soft/spark/hadoop-conf";
            for (var setting : java.util.Map.of("hadoopConfDir", confDir, "hdfsUrl", "hdfs://mycluster").entrySet()) {
                var field = CommonController.class.getDeclaredField(setting.getKey());
                field.setAccessible(true); field.set(controller, setting.getValue());
            }
            String content = "id,name\n1,完整导入\n";
            Files.writeString(directory.resolve("input.csv"), content);
            var request = new tech.qiantong.qdata.module.system.domain.vo.CsvColumnReqVO();
            request.setFile("/profile/input.csv"); request.setTaskType("SPARK");
            var response = controller.getCsvColumn(request);
            assertEquals(200, response.get("code"));
            var data = (tech.qiantong.qdata.module.system.domain.vo.ColumnRespVO) response.get("data");
            assertEquals(java.util.List.of("id", "name"), data.getColumnList());
            assertTrue(data.getCsvFile().startsWith("hdfs://mycluster/tmp/etl/"));
            var configuration = new org.apache.hadoop.conf.Configuration();
            configuration.addResource(new org.apache.hadoop.fs.Path(confDir, "core-site.xml"));
            configuration.addResource(new org.apache.hadoop.fs.Path(confDir, "hdfs-site.xml"));
            configuration.setBoolean("dfs.client.use.datanode.hostname", true);
            try (var fs = org.apache.hadoop.fs.FileSystem.get(new URI("hdfs://mycluster"), configuration, "hadoop");
                 var input = fs.open(new org.apache.hadoop.fs.Path(data.getCsvFile()))) {
                assertEquals(content, new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            System.out.println("CSV_PARSE_HDFS_FILE=" + data.getCsvFile());
        } finally {
            configBean.setProfile(oldProfile);
        }
    }

}
