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

package tech.qiantong.qdata.module.system.controller.admin.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tech.qiantong.qdata.common.config.AniviaConfig;
import tech.qiantong.qdata.common.constant.Constants;
import tech.qiantong.qdata.common.core.domain.AjaxResult;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.ExcelToCsvUtil;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.common.utils.file.FileUploadUtils;
import tech.qiantong.qdata.common.utils.file.FileUtils;
import tech.qiantong.qdata.config.ServerConfig;
import tech.qiantong.qdata.module.system.domain.vo.CsvColumnReqVO;
import tech.qiantong.qdata.module.system.domain.vo.ExcelColumnReqVO;
import tech.qiantong.qdata.module.system.domain.vo.ColumnRespVO;

/**
 * Common Request Handler
 *
 * @author qdata
 */
@RestController
@RequestMapping("/common")
public class CommonController {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<tech.qiantong.qdata.common.file.UploadedFileAccess> uploadedFiles;
    @Autowired private org.springframework.beans.factory.ObjectProvider<tech.qiantong.qdata.common.file.DevelopmentFileAccess> developmentFiles;

    private static final Logger log = LoggerFactory.getLogger(CommonController.class);

    @Autowired
    private ServerConfig serverConfig;

    @Value("${datax.home}")
    private String csv_dir;

    @Value("${ds.hdfs.url}")
    private String hdfsUrl;

    @Value("${ds.spark.hadoop_conf_dir:}")
    private String hadoopConfDir;

    private static final String FILE_DELIMETER = ",";

    /**
     * Common download request
     *
     * @param fileName File name
     * @param delete   Whether to delete
     */
    @GetMapping("/download")
    public void fileDownload(String fileName, Boolean delete, HttpServletResponse response, HttpServletRequest request) {
        try {
            if (!FileUtils.checkAllowDownload(fileName)) {
                throw new Exception(StringUtils.format("File name ({}) is invalid, download not allowed. ", fileName));
            }
            String realFileName = System.currentTimeMillis() + fileName.substring(fileName.indexOf("_") + 1);
            String filePath = AniviaConfig.getDownloadPath() + fileName;
            java.nio.file.Path root=java.nio.file.Path.of(AniviaConfig.getProfile()).toRealPath();
            java.nio.file.Path target=java.nio.file.Path.of(filePath).toRealPath();
            if(!target.startsWith(root))throw new IllegalArgumentException("下载文件不在有效目录内");
            uploadedFiles.getObject().read(Constants.RESOURCE_PREFIX+"/"+root.relativize(target).toString().replace('\\','/'));

            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            FileUtils.setAttachmentResponseHeader(response, realFileName);
            FileUtils.writeBytes(filePath, response.getOutputStream());
            // The legacy delete flag is retained for request compatibility; downloading never deletes files.
        } catch (org.springframework.security.access.AccessDeniedException denied) {
            response.setStatus(403);
        } catch (Exception e) {
            response.setStatus(404);
            log.warn("Download unavailable type={}",e.getClass().getSimpleName());
        }
    }

    /**
     * Common upload request (single)
     */
    @PostMapping("/upload")
    public AjaxResult uploadFile(MultipartFile file) throws Exception {
        try {
            // Upload file path
            String filePath = AniviaConfig.getUploadPath();
            // Upload and return new file name
            String fileName = FileUploadUtils.upload(filePath, file);
            uploadedFiles.getObject().register(fileName,"UPLOAD");
            String url = serverConfig.getUrl() + fileName;
            AjaxResult ajax = AjaxResult.success();
            ajax.put("url", url);
            ajax.put("fileName", fileName);
            ajax.put("newFileName", FileUtils.getName(fileName));
            ajax.put("originalFilename", file.getOriginalFilename());
            return ajax;
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * Common upload request (multiple)
     */
    @PostMapping("/uploads")
    public AjaxResult uploadFiles(List<MultipartFile> files) throws Exception {
        try {
            // Upload file path
            String filePath = AniviaConfig.getUploadPath();
            List<String> urls = new ArrayList<String>();
            List<String> fileNames = new ArrayList<String>();
            List<String> newFileNames = new ArrayList<String>();
            List<String> originalFilenames = new ArrayList<String>();
            for (MultipartFile file : files) {
                // Upload and return new file name
                String fileName = FileUploadUtils.upload(filePath, file);
                uploadedFiles.getObject().register(fileName,"UPLOAD");
                String url = serverConfig.getUrl() + fileName;
                urls.add(url);
                fileNames.add(fileName);
                newFileNames.add(FileUtils.getName(fileName));
                originalFilenames.add(file.getOriginalFilename());
            }
            AjaxResult ajax = AjaxResult.success();
            ajax.put("urls", StringUtils.join(urls, FILE_DELIMETER));
            ajax.put("fileNames", StringUtils.join(fileNames, FILE_DELIMETER));
            ajax.put("newFileNames", StringUtils.join(newFileNames, FILE_DELIMETER));
            ajax.put("originalFilenames", StringUtils.join(originalFilenames, FILE_DELIMETER));
            return ajax;
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * Local resource download
     */
    @GetMapping("/download/resource")
    public void resourceDownload(String resource,HttpServletRequest request,HttpServletResponse response)throws Exception {
        try {
        String key=tech.qiantong.qdata.common.file.UploadedFileKey.of(resource);
        uploadedFiles.getObject().read(key);
        java.nio.file.Path file=tech.qiantong.qdata.common.utils.file.SafeUploadPath.resolve(java.nio.file.Path.of(AniviaConfig.getProfile()),key);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader("Cache-Control","private, no-store");
        FileUtils.setAttachmentResponseHeader(response,file.getFileName().toString());
        FileUtils.writeBytes(file.toString(),response.getOutputStream());
        } catch(org.springframework.security.access.AccessDeniedException denied) {
            response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getOutputStream().write("{\"code\":403,\"msg\":\"文件不属于当前可访问项目，或未获得相应读取权限\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch(IllegalArgumentException missing) {
            response.setStatus(404);response.setContentType("application/json;charset=UTF-8");response.getOutputStream().write("{\"code\":404,\"msg\":\"上传文件不存在或路径无效\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }


    /**
     * Get excel column names and convert to csv
     *
     * @return
     */
    @PostMapping("/getExcelColumn")
    public AjaxResult getExcelColumn(@RequestBody ExcelColumnReqVO excelColumnReqVO) {
        developmentFiles.getObject().authorizeSource(excelColumnReqVO.getProjectId(),excelColumnReqVO.getExcelFile());
        String targetHdfs = "DATAX".equals(excelColumnReqVO.getTaskType()) ? null : resolveHdfsUrl(hdfsUrl);
        String excelFile = tech.qiantong.qdata.common.utils.file.SafeUploadPath.resolve(
                java.nio.file.Path.of(AniviaConfig.getProfile()),excelColumnReqVO.getExcelFile()).toString();
        Integer startColumn = excelColumnReqVO.getStartColumn();
        Integer startData = excelColumnReqVO.getStartData();
        String fileName = UUID.randomUUID().toString().replace("-", "") + ".csv";
        String prefixUrl = "DATAX".equals(excelColumnReqVO.getTaskType()) ? csv_dir : new File(AniviaConfig.getProfile(), "etl").getPath() + File.separator;
        String csvFile = prefixUrl + "csv" + File.separator + fileName;
        List<String> columnList = ExcelToCsvUtil.convertExcelToCsv(excelFile, csvFile, startColumn, startData);
        if (columnList.size() > 0) {
            if (!ExcelToCsvUtil.verifyColumn(columnList)) {
                return AjaxResult.error("Column name format in attachment is incorrect, please check!");
            }
        }
        if ("DATAX".equals(excelColumnReqVO.getTaskType())) {
            developmentFiles.getObject().register(excelColumnReqVO.getProjectId(), excelColumnReqVO.getExcelFile(),prefixUrl + "csv/" + fileName);
            return AjaxResult.success(ColumnRespVO.builder().csvFile(prefixUrl + "csv/" + fileName).columnList(columnList).build());
        }
        uploadedFiles.getObject().register(Constants.RESOURCE_PREFIX+"/etl/csv/"+fileName,"UPLOAD");
        String hdfsPath = "/tmp/etl";
        uploadHdfs(targetHdfs, hdfsPath, csvFile, fileName);
        developmentFiles.getObject().register(excelColumnReqVO.getProjectId(),excelColumnReqVO.getExcelFile(),targetHdfs + hdfsPath + "/" + fileName);
        return AjaxResult.success(ColumnRespVO.builder()
                .csvFile(targetHdfs + hdfsPath + "/" + fileName)
                .columnList(columnList).build());
    }

    /**
     * Get excel column names and convert to csv
     *
     * @return
     */
    @PostMapping("/getCsvColumn")
    public AjaxResult getCsvColumn(@RequestBody CsvColumnReqVO csvColumnReqVO) {
        developmentFiles.getObject().authorizeSource(csvColumnReqVO.getProjectId(),csvColumnReqVO.getFile());
        String targetHdfs = "DATAX".equals(csvColumnReqVO.getTaskType()) ? null : resolveHdfsUrl(hdfsUrl);
        String file = tech.qiantong.qdata.common.utils.file.SafeUploadPath.resolve(
                java.nio.file.Path.of(AniviaConfig.getProfile()), csvColumnReqVO.getFile()).toString();
        String fileName = UUID.randomUUID().toString().replace("-", "") + ".csv";
        String prefixUrl = "DATAX".equals(csvColumnReqVO.getTaskType()) ? csv_dir : new File(AniviaConfig.getProfile(), "etl").getPath() + File.separator;
        String csvFile = prefixUrl + "csv" + File.separator + fileName;
        List<String> columnList = ExcelToCsvUtil.parseCsv(file, csvFile);
        if (columnList.size() > 0) {
            if (!ExcelToCsvUtil.verifyColumn(columnList)) {
                return AjaxResult.error("Column name format in attachment is incorrect, please check!");
            }
        }
        if ("DATAX".equals(csvColumnReqVO.getTaskType())) {
            developmentFiles.getObject().register(csvColumnReqVO.getProjectId(),csvColumnReqVO.getFile(),prefixUrl + "csv/" + fileName);
            return AjaxResult.success(ColumnRespVO.builder().csvFile(prefixUrl + "csv/" + fileName).columnList(columnList).build());
        }
        uploadedFiles.getObject().register(Constants.RESOURCE_PREFIX+"/etl/csv/"+fileName,"UPLOAD");
        String hdfsPath = "/tmp/etl";
        uploadHdfs(targetHdfs, hdfsPath, csvFile, fileName);
        developmentFiles.getObject().register(csvColumnReqVO.getProjectId(),csvColumnReqVO.getFile(),targetHdfs + hdfsPath + "/" + fileName);
        return AjaxResult.success(ColumnRespVO.builder()
                .csvFile(targetHdfs + hdfsPath + "/" + fileName)
                .columnList(columnList).build());
    }

    /**
     * Upload file to hdfs
     *
     * @param hdfsUrl  hdfs address
     * @param pathStr  Upload path
     * @param file     File path
     * @param filename File name
     */
    public void uploadHdfs(String hdfsUrl, String pathStr, String file, String filename) {
        pathStr = pathStr == null ? "" : pathStr;
        pathStr = resolvePath(pathStr, filename);
        String endpoint = resolveHdfsUrl(hdfsUrl);
        Configuration conf = hadoopConfiguration();
        conf.set("fs.defaultFS", endpoint);
        conf.set("dfs.client.use.datanode.hostname", "true");
        conf.set("dfs.replication", "1");
        Path path = new Path(pathStr);
        try (FileSystem fs = FileSystem.newInstance(new URI(endpoint), conf, "hadoop");
             InputStream inputStream = new FileInputStream(file);
             FSDataOutputStream outputStream = fs.create(path, false)) {
            IOUtils.copy(inputStream, outputStream);
        } catch (Exception e) {
            log.error("HDFS upload failed", e);
            throw new ServiceException("sys.error.hdfs.upload.fail",
                    "HDFS 上传失败，请检查 NameNode/DataNode 连通性及 /tmp/etl 写入权限；原始上传文件已保留");
        }
    }

    private Configuration hadoopConfiguration() {
        Configuration conf = new Configuration();
        if (hadoopConfDir != null && !hadoopConfDir.isBlank()) {
            conf.addResource(new Path(hadoopConfDir, "core-site.xml"));
            conf.addResource(new Path(hadoopConfDir, "hdfs-site.xml"));
        }
        return conf;
    }

    String resolveHdfsUrl(String configured) {
        String endpoint = configured == null || configured.isBlank()
                ? hadoopConfiguration().getTrimmed("fs.defaultFS", "") : configured.trim();
        try {
            URI uri = new URI(endpoint);
            if (!"hdfs".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() == null
                    || uri.getAuthority().isBlank() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("Invalid HDFS endpoint");
            }
            return endpoint.replaceAll("/+$", "");
        } catch (Exception invalid) {
            throw new ServiceException("sys.error.hdfs.config.invalid",
                    "未配置有效 HDFS 地址。请设置 ds.hdfs.url（QDATA_HDFS_URL）为 hdfs://NameNode:端口 或 HA nameservice，"
                            + "并通过 ds.spark.hadoop_conf_dir 提供 core-site.xml、hdfs-site.xml；原始上传文件已保留");
        }
    }

    private String resolvePath(String path, String filename) {
        String str = path + "/" + filename;
        return str.replaceAll("/+", "/");
    }
}
