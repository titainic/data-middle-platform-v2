# BIN-123 ETL 创建类目归属项目隔离

作者：全栈研发工程师-Forge；日期：2026-10-17（Asia/Shanghai）。来源：BIN-123 / BIN-113，缺陷 BIN-120-D2、口径校准 BIN-120-D1。基线：main `ddcc77defe3cfb7c1aabb7b60d0ba25674eba446`，保留 BIN-116/BIN-117 已合入的项目隔离语义。

## 根因与变更

`POST /dpp/etlTask/createEtlTaskFront` 先调用 `DevelopmentResourceAccess.request`，原类目查询仅按 code+del_flag 获取全项目 owners，要求恰好一条。project5/6 合法各有 A01 时得到两条，业务服务尚未执行便误拒。集成 type 1 和开发 type 3 都受影响。

- 类目查询增加绑定参数 `project_id=?`，在目标项目内检查恰好一条有效记录，仍保留拥有者一致性校验。查询表名仅由已有 type 白名单分支选择。外项目独有、目标项目逻辑删除、目标项目重复编码、空/无权项目及项目 ID/code 不一致仍拒绝。
- 不改类目分配、计数、树、列表或落库服务；`AttCatService` 继续使用 BIN-117 的 code+projectId 查 ID，创建任务的 cat_id 属于本项目。
- 子流程 `definitions` 原调用 `taskCode(subtask,null)` 丢掉请求项目，历史跨项目同任务码会误拒。内部解析现在接收已授权 request.projectId，并按 code+project_id+del_flag 查询，随后仍通过 `task(id)` 核实项目状态/归属/读取权限。拒绝外项目独有、删除、同项目歧义及不可读子流程。
- 原公开 `taskCode(code,projectCode)` 签名和行为保持兼容。有 projectCode 按其限定；完全没有项目上下文时仅接受全局恰好一条，歧义安全拒绝。当前源码唯一实际消费点是 `definitions`，已传入项目 ID；没有遗留无上下文消费点。省略 projectCode 的内部请求同样按 projectId 解析；这不豁免 HTTP DTO 的必填校验。

无数据库迁移、运行依赖升级、公共异常框架变更、已有编码或业务数据改写。共享工作区并发持有，所有开发在 `/private/tmp/bin123-324d197e/repo` 独立副本完成。

## definitions 节点编码审计：保留全局保护

节点 code 与项目目录 code 的命名空间不同。当前本地节点发码经 `DppEtlTaskServiceImpl.getNodeUniqueKey` → `DppTaskQuartzService.genCode` → `QuartzSchedulerAdapterImpl.generateTaskCode`，使用 Redis 全局 key `qdata:quartz:task-code`；外部调度分支通过 DolphinScheduler genCode。本结论以本仓代码消费语义为依据，不声称验证了远端调度器。

现有 `DppEtlNodeServiceImpl.getNodeIdByNodeCode/getNodeByNodeCode` 仅按 code 取节点，`DppEtlNodeMapper.xml#removeOldDppEtlNode` 也仅按 code 移除旧版本节点（本轮未调用）。因此不能只将归属校验改成 project_id 过滤并允许跨项目节点同码，否则会放行无法安全隔离的节点复用。保留当前表+历史表 UNION 的全局保护，包括逻辑删除历史；本项目已有节点/新编码可用，其他项目占用（包括双方均占用）仍拒绝。

结论：这处不是 BIN-116/117 的类目消费者遗漏，无需在本 PR 拆出缺陷任务。将来若产品要求节点跨项目同码，需要技术总负责人-Atlas另定覆盖完整消费链路的方案，不能单点放宽本校验。新增离线用例固定以上边界。

## D1 业务码口径：非缺陷

`AttTaskCatServiceImpl` 的同父重名、非法名称、非法排序、父节点非法、缺项目等均抛 `ServiceException`，没有分别指定 400/409。`GlobalExceptionHandler.handleServiceException` 在 code 为空时调用 `AjaxResult.error(message)`，默认业务码为 500；该处理方法未设置 HTTP 500。

本轮新增用例实际执行类目新增、同父重名和非法名称拒绝，再调用现有异常处理方法，确认两者 code 均为空、处理结果业务 code=500、信息非空、重复新增 0 行。已有 8 路同名并发用例继续验证只落 1 条。与任务已确定的验收口径一致：明确业务拒绝不等于未处理异常或服务端崩溃，本棒不修改错误码或公共异常框架。

## 自测方法与边界

`DevelopmentCategoryAccessTest` 使用真实控制器方法 → `DevelopmentResourceAccess` / JDBC `ProjectPermissionService` → `DppEtlTaskServiceImpl` / `AttCatServiceImpl` / `DppEtlSchedulerServiceImpl` → MyBatis SQL，进程内 H2 MySQL 模式存储。测试中的用户 principal 为合成身份，角色菜单关系由真实权限 SQL 读取；成员可见项目使用固定夹具。本地 `AtomicLong` 仅替代 Redis 发码，不证明 Redis/多实例发码行为。未注入 `DevelopmentFileAccessService`，文件访问不在本棒验证范围。

每次请求核对业务 code=200、任务实际新增行数、项目/type/cat_id、调度行和响应编码；新项目空任务表、跨项目 8 路并发、旧 bin11cat、合成 40 条规则及前述拒绝场景均有断言。控制器通过 Java 直接调用，没有启动 HTTP、Bean Validation 或安全过滤器。接口原有 `@ResponseStatus(CREATED)` 即 HTTP 201，本任务所谓 200 是业务 code。

相邻回归包含 `CategoryProjectIsolationTest`、`TaskCategoryCodeAllocationTest` 和 `CleanCategoryCodeAllocationTest`，保留同父重名、空项目与旧编码/并发隔离验证。它们的逻辑删除仅操作进程内 H2 合成表，不连接业务数据或 Hive/HDFS。

H2 ≠ MySQL/95 运行验收；不代表真实 MySQL 隔离级别/锁、多实例、浏览器登录/权限过滤器、Redis、DolphinScheduler、DataX/Spark 或集群验证。合成 40 条规则未变不等于核验 95 真实 40 条指纹。

实际结果：JDK 21.0.10，2026-10-17 02:52:21 +08:00 完成 30 个相关模块离线 package，BUILD SUCCESS；DevelopmentCategoryAccessTest 15/15、CategoryProjectIsolationTest 31/31、TaskCategoryCodeAllocationTest 16/16、CleanCategoryCodeAllocationTest 20/20，合计 82/82，0 失败/异常/跳过。D1 用例经过现有异常处理器产生两条预期 ERROR 级业务拒绝日志，JUnit 无异常失败；没有修改日志或异常处理框架。既有 Scala 版本、shade module-info 和 MapStruct 等警告保留。未运行完整 qdata-server/AI 构建或前端构建。完整命令、JUnit XML 与日志保存在任务证据包。

```sh
# 工作目录 qdata-work；使用既有 JDK 21 和依赖缓存，独立 java.io.tmpdir
mvn -o -s /private/tmp/bin98-maven-settings.xml \
  -Dmaven.repo.local=/private/tmp/qdata-bin89-maven \
  -pl qdata-module-dpp/qdata-module-dpp-biz -am \
  -Dtest=DevelopmentCategoryAccessTest,CategoryProjectIsolationTest,TaskCategoryCodeAllocationTest,CleanCategoryCodeAllocationTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
```

## 独立验收交接

回交技术总负责人-Atlas审查候选提交/PR，再由其安排测试与部署工程师-Sentinel在下一批 95 授权范围独立验证；本棒不直接派环境任务。

1. project5/6 各有 A01；分别用合法责任人及 2099 周期创建 type 1/3。每请求业务 code=200、任务新增 1 行、cat_id/project_id 属于请求项目，另一项目原行不变。保留 HTTP 状态与业务码的区别。
2. 核对同父重名（含 8 路并发）、空项目、跨项目并发及 ID/code 不一致或无写权限拒绝；不能用本地序列夹具替代实际 Redis/MySQL 验证。
3. 只读核对既有 bin11cat、40 条规则、9 条保护任务及 2099 调度；不为测试改写它们。
4. 节点编码跨项目占用仍拒绝；本项目子流程解析正确，外项目独有、已删除和无读取权限仍拒绝。不要为构造异常场景改写正式节点编码。
5. Hive/HDFS 零删除/清空/覆盖；插件/驱动授权仍独立待定。本棒没有登录95、部署、重启、权限修改或业务数据操作。

版本规划管理工程师-Roadmap依据实际提交及后续审查/验收登记；本棒的代码/构建交付不等于合入、部署、正式发布或业务验收。
