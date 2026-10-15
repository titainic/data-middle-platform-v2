package tech.qiantong.qdata.module.dpp.service.etl.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.enums.TaskComponentTypeEnum;
import tech.qiantong.qdata.common.exception.ServiceException;
import tech.qiantong.qdata.common.utils.JSONUtils;
import tech.qiantong.qdata.common.utils.StringUtils;
import tech.qiantong.qdata.module.dpp.controller.admin.etl.vo.DppEtlNodeRespVO;
import tech.qiantong.qdata.module.dpp.dal.dataobject.etl.DppEtlTaskDO;
import tech.qiantong.qdata.module.dpp.service.etl.IDppEtlNodeService;
import tech.qiantong.qdata.module.dpp.utils.TaskConverter;
import tech.qiantong.qdata.module.dpp.utils.log.LogUtils;
import jakarta.annotation.Resource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local Spark executor for data integration tasks whose execution engine is SPARK
 * (draftJson.taskType == "SPARK") in the Quartz local-scheduler mode.
 * <p>
 * It rebuilds the same EtlApplication payload the DolphinScheduler path produces
 * via {@link TaskConverter#buildEtlTaskParams} and submits it with spark-submit.
 * Task/node instance state and execution logs are reported by EtlApplication
 * itself over RabbitMQ (ProcessListener/TaskListener/TaskLogListener); this
 * runner only surfaces the driver process output and exit code.
 *
 * @author qdata
 */
@Service
@Slf4j
public class DppEtlTaskSparkRunner {

    /** Lines of driver output kept in the failure message so the UI shows the root cause. */
    private static final int FAILURE_LOG_TAIL_LINES = 30;

    @Resource
    private DppDatasourceResolver datasourceResolver;
    @Resource
    private IDppEtlNodeService iDppEtlNodeService;
    @Resource
    private SparkPreflightFailureRecorder preflightFailures;

    @Value("${ds.spark.master_url}")
    private String sparkMaster;

    @Value("${ds.spark.main_class}")
    private String mainClass;

    @Value("${ds.spark.main_jar}")
    private String mainJar;

    /**
     * spark-submit executable. Point it at the Spark 4.x distribution that matches
     * the version qdata-executor-etl was built with.
     */
    @Value("${ds.spark.submit_path:spark-submit}")
    private String sparkSubmitPath;

    /** Optional HADOOP_CONF_DIR for yarn-master submissions; also read from SPARK_HOME/conf when empty. */
    @Value("${ds.spark.hadoop_conf_dir:}")
    private String hadoopConfDir;

    /** Optional HDFS/YARN submission user (HADOOP_USER_NAME); defaults to the server process user. */
    @Value("${ds.spark.hadoop_user:}")
    private String hadoopUser;

    /** Address reachable by YARN containers; leave unset for Spark's default discovery. */
    @Value("${ds.spark.driver_host:}")
    private String driverHost;

    @Value("${ds.spark.driver_bind_address:}")
    private String driverBindAddress;

    @Value("${ds.spark.driver_port:0}")
    private int driverPort;

    @Value("${ds.spark.driver_block_manager_port:0}")
    private int driverBlockManagerPort;

    /**
     * Builds the ETL payload and blocks until the driver process finishes.
     *
     * @param dppEtlTaskDO data integration task details
     * @param taskLog      live log buffer that receives the driver output
     */
    public void startSparkEtlTask(DppEtlTaskDO dppEtlTaskDO, StringBuilder taskLog) {
        Map<String, Object> mainArgs;
        try {
            mainArgs = prepareTask(dppEtlTaskDO);
        } catch (RuntimeException failure) {
            // No driver has started, so RabbitMQ cannot report an instance for this attempt.
            try {
                preflightFailures.record(dppEtlTaskDO, failure);
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
                log.error("Failed to persist Spark preflight failure, taskId={}", dppEtlTaskDO.getId(), persistenceFailure);
            }
            throw failure;
        }
        String payloadJson = JSON.toJSONString(mainArgs);

        LogUtils.appendLocalLogLine(taskLog, "********************************* Execute Spark task instance ********************************");
        LogUtils.appendLocalLogLine(taskLog, "spark master: " + sparkMaster + ", main jar: " + mainJar);

        Path payloadFile = null;
        try {
            // The payload easily exceeds the OS single-argument limit, so hand it over as a file.
            payloadFile = Files.createTempFile("qdata-etl-" + dppEtlTaskDO.getId() + "-", ".b64");
            Files.write(payloadFile, Base64.getEncoder().encode(payloadJson.getBytes(StandardCharsets.UTF_8)));

            int exitCode = runSparkSubmit(dppEtlTaskDO, payloadFile, taskLog);
            LogUtils.appendLocalLogLine(taskLog, "spark-submit exitCode: " + exitCode);
            LogUtils.appendLocalLogLine(taskLog, "*********************************** Execute Spark task end *************************************");
            if (exitCode != 0) {
                throw new ServiceException("dpp.error.spark.execution.exit.code",
                        "Spark task execution failed with exit code {0}. {1}",
                        String.valueOf(exitCode), tailOf(taskLog));
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to execute local Spark task {}", dppEtlTaskDO.getId(), e);
            throw new ServiceException("dpp.error.spark.execution.fail",
                    "Spark task execution failed: {0}", String.valueOf(e.getMessage()));
        } finally {
            if (payloadFile != null) {
                try {
                    Files.deleteIfExists(payloadFile);
                } catch (Exception ignore) {
                    // A leftover temp payload file is harmless.
                }
            }
        }
    }

    /** Read-only validation for interactive submission; scheduled runs revalidate before launching. */
    public void validateTask(DppEtlTaskDO task) {
        prepareTask(task);
    }

    private Map<String, Object> prepareTask(DppEtlTaskDO dppEtlTaskDO) {
        List<DppEtlNodeRespVO> nodeList = iDppEtlNodeService.listNodeByTaskId(dppEtlTaskDO.getId());
        if (nodeList == null || nodeList.isEmpty()) {
            throw new ServiceException("dpp.error.spark.nodes.missing",
                    "The local Spark task has no configured nodes; save the task first");
        }
        validateReaderAndWriter(nodeList);
        return buildEtlMainArgs(dppEtlTaskDO, nodeList);
    }

    private void validateReaderAndWriter(List<DppEtlNodeRespVO> nodeList) {
        boolean hasReader = nodeList.stream().anyMatch(n ->
                TaskComponentTypeEnum.DB_READER.getCode().equals(n.getComponentType())
                        || TaskComponentTypeEnum.EXCEL_READER.getCode().equals(n.getComponentType())
                        || TaskComponentTypeEnum.CSV_READER.getCode().equals(n.getComponentType()));
        boolean hasWriter = nodeList.stream().anyMatch(n ->
                TaskComponentTypeEnum.DB_WRITER.getCode().equals(n.getComponentType()));
        if (!hasReader || !hasWriter) {
            throw new ServiceException("dpp.error.spark.input.output.missing",
                    "The local Spark task has no input or output node; save the task first");
        }
    }

    private Map<String, Object> buildEtlMainArgs(DppEtlTaskDO dppEtlTaskDO, List<DppEtlNodeRespVO> nodeList) {
        // Rebuild the node definition list the payload builder expects. The saved node
        // parameters JSON is the taskParams map of the node's form.
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (DppEtlNodeRespVO node : nodeList) {
            Map<String, Object> definition = new HashMap<>();
            definition.put("code", node.getCode());
            definition.put("componentType", node.getComponentType());
            definition.put("name", node.getName());
            definition.put("taskParams", datasourceResolver.refreshNodeParameters(
                    JSONUtils.convertTaskDefinitionJsonMap(node.getParameters())));
            definitions.add(definition);
        }

        Map<String, Object> taskInfo = new HashMap<>();
        taskInfo.put("projectCode", dppEtlTaskDO.getProjectCode());
        taskInfo.put("taskCode", String.valueOf(dppEtlTaskDO.getCode()));
        taskInfo.put("taskVersion", dppEtlTaskDO.getVersion() == null ? 1 : dppEtlTaskDO.getVersion());
        taskInfo.put("name", dppEtlTaskDO.getName());

        return TaskConverter.buildEtlTaskParams(
                JSON.toJSONString(definitions), new HashMap<>(), taskInfo, new ArrayList<>());
    }

    private int runSparkSubmit(DppEtlTaskDO dppEtlTaskDO, Path payloadFile, StringBuilder taskLog) throws Exception {
        List<String> command = buildCommand(dppEtlTaskDO, payloadFile);

        LogUtils.appendLocalLogLine(taskLog, "command: " + StringUtils.join(command, " "));
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        if (StringUtils.isNotBlank(hadoopConfDir)) {
            processBuilder.environment().put("HADOOP_CONF_DIR", hadoopConfDir);
        }
        if (StringUtils.isNotBlank(hadoopUser)) {
            processBuilder.environment().put("HADOOP_USER_NAME", hadoopUser);
        }
        Process process = processBuilder.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LogUtils.appendLocalLogLine(taskLog, line);
                log.info("[spark-etl-{}] {}", dppEtlTaskDO.getId(), line);
            }
        }
        return process.waitFor();
    }

    List<String> buildCommand(DppEtlTaskDO dppEtlTaskDO, Path payloadFile) {
        List<String> command = new ArrayList<>();
        command.add(sparkSubmitPath);
        command.add("--master");
        command.add(sparkMaster);
        // Client mode keeps the driver on this host so EtlApplication can reach the
        // local RabbitMQ/Redis it reports status through.
        command.add("--deploy-mode");
        command.add("client");
        // Hive JDBC 4.2 client must win over Spark's bundled Hive 4.0 classes
        // on the driver classpath, otherwise HiveAuthUtils breaks with
        // NoSuchMethodError. The fat jar is prepended via driver-class-path.
        command.add("--driver-class-path");
        command.add(mainJar.startsWith("file:") ? Path.of(java.net.URI.create(mainJar)).toString() : mainJar);
        if (StringUtils.isNotBlank(driverHost)) {
            command.add("--conf");
            command.add("spark.driver.host=" + driverHost.trim());
        }
        if (StringUtils.isNotBlank(driverBindAddress)) {
            command.add("--conf");
            command.add("spark.driver.bindAddress=" + driverBindAddress.trim());
        }
        addPort(command, "spark.driver.port", driverPort);
        addPort(command, "spark.driver.blockManager.port", driverBlockManagerPort);
        if (driverPort != 0 || driverBlockManagerPort != 0) {
            command.add("--conf");
            command.add("spark.port.maxRetries=16");
        }
        command.add("--class");
        command.add(mainClass);
        command.add("--name");
        command.add("qdata-etl-" + dppEtlTaskDO.getId());
        JSONObject draft = parseDraftJson(dppEtlTaskDO.getDraftJson());
        addIfPresent(command, "--driver-cores", draft, "driverCores");
        addIfPresent(command, "--driver-memory", draft, "driverMemory");
        addIfPresent(command, "--num-executors", draft, "numExecutors");
        addIfPresent(command, "--executor-memory", draft, "executorMemory");
        addIfPresent(command, "--executor-cores", draft, "executorCores");
        command.add(mainJar);
        command.add("file:" + payloadFile);

        return command;
    }

    private void addPort(List<String> command, String key, int port) {
        if (port < 0 || port > 65519) {
            throw new IllegalArgumentException(key + " must leave room for 16 port retries");
        }
        if (port != 0) {
            command.add("--conf");
            command.add(key + "=" + port);
        }
    }


    private JSONObject parseDraftJson(String draftJson) {
        if (StringUtils.isBlank(draftJson)) {
            return new JSONObject();
        }
        try {
            JSONObject draft = JSON.parseObject(draftJson);
            return draft == null ? new JSONObject() : draft;
        } catch (Exception e) {
            log.warn("Failed to parse task draft json, ignoring Spark resource settings", e);
            return new JSONObject();
        }
    }

    private void addIfPresent(List<String> command, String flag, JSONObject draft, String key) {
        String value = draft.getString(key);
        if (StringUtils.isNotBlank(value) && !"0".equals(value)) {
            command.add(flag);
            command.add(value);
        }
    }

    private String tailOf(StringBuilder taskLog) {
        String[] lines = taskLog.toString().split("\n");
        StringBuilder tail = new StringBuilder();
        int from = Math.max(0, lines.length - FAILURE_LOG_TAIL_LINES);
        for (int i = from; i < lines.length; i++) {
            tail.append(lines[i]).append('\n');
        }
        return tail.toString();
    }
}
