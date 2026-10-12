# qData 数据中台 · 五库业务版

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
| `sql/mysql/migration` | 增量数据库迁移 |

工程声明 JDK 21、Spring Boot 3.5.16、Spark 4.0.2、Hadoop 3.4.3、Hive JDBC 4.2.0。以上是源码依赖版本，集群兼容性需在目标环境确认。

## 构建与配置

运行配置使用 `.example.yml` 和前端 `.env.*.example` 模板，按需复制为本地配置后填写目标环境参数。私钥、运行口令、部署包、数据库备份与历史运行记录由部署环境单独管理。

详细步骤见 [部署与配置说明](docs/DEPLOYMENT.md)。前端依赖使用 `package-lock.json` 锁定；后端使用 Maven 多模块构建。

## 验收状态

历史样例已经验证 DS 原生 20 节点五库加工与 Kyuubi/Spark 查询，也有独立 Quartz/Tez 批次完成 Hive→Doris→OAuth API 字段级对账。两笔批次的证据分别保留。当前源码可能包含尚未生产发布的候选改动；本次源码提交不构成新的生产部署。

同一 DS+Spark 批次的完整下游发布验收、业务 DECIMAL 精度/舍入及水气土正式质量规则签版仍需完成。非核心容灾与外部系统验收按项目优先级继续跟进。

项目操作约束见 [AGENTS.md](AGENTS.md)：Hive/HDFS 数据删除、清空和覆盖仅由用户手工操作，测试数据与备份应保留。

上游工程来源见 `qdata-work/README.zh-CN.md`，第三方组件许可证保留在各自目录。
