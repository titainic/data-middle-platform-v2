# qData 数据中台 · Linux / JDK 21 源码部署版

基于 qData 1.6.1 的持续改造源码，围绕 Hive 五层数仓与 Doris/API 数据服务构建业务链：

```text
数据接入 → ods_iot_db → dwd_iot_db → dws_iot_db → dwt_iot_db → ads_iot_db → Doris → API
```

五层分别是 Hive 物理数据库。批次创建支持选择 Quartz 或 DolphinScheduler，计算入口支持 HiveServer2/Tez 或 Kyuubi/Spark。平台还包含建模、开发、元数据、资产、治理、质量、项目权限及数据服务模块。

## 代码目录

实际工程位于 `qdata-work/`：

| 目录 | 内容 |
|---|---|
| `qdata-server` | 后端主服务，同时承载前端静态资源 |
| `qdata-ui` | Vue 前端 |
| `qdata-service-quality` | 质量执行器 |
| `qdata-executor-etl` | Spark ETL 执行器 |
| `qdata-module-*`、`qdata-api-ds` | 业务模块与调度接口 |
| `qdata-framework` | 公共组件 |
| `qdata-service-ai` | 独立 AI 服务及核心模块 |
| `sql/mysql/migration` | 已有部署的增量数据库迁移 |
| 根目录 `deploy/database` | 完整新装表结构、菜单字典、管理员基础数据与五层目录 |
| 根目录 `deploy/build.sh`、`deploy/install.py` | 源码构建与 Linux 安装 |

工程声明 JDK 21、Spring Boot 3.5.16、Spark 4.0.2、Hadoop 3.4.3、Hive JDBC 4.2.0。以上是源码依赖版本，集群兼容性需在目标环境确认。

## 全新 Linux 部署

本仓库用于在一台全新 Linux 机器安装 qData，连接已有的 Hive / Kyuubi / Spark / YARN、Doris 和 DolphinScheduler 集群。主平台、质量执行器、AI 使用 JDK 21 + systemd；MySQL、Redis、RabbitMQ、Neo4j 与访问代理可在目标 Linux 使用 Docker Engine 安装，也可连接已有依赖。

```bash
git clone https://github.com/titainic/data-middle-platform-v2.git
cd data-middle-platform-v2
sudo env PATH="$PATH" JAVA_HOME="$JAVA_HOME" python3 deploy/install.py --java-home "$JAVA_HOME" --install-dir /data/qdata --instance qdata --with-ai
```

安装器生成实例密钥并从源码构建，交互设置管理员的真实密码。运行配置和密钥保存在安装目录受限文件中。随后配置已有集群、项目权限和数据源，再进行五层业务验证。先按 [完整 Linux 部署教程](docs/DEPLOYMENT.md) 准备工具和依赖；教程包含独立构建、离线部署、端口回程、全部模块所需外部服务及更新方法。

前端依赖由 `package-lock.json` 锁定，所有 `tech.qiantong` 模块从本仓库源码构建。仓库保留全部业务源码和公开模板；部署方密钥、数据、运行配置及备份单独管理。

## 验收状态

历史样例已经验证 DS 原生 20 节点五库加工与 Kyuubi/Spark 查询，也有独立 Quartz/Tez 批次完成 Hive→Doris→OAuth API 字段级对账。两笔批次的证据分别保留。当前源码可能包含尚未生产发布的候选改动；本次源码提交不构成新的生产部署。

同一 DS+Spark 批次的完整下游发布验收、业务 DECIMAL 精度/舍入及水气土正式质量规则签版仍需完成。非核心容灾与外部系统验收按项目优先级继续跟进。

完整源码、安装成功和业务验收分别记录，范围见 [部署教程的验收说明](docs/DEPLOYMENT.md#9-验收范围与记录)。AI 推理需要模型服务；DataX / 本地 Spark 任务需要对应引擎；审批等外部集成需要配置相应服务。

项目操作约束见 [AGENTS.md](AGENTS.md)：Hive/HDFS 数据删除、清空和覆盖仅由用户手工操作，测试数据与备份应保留。

上游工程来源见 `qdata-work/README.zh-CN.md`，第三方组件许可证保留在各自目录。
