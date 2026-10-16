# BIN-104：DataX Hive JDBC 准备与95实施交接

作者：全栈研发工程师-Forge；2026-10-16，Asia/Shanghai。来源 BIN-104 / BIN-103（BIN68-DATAX-003）。本文件是待审查方案，本研发棒没有实施95变更。技术总负责人-Atlas审查并核对 BIN-103 授权卡 `e2c3586f-7ee6-4566-9215-ca0d26537157` 的最终范围后，由测试与部署工程师-Sentinel执行。

## 根因与证据边界

`DataXJsonBuilder` 的 Hive 输出使用 `rdbmswriter`，JDBC URL 为 `jdbc:hive2://...`；追加模式2不生成 `writeMode`，空 pre/post SQL 被省略。此映射与 Hive JDBC 协议一致，无需改生成器。

官方 DataX 202309 通用插件只自带 DM、Sybase、EDB、DB2 驱动。旧 `prepare-datax.sh` 仅补充 MySQL，未安装或登记 Hive。实际 `DBUtil.loadDriverClass` 读取当前插件的 `plugin.json`、调用 `Class.forName`；`RDBMS` 类型的 `DBUtil.connect` 通过 `DriverManager` 匹配 JDBC URL。每个插件有独立 `JarLoader`，Web JAR 和其他插件的依赖不能证明当前插件可见。

用固定官方二进制的真实 `JarLoader`、`DBUtil` 和真实驱动进行离线检查，旧准备产物 MySQL=true/Hive2=false；仅加 JAR 而不显式注册时 Hive2=false；两者齐备后两种插件均 MySQL=true/Hive2=true；只有注册无 JAR 时出现 `ClassNotFoundException`。驱动 SPI 在暴露插件目录前初始化，以避免测试依赖偶然的线程上下文类加载顺序。检查只调用驱动查找与 URL 判断，连接数为0。

因此已确证**仓库部署准备缺少插件级 Hive 制品和显式注册**，不是只有现场漏装的纯环境问题。95 的失败栈与此缺口相符，但本棒未读取95当前插件目录、配置或哈希，不能排除另有现场漂移，不能声称已证实95当前每个文件的状态或完成修复。

## 固定制品与兼容依据

| 项目 | 来源、版本与 SHA-256 |
|---|---|
| DataX | [Alibaba官方发行物](https://datax-opensource.oss-cn-hangzhou.aliyuncs.com/202309/datax.tar.gz)，202309，`5f3d8cbdf25fd8476c8124f6ca3c892a7f4c2286e55da629771848adff944788` |
| Hive JDBC | [Maven Central](https://repo.maven.apache.org/maven2/org/apache/hive/hive-jdbc/4.2.0/hive-jdbc-4.2.0-standalone.jar)，`org.apache.hive:hive-jdbc:4.2.0:standalone`，`c89c6224df9d465ddf96060bc3e289522f1f6e64b01a28e01f94413edeb3d43a` |
| MySQL JDBC（保持既有） | [Maven Central](https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar)，9.7.0，`0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44` |

Hive 下载文件还核对了 Maven Central SHA-1：`ac2fa84673e62ee614211dded1707b9026614a40`。Hive 4.2.0 是项目既有客户端依赖，不升级主版本；`qdata-common/pom.xml` 约定 Hive 4.2.0 / Kyuubi 1.11。standalone 包含该驱动加载需要的依赖，普通171 KB JAR不能替代。实际离线检查使用 JDK 21.0.10，与项目 Linux/JDK21 运行约定一致。

[Hive官方说明](https://hive.apache.org/docs/latest/user/hiveserver2-clients/)确认 `org.apache.hive.jdbc.HiveDriver` 与 `jdbc:hive2`；[Kyuubi 1.9.1发布说明](https://kyuubi.apache.org/release/1.9.1.html)记录 Hive JDBC 4.0 支持。以上是协议与选型依据，不代表4.2.0与95真实服务端的认证、传输和所有SQL路径已验证。

## 新安装与离线验证

`prepare-datax.sh` 新增 `--hive-driver-jar`；安装器新增 `--datax-hive-driver`。联网准备固定下载并校验两个驱动，离线需同时提供它们。两个通用插件各安装一份驱动并显式登记；保留其他驱动和元数据。发行物若已有同类 JAR 则拒绝叠加，不覆盖既有目标。

```bash
bash deploy/prepare-datax.sh --dest /opt/qdata-datax-new \
  --archive /opt/packages/datax.tar.gz \
  --mysql-driver-jar /opt/packages/mysql-connector-j-9.7.0.jar \
  --hive-driver-jar /opt/packages/hive-jdbc-4.2.0-standalone.jar
```

`--datax-home` 复用已有安装时不会修改其驱动；须按下面方案另行处理。下面的探针必须使用实际 DataX 根目录和受审查代码，独立临时类目录；不调用 `datax.py`。

```bash
javac -d /tmp/bin104-probe-classes deploy/tests/DataxDriverProbe.java
jar --create --file /tmp/bin104-probe-classes/probe-check.jar \
  -C /tmp/bin104-probe-classes 'DataxDriverProbe$PluginCheck.class'
java -cp /tmp/bin104-probe-classes DataxDriverProbe "$DATAX_HOME95" reader /tmp/bin104-probe-classes true
java -cp /tmp/bin104-probe-classes DataxDriverProbe "$DATAX_HOME95" writer /tmp/bin104-probe-classes true
```

预期每个插件输出 `hive2=true mysql=true actualDataxJarLoader=true actualDBUtil=true connections=0`。若核心 JAR 或插件自定义路径与本报告不同，先停止实施并交技术总负责人-Atlas协调，不更换整个DataX发行物掩盖差异。

## 95备份、实施顺序与影响范围

1. 核对授权已覆盖本次驱动/配置与零行回归。确认27号无运行实例，协调其他DataX任务处于空闲；不修改任务、调度或业务数据。冻结Web受测基线 `b5ae853f47de7dc743eedf04cfac3913e253b92a` / JAR `9b41cccf9ae952ef8a69b4795eb636bdd56f684fdc1c37e664524376a44505b3`。如实际不同，由技术总负责人-Atlas重新确认受测版本。
2. 只读取有效运行配置中的 `datax.home`、`datax.datax-py-path`、`datax.lib-dir` 和Java版本，确定 `DATAX_HOME95`；历史默认 `/data/qdata/tools/datax` 不能当作本次实测路径。不得输出整个配置或连接信息。检查它们指向同一安装、无符号链接歧义，并记录真实路径、属主与模式。
3. 在 `/data/backups/bin104-datax-hive-<UTC>-<本次提交前8位>/` 建立权限0700的独立备份。保留两个完整目录 `plugin/reader/rdbmsreader/`、`plugin/writer/rdbmswriter/`，根 `qdata-tool-manifest.json`（若有）；记录相关文件 SHA-256、属主、权限、备份哈希。备份只读封存，不自动清理。已有任何 Hive JDBC JAR 或 HiveDriver 注册时先检查现状：完全一致则无需重复变更；版本或来源不同则暂停并提交差异，不能叠加或覆盖。
4. 暂存并核对上述Hive制品；两个插件内各新增 `libs/hive-jdbc-4.2.0-standalone.jar`，权限与原插件JAR一致。保留既有 MySQL JAR、其他文件、注册项和元数据。每份 `plugin.json` 的 `drivers` 数组仅补入一次 `org.apache.hive.jdbc.HiveDriver`，先写临时文件解析校验，再原子替换；两份JAR就位后才发布注册文件。全程协调任务空闲，防止中间状态被新任务读取。
5. 记录两份JAR与配置最终哈希，保存变更前后清单到备份目录。对未修改的MySQL JAR和核心/插件代码哈希做前后相等检查。参考官方发行物经新脚本处理的两份配置哈希为 reader `74a27aefed0b5c9ec9c6b03c8cf435cf3eeb521cee535088a275f4af9605ef39`、writer `44f0439069a3270314e5544a964c74a7f5823dec915434a6b6a0ebc3ed002c16`。这些哈希只适用于完全相同的上游元数据；95配置有合法差异时按结构增量及现场实测摘要审查，不盲目覆盖成参考文件。建议保留原工具清单，另写 `bin104-driver-change.json` 记录现场增量及哈希，避免伪造整套重装清单。
6. 使用与qData子进程相同的Java路径和服务账号运行上面的两个离线探针。失败时先恢复，不继续跑业务任务。本次不改Web/ETL JAR、全局DataX lib、Hadoop/Hive/Kyuubi配置，不需要数据库迁移。`DataXExecutor` 每次用 `ProcessBuilder` 启动新的Python/Java子进程，单改插件文件通常无需重启qdata-web。Hive/Kyuubi/HDFS及qdata-quality、qdata-ai均无需重启；若发现现场另有长期驻留DataX进程，暂停交技术总负责人-Atlas确认，不擅自杀进程或扩大重启范围。
7. 只有独立授权要求同时换qData应用版本时，才按该换版方案备份Web JAR与运行配置、停止 `qdata-web.service`、发布受审查应用并启动；这不是本缺陷必需步骤，不能把当前main的全部其他修复顺带部署。

## 失败恢复

保持任务空闲，先保留失败日志（脱敏）和变更后文件清单。恢复备份中的两份 `plugin.json`；将本次新增的两个 Hive JAR移到上述备份目录下新建的 `disabled-after-failure/reader/`、`writer/`，离开插件递归扫描范围，不删除文件、备份或数据。恢复前核对当前文件仍是本次哈希，若有并发变化则停止并协调。若更新过额外增量清单，也保留并移出活动目录；原根清单保持原样。

用SHA-256、属主/模式核对恢复与备份一致，MySQL驱动查找应仍通过；旧环境Hive2不可用属于恢复到原状态，不得记录为修复成功。仅在本次另有授权且实际重启/换版时，按相同服务顺序恢复应用和配置并启动受影响服务。备份保留。无需、不得通过删表、清空、删分区或覆盖Hive/HDFS数据恢复。

## 零行回归交接与未覆盖范围

测试与部署工程师-Sentinel在获授权后先只读核对27号 `bin68_datax_1016` 的配置未漂移：源 `acc-mysql-src/t_acc_src`、`where 1=0`；目标 `ods_iot_db.bin68_datax_target_1016` 四列 `id/name/amt/created`、追加 `writeModeType=2`、pre/post SQL均空。记录目标表只读计数与schema；若非0行或字段变化，停止并交技术总负责人-Atlas，不清理或覆盖。模型70/dataset21/asset35及任务保持原状。

运行仅一次授权零行任务，保存新实例ID、进程退出码、平台状态、读写记录数、脱敏异常和驱动摘要。预期writer初始化越过原错误、任务最终成功、读0/写0、目标仍0行且四字段不变；不能只检查“不再出现No suitable driver”。如转为认证、依赖、元数据、事务或批写错误，按新缺陷记录，不通过增加超时或删除数据规避。不添加非零Hive写入或MySQL写入验证。

本研发证据未覆盖真实95进程、真实服务端握手/权限/元数据、非零写入、Hive事务及批提交、Kerberos/TLS、真实Linux壳脚本完整执行、浏览器和运行态验收。尤其Hive JDBC某些事务/批处理方法与通用writer可能仍有差异，驱动成功加载不保证完整任务通过。独立回归后由技术总负责人-Atlas审查，随后交版本规划管理工程师-Roadmap登记；本棒不直接派后续任务。
