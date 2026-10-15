# qData 完整 Linux 部署教程（JDK 21）

本教程在一台全新 Linux 主机安装 qData，并连接部署方已有集群。全部 qData 源码、完整初始化 SQL、构建和安装程序来自本 GitHub 仓库。Hive、Kyuubi、Spark、YARN、Doris、DolphinScheduler 使用部署方已有集群；安装器不会重置或清理集群数据。

**普通新装的密码操作只有一项：输入 `admin` 的登录密码。** 可以继续使用原来熟悉的密码，长度 5–20 位。准备好环境后按第 3 节下载、第 5 节安装即可；AES、RSA 和指纹校验由安装器自动处理，不需要手工配置 `--key-file`。

## 1. 部署内容与目录

```text
浏览器 → Linux 访问代理 → qData 主平台（前端 + Java 后端）
                         ├─ MySQL：项目、模型、任务、权限、质量明细、服务配置
                         ├─ Redis / RabbitMQ / Neo4j
                         ├─ 质量执行器 / AI 服务
                         ├─ HiveServer2 / Kyuubi → Hive 五个物理数据库
                         ├─ DS Worker → qData 五层单步回调
                         └─ Hive ADS → Doris → OAuth API → 应用
```

| 仓库路径 | 用途 |
|---|---|
| `qdata-work/qdata-server`、`qdata-work/qdata-ui` | Java 主平台与完整 Vue 前端 |
| `qdata-work/qdata-framework`、`qdata-work/qdata-module-*` | 公共组件及全部业务模块 |
| `qdata-work/qdata-api-ds` | DolphinScheduler 集成 |
| `qdata-work/qdata-service-quality` | 质量执行器，质量问题明细使用 MySQL |
| `qdata-work/qdata-executor-etl` | Spark ETL 与 DataX 集成代码 |
| `qdata-work/qdata-service-ai` | 独立 AI 服务与核心模块 |
| `deploy/database/schema.sql` | 全新空库完整表结构 |
| `deploy/database/reference.sql` | 公共菜单、字典、配置数据 |
| `deploy/database/bootstrap.sql` | 管理员、角色、部门基础关系 |
| `deploy/database/five-layers.sql` | qData MySQL 中 ODS / DWD / DWS / DWT / ADS 五层目录 |
| `deploy/build.sh`、`deploy/install.py` | 完整源码构建与 Linux 新实例安装 |
| `qdata-work/sql/mysql/migration` | 既有环境升级用增量迁移 |

下载源码可放在 `/data/src/data-middle-platform-v2`，运行实例示例安装到 `/data/qdata`。构建产物位于仓库 `deploy/artifacts/`；安装目录 `runtime/` 保存受限配置与密钥，数据及上传目录由运行实例管理。

## 2. 准备 Linux 环境

需要 64 位 Linux + systemd、JDK **21**、Maven **3.9 或更高**、Node.js **20 或更高（建议 22）**、npm、Python 3、Git、curl、tar、unzip、openssl、Fontconfig 和至少一套字体。工具与 CPU 架构匹配；JDK 8 / 17 不能构建此工程。

Ubuntu / Debian 基础工具：

```bash
sudo apt-get update
sudo apt-get install -y git curl ca-certificates tar unzip python3 openssl fontconfig libfontconfig1 fonts-dejavu-core
fc-list | head
```

RHEL / Rocky 等使用对应包管理器安装 `fontconfig` 和 `dejavu-sans-fonts`。`fc-list` 必须有字体输出；Java headless 模式也需要字体用于验证码渲染，无字体会造成验证码 500，不能跳过验证码来处理。

JDK 按 [Eclipse Temurin 官方 Linux 安装说明](https://adoptium.net/en-GB/installation/linux) 配置源并安装 `temurin-21-jdk`；Ubuntu 24.04 可用系统 `openjdk-21-jdk`。Maven 从 [Apache 官方下载页](https://maven.apache.org/download.cgi) 选择 3.9.x binary tar.gz 并核对 SHA-512 / 签名。Node 从 [Node.js 22 官方下载页](https://nodejs.org/en/download/archive/v22) 获取对应 Linux 架构包。解压后按实际路径设置：

```bash
export JAVA_HOME=/opt/jdk-21
export PATH="$JAVA_HOME/bin:/opt/apache-maven/bin:/opt/node/bin:$PATH"
java -version
javac -version
mvn -version
node --version
npm --version
python3 --version
```

`/opt/` 目录需与实际安装一致，`mvn -version` 的 Java 也必须显示 21。安装器使用 root 权限；手工解压的 Maven / Node 可能不在 sudo 默认 PATH，使用 `sudo env PATH="$PATH" JAVA_HOME="$JAVA_HOME" python3 ... --java-home "$JAVA_HOME"` 明确传入工具路径。以下 `/root/` 私有输入文件准备步骤在 root shell 执行，可先 `sudo -i`，再设置该 shell 的 `JAVA_HOME` 和 `PATH`。安装器启动独立系统用户，JDK 与 Spark 路径的所有父目录必须允许该用户访问。

默认安装会在目标 **Linux** 用 Docker Engine 创建 MySQL、Redis、RabbitMQ、Neo4j 和访问代理；按 [Docker 官方发行版安装说明](https://docs.docker.com/engine/install/) 安装 Engine。qData Java 服务使用 systemd。若连接已有基础依赖，使用第 5 节选项；无 Docker 时须准备原生 Nginx。构建和安装不需要连接 Mac Docker。

联网构建需能访问 GitHub、npm、Maven 仓库与依赖镜像仓库；不联网时参考第 4 节。检查磁盘可容纳构建缓存、成套 JAR、数据库与上传数据，并为运行数据留足增长空间。

## 3. 从 GitHub 下载并构建

使用 Git：

```bash
mkdir -p /data/src
cd /data/src
git clone https://github.com/titainic/data-middle-platform-v2.git
cd data-middle-platform-v2
git rev-parse HEAD
```

也可下载 ZIP：

```bash
curl -fL https://github.com/titainic/data-middle-platform-v2/archive/refs/heads/main.zip -o qdata-main.zip
unzip qdata-main.zip
cd data-middle-platform-v2-main
```

记录实际 commit SHA 作为部署版本。Git 可 `git checkout <审核后的提交SHA>`；ZIP 可从 GitHub 对应提交下载。产品展示版本 `1.6.1` 不能唯一标识改造版本。

正常新装直接执行第 5 节安装器，输入管理员登录密码即可。它自动准备加密配置并调用构建脚本，不需要执行下面的高级操作。构建使用公开模板准备独立目录，所有 qData 模块从仓库源码编译；前端打入主 JAR，默认构建独立 AI。现有运行 YAML、密钥、数据库和历史 JAR 不作为源码构建输入。

<details>
<summary>高级操作：先独立编译 JAR，再用指定配置安装</summary>

需要先独立构建再安装时，先在 Linux 生成受限的新实例输入文件（目录应为空，已有环境升级沿用原密钥）：

```bash
umask 077
mkdir -p /root/qdata-deploy-input
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /root/qdata-deploy-input/server-private.pem
openssl pkey -in /root/qdata-deploy-input/server-private.pem -pubout -out /root/qdata-deploy-input/server-public.pem
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /root/qdata-deploy-input/browser-private.pem
openssl pkey -in /root/qdata-deploy-input/browser-private.pem -pubout -out /root/qdata-deploy-input/browser-public.pem
python3 - <<'PY'
import pathlib, secrets
root = pathlib.Path('/root/qdata-deploy-input')
aes = secrets.token_hex(8)
public = (root / 'browser-public.pem').read_text().replace('\n', '\\n')
private = (root / 'browser-private.pem').read_text().replace('\n', '\\n')
(root / 'frontend.env').write_text('VITE_APP_AES_KEY=' + aes + '\nVITE_APP_RSA_PUBLIC_KEY=' + public + '\nVITE_APP_RSA_PRIVATE_KEY=' + private + '\n')
(root / 'keys.env').write_text('QDATA_AES_KEY=' + aes + '\nQDATA_RSA_PRIVATE_KEY_FILE=' + str(root / 'server-private.pem') + '\nQDATA_RSA_PUBLIC_KEY_FILE=' + str(root / 'server-public.pem') + '\nQDATA_BROWSER_RSA_PRIVATE_KEY_FILE=' + str(root / 'browser-private.pem') + '\nQDATA_BROWSER_RSA_PUBLIC_KEY_FILE=' + str(root / 'browser-public.pem') + '\n')
PY
bash deploy/build.sh --env-file /root/qdata-deploy-input/frontend.env
sudo python3 deploy/install.py --install-dir /data/qdata --instance qdata --with-ai --skip-build --key-file /root/qdata-deploy-input/keys.env
```

`frontend.env` / `keys.env` 不入 Git。安装器核对构建 manifest 的 AES / 浏览器 RSA 公钥指纹，不匹配时拒绝安装。浏览器“记住密码”使用单独兼容密钥对；后端 RSA 私钥与它分开，不能放入前端产物。

</details>

预期产物：

```text
deploy/artifacts/qdata-server.jar
deploy/artifacts/qdata-service-quality.jar
deploy/artifacts/qdata-executor-etl.jar
deploy/artifacts/qdata-ai-server.jar
deploy/artifacts/PasswordHash.class
```

保留构建日志和 manifest。源码编译在新建的 `deploy/build/<时间>/source/` 中进行，原下载目录的 `qdata-work/*/target` 不作为输出；稳定产物入口为 `deploy/artifacts/`。脚本默认跳过 Maven 测试，成功只证明编译 / 打包，业务验证按第 9 节执行。完整部署保留默认 AI 构建；`--skip-ai` 是明确精简选项。

各应用 Dockerfile 已使用 Java 21 基础镜像，是辅助构建入口。本教程的应用安装路径为 JDK 21 + systemd。自行构建应用镜像时，需先把对应 `deploy/artifacts/*.jar` 放到该模块的 `target/`，再以模块目录为 Docker context，例如主平台：

```bash
mkdir -p qdata-work/qdata-server/target
cp deploy/artifacts/qdata-server.jar qdata-work/qdata-server/target/qdata-server.jar
docker build -f qdata-work/qdata-server/docker/Dockerfile -t qdata-web:local qdata-work/qdata-server
```

应用镜像还需配置实例密钥、外部配置、持久化数据、网络和字体。应用 Dockerfile 的 Java 21 修改不代表应用镜像部署已验收；本次基础依赖容器与原生 Java 应用的验证范围分别记录。

## 4. 内网 / 离线构建

源码仍由本 GitHub 获取。联网 Linux 构建机准备相同架构 JDK 21、Maven、Node、第三方 Maven/npm 缓存和基础服务镜像，再传入内网。

普通离线新装也由安装器完成构建和配置，只需输入管理员登录密码：

```bash
sudo env PATH="$PATH" JAVA_HOME="$JAVA_HOME" python3 deploy/install.py --java-home "$JAVA_HOME" --install-dir /data/qdata --instance qdata --with-ai --offline --dependency-dir /opt/qdata-dependencies
```

<details>
<summary>高级操作：只进行离线构建</summary>

已按第 3 节高级操作准备构建配置时，可单独执行：

```bash
bash deploy/build.sh --offline --dependency-dir /opt/qdata-dependencies --env-file /root/qdata-deploy-input/frontend.env
```

</details>

依赖目录包含 `maven/`、Linux `node_modules/` 和 `manifest.json`，后者需记录 `os=Linux`、`arch`（`uname -m`）与 `npmLockSha256`（锁文件 SHA-256）。Maven 目录排除 `tech/qiantong`，该目录包含全部第三方依赖；qData 自身模块仍从源码编译。Linux `node_modules` 不能由 Mac 目录代替。ZIP 本身不包含工具、第三方依赖或数据库镜像。

在已经成功完整构建的联网 Linux 上，可使用仓库工具导出这个依赖目录：

```bash
QDATA_BUILD_SOURCE=$(python3 -c 'import json; print(json.load(open("deploy/artifacts/build-manifest.json"))["buildSource"])')
python3 deploy/tools/make_dependency_kit.py --maven-repo "$(dirname "$QDATA_BUILD_SOURCE")/maven" --node-modules "$QDATA_BUILD_SOURCE/qdata-ui/node_modules" --lock-file qdata-work/qdata-ui/package-lock.json --output /opt/qdata-dependencies
```

输出目录必须不存在。由目标 CPU 架构相同的 Linux 构建生成，再传入内网机器。

离线安装前将对应版本 / 架构镜像导入目标 Linux Engine，或连接已有基础服务。DataX 和 Spark client 使用第 6 节的匹配发行物；离线命令需追加 `--datax-archive /opt/packages/datax.tar.gz --datax-mysql-driver /opt/packages/mysql-connector-j-9.7.0.jar`（或指向已有 `--datax-home`），不能依赖默认联网下载。

没有系统字体且需要保持现有 OS 包不变时，安装器支持 `--font-runtime /opt/qdata-fonts`。该运行时需由已校验、匹配发行版和 CPU 架构的官方 Fontconfig / DejaVu 包解出，结构包括：

仓库提供可复现的 Ubuntu 20.04 x86_64 字体准备工具，固定四个官方 Ubuntu 包的版本、大小及 SHA-256；仅提取到指定的新目录，保留原始包、许可证与来源清单，不调用 apt / dpkg 全局安装。联网 Linux 执行：

```bash
sudo python3 deploy/prepare-font-runtime.py --output /opt/qdata-fonts
```

离线主机将工具列出的四个官方 `.deb` 放入一个目录，仅使用这些已校验文件：

```bash
sudo python3 deploy/prepare-font-runtime.py --debs-dir /opt/font-debs --output /opt/qdata-fonts
sudo python3 deploy/install.py --install-dir /data/qdata --instance qdata --with-ai --font-runtime /opt/qdata-fonts --datax-archive /opt/packages/datax.tar.gz
```

`/opt/qdata-fonts/qdata-font-manifest.json` 记录官方下载地址、版本、SHA-256 与真实字体列表探测结果。工具拒绝覆盖已有目录；其 amd64 动态库要求兼容 glibc、libfreetype6 和 libexpat1，其他 CPU 架构使用第 2 节系统字体包。所有字体工具父目录须允许 qData 实例用户遍历。

```text
/opt/qdata-fonts/usr/bin/fc-list
/opt/qdata-fonts/usr/share/fonts/
/opt/qdata-fonts/usr/lib/x86_64-linux-gnu/libfontconfig.so.1
/opt/qdata-fonts/etc/fonts/
```

上述离线目录布局适用于 Ubuntu 20.04 x86_64；其他系统应使用对应运行时。安装器在本实例生成独立 `runtime/fonts.conf`、`state/` 字体缓存与字体 / 动态库环境，避免改写全局 OS 配置。完整新机可直接安装第 2 节系统字体包。

## 5. 安装新实例与首次登录

在目标 Linux 使用 root / sudo 执行：

```bash
python3 deploy/install.py --help
sudo env PATH="$PATH" JAVA_HOME="$JAVA_HOME" python3 deploy/install.py --java-home "$JAVA_HOME" --install-dir /data/qdata --instance qdata --with-ai
```

首次交互输入管理员登录密码（5–20 位），用户为 `admin`，可以使用原来熟悉的密码。登录仅需账号和密码，无图形验证码。密码 bcrypt 写入新 MySQL 空库；万能密码关闭。加密配置及指纹校验由安装器自动处理，文件保存在安装目录 `runtime/`，普通新装无需生成、复制或填写密钥。

登录页按照服务器返回的验证码开关展示验证码。启用时获取并填写当前图像结果后提交；后端通过 Redis 一次性校验验证码，失败后页面刷新图像。验证码成功生成、浏览器提交正确 / 错误验证码及过期 / 复用拒绝是独立检查，不能仅凭 `/captchaImage` 返回 200 判定登录校验已通过。

<details>
<summary>高级操作：无人值守安装时从受限文件读取密码</summary>

非交互方式先建立只有 root 可读的管理员密码文件：

```bash
umask 077
read -r -s -p '设置新 qData 管理员密码: ' QDATA_INSTALL_ADMIN_PASSWORD
printf '\n'
printf '%s' "$QDATA_INSTALL_ADMIN_PASSWORD" > /root/qdata-admin-password
unset QDATA_INSTALL_ADMIN_PASSWORD
sudo python3 deploy/install.py --install-dir /data/qdata --instance qdata --with-ai --admin-password-file /root/qdata-admin-password
```

密码文件与 `runtime/runtime.env` 按凭据管理，不能入 Git。

</details>

安装器按完整 `schema` → `reference` → `bootstrap` → `five-layers` 初始化，再设置管理员密码；新装不用重复运行全部 migration。五层初始化 SQL 是 **qData MySQL 目录数据**，Hive 物理库需要集群授权并在平台空间绑定。

### 新装数据范围

当前清洁初始化包含 203 张平台结构表。新装只保留 17 张表中的必要系统种子：一个 `admin`、管理员角色/部门/岗位及关系、一个空的默认项目、菜单、字典、内置稽查/清洗规则、敏感级别和五层目录。其他 186 张业务及运行记录表在服务启动前全部为零，包括连接、模型、批次、调度、资产、元数据、质量结果、Doris/API 配置、AI 模型/会话/消息和日志。

旧 `gw_b_stinfo`、`huiyuan_*`、`rep_demo_*` 共 14 张遗留示例表已从新装结构移除，旧自增起点也不导入。内置模板中的说明示例是规则使用帮助，保留其功能内容；历史操作人改为 `bootstrap`，创建时间使用本次安装时间。

安装器校验四份 SQL 的 SHA，并逐表检查种子允许表与精确行数。发现任何额外用户、项目或业务测试记录会停止安装并保留现场，不自动删数据。启动和登录会产生正常调度状态或审计记录，不属于预置测试数据。

`deploy/tests` 是可选验收工具，安装器不会调用。特别是 `run-ai-datasource-live-probe.py --allow-synthetic-mysql` 会在明确选中的测试实例创建并保留合成 MySQL 表/项目/连接；需要保持干净的正式实例时，不执行该工具。

| 默认服务 / 端口 | 用途 |
|---|---|
| 访问代理 `--web-port 8080` | 浏览器 / API 入口及 `/prod-ai/` AI 同源代理 |
| Java 主平台 `--backend-port 18080` | 仅 Linux 回环地址，由代理访问 |
| 质量 `--quality-port 8083` | 质量执行器 |
| AI `--ai-port 8087` | `--with-ai` 启用的 AI 服务 |
| MySQL 13306 / Redis 16379 | 本实例基础依赖，回环绑定 |
| RabbitMQ AMQP 25672 / Neo4j Bolt 17687 | 本实例消息与图数据库，回环绑定 |

systemd 名为 `qdata-web.service`、`qdata-quality.service`、`qdata-ai.service`，前缀来自 `--instance`。Docker 的 Nginx 使用 Linux host 网络代理回环 Java 服务。实际配置以生成文件和安装日志为准。

在已有 qData 的机器旁路验证新装，必须指定新目录、新实例及全部未占用端口，例如：

```bash
sudo python3 deploy/install.py --install-dir /data/qdata-new --instance qdata-new --web-port 28080 --backend-port 28081 --quality-port 28083 --ai-port 28087 --with-ai
```

基础依赖端口也需按帮助指定为未占用值。安装器拒绝复用非空目标库或现有实例，不能用清空旧数据来解决冲突。

连接已有 MySQL / Redis / RabbitMQ / Neo4j：

```bash
sudo python3 deploy/install.py --install-dir /data/qdata --instance qdata --with-ai --existing-dependencies --dependencies-file /root/qdata-dependencies.env
```

依赖文件使用 `KEY=value`（文件权限 600），必填键：

```text
MYSQL_HOST
MYSQL_PORT
MYSQL_ADMIN_USER
MYSQL_ADMIN_PASSWORD
REDIS_HOST
REDIS_PORT
REDIS_PASSWORD
RABBITMQ_HOST
RABBITMQ_PORT
RABBITMQ_USERNAME
RABBITMQ_PASSWORD
NEO4J_URI
NEO4J_USERNAME
NEO4J_PASSWORD
```

可指定 `MYSQL_DATABASE`、`MYSQL_APP_USER`、`MYSQL_APP_PASSWORD`；目标库必须全新空库，应用账号必须与初始化管理员分开且专属新实例。Redis / RabbitMQ / Neo4j 使用部署方授权的独立资源。

打开 `http://<新Linux地址>:8080`，用 `admin` 和自己设置的密码登录。先创建业务项目、部门、角色、普通用户与成员关系，再接已有集群。正常运行无需 Vite 开发服务器。

## 6. 配置已有集群与完整模块依赖

安装目录 `runtime/` 提供 `application-web.properties`、`application-quality.properties`、`application-ai.properties`、`external.properties`、`runtime.env`。外部覆盖配置写 `external.properties`，密钥凭据保持权限限制，修改后重启受影响实例。项目数据源的密码由平台页面登记。JAR 安装在 `lib/`；数据在 `data/`，上传与日志在 `upload/`、`logs/`。

### 6.1 网络方向

| 方向 | 用途 |
|---|---|
| 浏览器 → Linux 访问代理 | 页面与 API；常见 8080 或网关 HTTPS |
| qData → HiveServer2 / Kyuubi JDBC | Tez / Spark SQL 五层任务与查询 |
| qData → Hive Metastore / HDFS / YARN | Spark ETL 与文件读写 |
| YARN 容器 → Linux Spark driver | client 模式 driver / block-manager 回程 |
| qData → DolphinScheduler API | 项目、工作流、启动与状态查询 |
| DS Worker → qData 访问代理 | 五层原生 HTTP DAG 单步回调 |
| qData → Doris FE / BE | JDBC 与目标实际数据加载接口 |
| 主平台 → 质量 / AI | 同机回环或受控内网执行器 |

集群主机名必须能在新 Linux 解析。DS Worker / YARN 中的 `127.0.0.1` 是其自身，不能当作远程 qData 回调地址。具体端口从已有集群配置取得。

### 6.2 Hive 五层、Tez、Kyuubi / Spark

1. 建立项目和成员，在“数据连接”分别登记 HiveServer2 和 Kyuubi 数据源，测试连通性并授权给项目。
2. 在“数据建模 / 五层数仓”登记引擎与数仓空间，绑定同一 Hive catalog 中 `ods_iot_db`、`dwd_iot_db`、`dws_iot_db`、`dwt_iot_db`、`ads_iot_db`。
3. 五个名称要对应 **五个 Hive 物理数据库**，单库不同表前缀不满足目标。Ranger / HDFS 建表、写入、读取和元数据权限由集群管理员配置。
4. 创建批次时选择 HiveServer2 / Tez 或 Kyuubi / Spark，Kyuubi endpoint 需与本项目授权的登记地址一致。
5. 单模型手动执行与一般 Spark ETL 是独立入口；它们不能仅凭 Kyuubi 五库批次成功判定已验收。

已有 Hive 数据保留；测试使用新批次与新版本输出，不重跑旧清理脚本。Hive/HDFS 删除、清空和覆盖仅由用户手工执行。

### 6.3 DolphinScheduler 与 Quartz 可选

在外部配置填写本部署实际值：

```properties
ds.base_url=https://ds.example.com/dolphinscheduler
ds.token=<部署方DS Token>
warehouse.ds.project-code=<DS业务项目code>
warehouse.ds.health-url=http://qdata.internal.example:8080/actuator/health
ds.mc_url=http://qdata.internal.example:8080/mc/taskExecutor/runExecuteTask
ds.quality_url=http://qdata.internal.example:8083/quality/qualityTaskExecutor/runExecuteTask
```

以上仅示例格式。可在 `runtime.env` 设置安装器预留的 `QDATA_DS_BASE_URL`、`QDATA_DS_TOKEN`、`WAREHOUSE_DS_PROJECT_CODE`、`WAREHOUSE_DS_HEALTH_URL`。`warehouse.ds.health-url` 要以 `/actuator/health` 结尾；指定地址须从 DS Worker 实际可达。Token 需具备项目权限，Worker 要有 HTTP 插件。质量服务的网络端口需要通过防火墙仅允许可信 DS Worker / 运维来源。

批次可选 Quartz 或 DS。选择 DS 后平台冻结模型及依赖，生成原生 HTTP DAG，**每个节点回调 qData 执行一个受管步骤**，SQL 由步骤选用 HiveServer2 / Tez 或 Kyuubi / Spark。调度器与计算引擎选择相互独立。Quartz 使用本地调度驱动同一批次语义。

查看 DS 节点与 qData 账本成功状态，再独立查询 Hive；工作流创建成功或 HTTP 200 本身不足以完成数据验收。

### 6.4 ADS → Doris → OAuth API

1. 登记 Doris 数据源并授权本项目，设置五层服务发布目标数据库。
2. 从成功批次选择 ADS，创建发布版本；同步到 Doris 并核对行数、字段类型和值。
3. 发布 API，创建应用 / OAuth client，明确授权本项目本次 API；页面获取实际 URL、参数和 Token 获取方式。
4. 用部署方 client 凭据调用 API，对返回值与 Doris / Hive 做字段级比对。
5. 检查匿名、其他项目用户 / client 无权读取本项目服务。

Doris 是 ADS 服务副本。API、release、client 的 ID 由本部署生成，不能复制历史环境 ID；Hive ADS 保留。

### 6.5 DataX、本地 Spark、AI、血缘与外部集成

全部业务源码保留，下列功能需提供对应运行时：

| 功能 | 部署内容 |
|---|---|
| DataX 集成任务 | 安装器默认准备 [Alibaba DataX](https://github.com/alibaba/DataX)；需相应 reader / writer 插件与 Python；路径写入 `datax.*`，可配置已有 DataX |
| 本地 Spark ETL | 匹配 Spark client、Hadoop 配置及本次 `qdata-executor-etl.jar`；设置 `ds.spark.submit_path`、`ds.spark.master_url`、`ds.spark.main_jar`、`ds.spark.main_class`、`ds.spark.hadoop_conf_dir` |
| Spark client 回程 | 设置 `ds.spark.hadoop_user`、`ds.spark.driver_host`、`ds.spark.driver_bind_address`、`ds.spark.driver_port`、`ds.spark.driver_block_manager_port` 并开放回程端口 |
| 血缘 | Neo4j，安装器可创建；已有服务使用正确 `spring.data.neo4j.uri=bolt://...` 与部署方身份 |
| AI | `--with-ai` 启动独立 AI；在模型配置登记模型供应商、名称、地址与部署方 key，并测试模型调用 |
| SSO | 普通新装本地密码登录；需要 SSO 时配置 `oauth2.*` 与前端认证变量并重建 |
| 文件 / OSS | 默认本地目录；外部存储用 `dromara.x-file-storage.*` 填实际私有桶配置 |
| 审批 / 报表外部入口 | 配置部署方流程 / 报表服务与 `flyflow.url`、`VITE_APP_FLOW_API`、`VITE_RP_VIEW_URL` |

安装器默认调用 `prepare-datax.sh`，安装到实例 `tools/datax/`。离线同时提供 `--datax-archive /opt/packages/datax.tar.gz --datax-mysql-driver /opt/packages/mysql-connector-j-9.7.0.jar`，可追加 `--datax-sha256 <已审核的SHA256>`；已安装发行物用 `--datax-home /opt/qdata-datax`。`--skip-datax` 是显式精简选项，此时 DataX 任务尚不具备运行条件。

也可独立准备 DataX（在 Linux 执行）：

```bash
bash deploy/prepare-datax.sh --dest /opt/qdata-datax
# 离线：使用本地发行包和与主项目一致的 JDBC 驱动
bash deploy/prepare-datax.sh --dest /opt/qdata-datax-offline --archive /opt/packages/datax.tar.gz --mysql-driver-jar /opt/packages/mysql-connector-j-9.7.0.jar
```

准备脚本将 Connector/J 9.7.0 安装到 `rdbmsreader`、`rdbmswriter` 各自的 `libs/` 并在两份 `plugin.json` 注册 `com.mysql.cj.jdbc.Driver`。驱动固定 SHA-256 为 `0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44`；离线缺少驱动或摘要不一致会拒绝发布新工具目录。已有目录不会被覆盖；现场 DataX 的升级由部署负责人备份后另行执行。

Spark 的 CSV/Excel 解析会将派生文件新增至 HDFS `/tmp/etl/`。部署须在私有 `runtime.env` 设置 `QDATA_HDFS_URL=hdfs://实际NameNode:端口` 或 HA nameservice，并用 `--hadoop-conf-dir` 提供匹配的 `core-site.xml`、`hdfs-site.xml`。留空地址时解析器读取该配置目录的 `fs.defaultFS`；没有有效 HDFS 配置时，解析接口明确拒绝且保留原始上传文件。HA 地址还需匹配 nameservice、NameNode RPC 地址及 failover provider 配置，且部署机能访问 NameNode/DataNode。地址由环境负责人核实，不把 `hdfs://` 当作可用默认值。

数仓执行环境登记支持 HiveServer2 的单列 `SET hive.metastore.uris` 结果，也支持 Spark/Kyuubi 的键、值两列结果；空值或未定义身份仍拒绝登记。登记成功后仍需检查项目分配及实际授权库，不能仅凭 JDBC 连通性视为五库链通过。

DataX 发行物约 1.56 GiB，需另预留解压空间。工具脚本核验固定官方 HTTPS 包的已记录 SHA-256 与 Content-MD5；其他已审核发行物通过 `--sha256` 指定校验值。拒绝覆盖已有目录并生成安装清单。独立工具目录需给予实例用户读 / 执行权限，`log/`、`log_perf/` 和 `tmp/` 需要写权限；采用 `--datax-archive` 解压到本实例 `tools/datax/` 时由安装器设置这些目录权限。自有 DataX 示例配置：

```properties
datax.home=/opt/qdata-datax
datax.python-command=python3
datax.datax-py-path=/opt/qdata-datax/bin/datax.py
datax.lib-dir=/opt/qdata-datax/lib
datax.job-dir=/data/qdata/upload/datax/job
ds.spark.main_jar=file:/data/qdata/lib/qdata-executor-etl.jar
ds.spark.main_class=tech.qiantong.qdata.spark.etl.EtlApplication
```

首次安装也可以指定 `--datax-home /opt/qdata-datax --spark-home /opt/spark --hadoop-conf-dir /etc/hadoop/conf`，安装器写入对应路径和 JDK 21 环境。本地 Spark runner 使用 Linux 上本次 ETL JAR；若由 DS Worker 提交 Spark 任务，`QDATA_DS_ETL_JAR_URI` 应设置为 **Worker 可访问** 的资源 URI，不能把 qData Linux 本地路径视为 Worker 文件。

DataX / Spark 是外部引擎发行物；Spark client 需匹配源码 Spark `4.0.2` 依赖，已有集群不同版本时先验证兼容性。Kyuubi 五库批次不经过本地 Spark ETL runner。视频厂家对接类当前保留未实现的上游入口，其返回空值；部署不能使该入口自动变成完成的视频服务。

独立 AI 服务的跨模块代理 `DualServiceImpl` 仍有上游占位接口，AI 自动调用建模、数据源或调度等能力需单独完成实现与验收。AI JAR 编译 / 服务启动不代表这些自动操作已经可用；正常数仓、数据开发和 DS 业务通过主平台已实现的入口操作。

独立 AI 的会话详情与消息操作依据当前登录身份及会话归属控制，普通用户可以访问自己创建的会话。

原前端流程 / 报表 `localhost` 指浏览器本机，要改为实际可达地址。前端变量用 `.env.production.example` 或 `build.sh --env-file` 准备后重建，`VITE_*` 会进入浏览器；后端私钥不填到前端变量。SSO、OSS、AI 模型凭据由部署方提供。

## 7. 服务管理与安全配置

```bash
sudo systemctl status qdata-web qdata-quality qdata-ai --no-pager
sudo journalctl -u qdata-web -n 100 --no-pager
sudo journalctl -u qdata-quality -n 100 --no-pager
sudo journalctl -u qdata-ai -n 100 --no-pager
curl -f http://127.0.0.1:8080/actuator/health
sudo systemctl restart qdata-web qdata-quality qdata-ai
```

多实例替换服务名前缀。代理默认提供同源 `/prod-ai/` 路由；不开 AI 时返回不可用状态。HTTPS 证书 / 域名由部署方网关配置。主 Java backend 和 AI 限制回环；质量端口只允许可信执行来源。Docker gateway 用 `docker ps` 查看，原生 Nginx fallback 则查看本实例 `qdata-gateway.service`；不重启已有系统默认 Nginx。

- `QDATA_AES_KEY` 为 16 字节 UTF-8，前后端同值。新装生成，升级保存原值避免历史密文不可解。
- 后端 RSA 与 Token 密钥保存在受限部署文件中，升级沿用原值。浏览器“记住密码”的独立 RSA 兼容对进入浏览器产物，只是浏览器端可逆处理，不能作为后端保密机制；后端私钥不复用该对。
- MySQL、上传文件、Neo4j 图数据、Redis / RabbitMQ 持久化数据与运行配置保留备份。
- Hive/HDFS 删除、清空、覆盖只能由用户手工操作，包括测试表。安装或验收冲突时使用新版本，不能清理旧表作为前置操作。

## 8. 更新与回退

`install.py` 是新空库安装入口，已有实例不能再次执行初始化 SQL。

1. 记录 commit、成套 JAR 的 SHA-256、配置和任务状态，维护窗口暂停受影响调度。
2. 备份主平台 / 质量 / AI / ETL 产物、配置、MySQL 与上传文件，保存旧数据库和密钥。
3. 独立源码目录检出新提交并构建。依据当前数据库版本审核尚未应用的 migration，不重复运行全部历史迁移。
4. 停止本实例受影响服务，按其服务定义替换核验的成套产物；同步外部 Spark 引用的 ETL 版本。启动，检查日志和 HTTP。
5. Chrome 登录核对权限与模块，以新批次完成五库至 Doris / API 验收，保留所有 Hive 测试对象。
6. 回退先恢复兼容旧 JAR 与配置。数据库变更不能自动反向删表 / 字段或覆盖数据，先审核兼容性；回退不构成 Hive/HDFS 清空授权。

## 9. 验收范围与记录

新机器至少确认：

1. JDK 21 全部模块源码构建、完整空库初始化、真实 admin 密码、Chrome 主要菜单。
2. 项目成员与普通角色权限生效，其他项目无权查看本项目资产和服务。
3. 水 / 空气 / 土壤新批次完成五个 Hive 物理库加工与独立结果对账。
4. 实际启用的 Quartz / DS、Tez / Kyuubi-Spark 都有调度与引擎证据；DS 节点及 Worker 回程正常。
5. 同批元数据、资产、质量、ADS → Doris、OAuth API 授权与字段级比对。
6. 启用的 DataX、本地 Spark、AI、Neo4j、SSO / OSS 等功能完成各自真实验证。

历史生产有 DS 原生 20 节点五层与 Kyuubi/Spark 证据，也有另一 Quartz / Tez 批次的 Doris / API 字段对账。历史分项验证不代替本提交的新装验收，完整代码范围也不等于全部外部集成已通过。

DECIMAL 精度 / 舍入和水空气土壤 / 设备正式质量规则仍需业务签版。新机安装和本提交验收结果由实际构建、空库、页面与集群记录确认；本教程不将待执行项写为成功。
