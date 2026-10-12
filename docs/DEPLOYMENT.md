# 部署与配置说明

## 准备环境

准备 JDK 21、Maven、Node.js 22 与 npm，以及 MySQL、Redis、RabbitMQ。数据计算需要能访问 HiveServer2，使用 Spark 时配置 Kyuubi 与 YARN；DS 调度需要可用的 DolphinScheduler 项目和 Worker。数据服务需要 Doris。

本仓库包含源码与增量迁移。历史离线包、生产配置和已有数据库全备保存在部署环境。全新安装的完整初始化库需由项目维护者提供与源码匹配的材料；`sql/mysql/migration` 不等同于完整初始化库。已有部署按实际数据库版本选取尚未应用的迁移，逐项审核并先备份，不重复运行全部历史迁移。

## 准备本地配置

1. 将各服务和模块 `src/main/resources/application*.example.yml` 复制为相同名称去掉 `.example` 的 `.yml` 文件。例如 `application.example.yml` → `application.yml`、`application-prod.example.yml` → `application-prod.yml`。
2. 填写数据库、Redis、RabbitMQ、Hive/Kyuubi、Doris、DS 回调与 Spark 网络参数。模板中的占位值需要替换；不把本机填写后的配置提交到 Git。
3. 前端将 `.env.production.example` 复制为 `.env.production`；本地开发使用 `.env.development.example`，按目标环境设置参数。
4. 需要 RSA 功能时生成部署专用 RSA 密钥对，并按现有类路径读取方式配置 `private_key.pem` 与 `public_key.pem`。私钥只在受限部署环境保存。已有环境保持现有密钥，直接更换密钥可能导致历史密文不可解。
5. 后端 `QDATA_AES_KEY`（或 Java 系统属性 `qdata.aes.key`）设置为 16 字节 UTF-8 密钥，与前端 `VITE_APP_AES_KEY` 保持一致。已有环境必须沿用原密钥，避免历史密文不可解；原密钥保存在部署方受保护的本地配置中。
6. 前端 RSA 工具通过 `VITE_APP_RSA_PUBLIC_KEY` 与 `VITE_APP_RSA_PRIVATE_KEY` 读取旧功能所需配置。前端环境变量会进入浏览器产物，只适合兼容既有浏览器功能，不承担生产机密保护；独立部署的后端 PEM 私钥不要放进前端变量。正式安全改造需另行处理该旧功能。

## 构建

在项目根目录执行：

```bash
cd qdata-work/qdata-ui
npm ci
npm run build:prod
```

把本次前端 `dist/` 文件复制到主服务的静态资源打包目录，随后在 `qdata-work/` 构建：

```bash
mvn -pl qdata-server,qdata-service-quality,qdata-executor-etl -am -Dmaven.test.skip=true package
```

构建命令跳过测试；是否满足业务验收需另行确认。离线机器使用项目已有、确认完整的工具链和 Maven/npm 缓存，先在隔离源码目录构建，不在运行中的生产目录直接重建 JAR。

## 更新现有部署

既有 Linux 部署使用 `/data/qdata`，由 `qdata-server.service`、`qdata-quality.service` 管理 Java 服务，MySQL/Redis/RabbitMQ 使用容器并持久化数据。此路径是项目历史部署约定；新主机可以另设安装目录。

1. 核对运行版本和任务状态，暂停受影响任务，备份配置、数据库及成套主服务/质量/ETL JAR。
2. 保留 `runtime`、`runtime-data`、上传文件和历史备份，审核本次增量迁移及回退兼容性。
3. 停止受影响服务，更新已核验的产物与所需配置，按既有服务定义启动。
4. 检查 HTTP、登录、项目权限、任务日志，再核对五库物理表与数据。
5. 对一个新批次做 Chrome 页面操作及 Hive→Doris→API 字段比对。测试数据保留，Hive/HDFS 的删除或覆盖仅由用户手工执行。

主服务常用端口 8080，质量执行器 8083 应限制在同机回环/可信网络。实际端口、挂载、配置加载和依赖镜像以目标环境为准。
