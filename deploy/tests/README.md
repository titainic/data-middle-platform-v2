# AI 身份与会话归属验证

在 Linux 使用 JDK 21，对本次源码生成的独立 AI JAR 执行：

```bash
export JAVA_HOME=/opt/jdk-21
bash deploy/tests/run-ai-security-probe.sh deploy/artifacts/qdata-ai-server.jar
```

程序启动临时回环地址的真实 Tomcat HTTP 服务，使用实际 SecurityFilterChain、JWT filter、framework TokenService 和 AI Controller。会话缓存用内存 Redis 适配器，数据库 / LLM 边界使用有调用计数的测试对象。

覆盖未登录、无效及撤销 Token 拒绝；主平台 Token 协议访问 AI；非归属发送、读取、删除、混合批删、清空和导出拒绝；合法创建 / 修改、消息发送使用当前登录身份，以及核心 Service 不能绕过会话归属检查。测试身份包含两个普通用户与管理员，管理员也遵循当前 AI 会话归属策略。

2026-10-12 Linux JDK 21 实测输出：`AI_SECURITY_HTTP_PROBE_PASS assertions=55`。这是鉴权 / 归属的 55 项断言，包括没有任何菜单权限的普通用户访问自身会话成功、其他会话拒绝及匿名拒绝。真实 Redis、用户配置的模型服务、集群业务与新机器的安装验收按 [部署教程](../../docs/DEPLOYMENT.md#9-验收范围与记录) 独立执行。

测试只操作自己的临时目录和测试对象。临时服务随程序结束停止，不访问实际 MySQL、Hive、HDFS 或外部 LLM。脚本不会停止已有平台服务。

## 真实 Redis / MySQL 数据源与项目权限

`AiDatasourceLiveProbe.java` 和 `run-ai-datasource-live-probe.py` 用于已完成安装的 Linux 测试实例。它们启动实际完整的独立 AI Spring 上下文，使用该实例的配置、真实 Redis 会话及 JDBC 数据库；临时 HTTP 端口随机绑定在回环地址，进程结束后关闭。

先用该测试实例的主平台正常验证码登录取得管理员和两个普通用户的 Token，写入仅操作人可读的 JSON 文件（权限 `600`）。字段为 `adminToken`、`memberToken` 和 `nonmemberToken`；两个普通用户必须不同且不是管理员。Token 值不可写进仓库、聊天或公开日志。运行器读取已安装实例的 `runtime/runtime.env`（权限 `600` 或服务组可读的 `640`），并要求测试 JAR 与该实例实际安装的 AI JAR SHA-256 一致。

```bash
python3 deploy/tests/run-ai-datasource-live-probe.py \
  --ai-jar deploy/artifacts/qdata-ai-server.jar \
  --install-dir /data/qdata-test \
  --token-file /root/qdata-test-tokens.json \
  --java-home /opt/jdk-21 \
  --source-commit "$(git rev-parse HEAD)" \
  --evidence-file /root/qdata-ai-live-verification.json \
  --allow-synthetic-mysql
```

这项验证会新增并保留一个 MySQL 合成表、一条记录、一个项目、一个数据源及其成员关联；不会清理或覆盖既有数据。测试普通成员是新项目成员，数据源创建人是管理员，因此成功不能依赖创建人或管理员绕过权限。验证成员读取实际源和两列 JDBC 元数据，非成员读取源 / 列均被拒绝。所有 Hive / HDFS、Doris 和外部 LLM 操作均不执行。

结果只输出 PASS / FAIL、收据位置和保留对象编号；完整运行日志写入同名 `.log`，权限 `600`。收据包含代码标记、JAR SHA-256、真实基础设施范围和保留对象信息，不包含 Token 或密码。请使用新的收据文件名，历史证据不会被覆盖。运行前确认 `--install-dir` 为允许新增验收数据的测试实例。

这项实际数据源验证与上方 55 项 HTTP fixture 检查分开，也不代替安装后主平台登录、普通用户会话 HTTP 正反、退出后共享会话失效及业务模型调用验收。
