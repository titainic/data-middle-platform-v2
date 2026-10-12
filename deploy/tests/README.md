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
