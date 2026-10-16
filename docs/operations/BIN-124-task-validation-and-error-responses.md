# BIN-124 任务创建校验、异常响应与同类审计

作者：全栈研发工程师-Forge；2026-10-17，Asia/Shanghai。来源：[BIN-124](http://localhost:3100/BIN/issues/BIN-124)，父任务 [BIN-113](http://localhost:3100/BIN/issues/BIN-113)，缺陷 BIN-120-D3 / ADJ-01。基线 `ddcc77defe3cfb7c1aabb7b60d0ba25674eba446`，独立副本 `/private/tmp/bin124-9f7afabe/repo`。共享工作区的其他修改保持原样。

## 授权与协调

技术总负责人-Atlas 派单仅允许代码、本地构建和离线验证，不包含95登录、部署、重启、生产、业务数据操作或插件/驱动变更。2026-10-17启动评论 `c5e4713d-5973-4c2a-8361-a4ab792859e1` 盘点；技术总负责人-Atlas 以评论 `66057d19-250c-403a-b13e-8340803f2c04` 有条件确认公共处理器的影响范围：保留成功响应、业务码/HTTP约定、鉴权和项目隔离，日志可诊断，覆盖离线HTTP与轻量请求。Hive/HDFS始终零删除、零清空、零覆盖；未触碰实际bin11cat、40规则、9保护任务或2099调度。修改可通过后续独立revert提交撤回；本棒未执行环境回滚。

## 根因与实现

`DppEtlTaskController`已有`@Valid`，但`DppEtlNewNodeSaveReqVO`原仅有projectCode的`@NotNull`及少量长度约束，personCharge/name/projectId/executionType均可缺失。MyBatis插入在MySQL NOT NULL约束失败，`GlobalExceptionHandler`的RuntimeException/Exception分支直接返回`getMessage()`，从而泄露SQLException、Mapper和完整INSERT。

新增DTO内`TaskSave extends Default`校验组，仅以下6个完整任务写接口使用：

| HTTP入口（均POST，前缀/dpp/etlTask） | 要求 |
|---|---|
| createEtlTaskFront | 前置草稿创建 |
| createEtlTask | 集成任务保存 |
| createProcessDefinitionEx | 开发/作业画布保存 |
| createEtlTaskFrontPostposition | 草稿后置完整保存 |
| updateEtlTask | 集成任务完整修改 |
| updateProcessDefinition | 开发/作业完整修改 |

必填：personCharge（责任人）、name、projectId、projectCode、executionType、crontab。最后一项来自本仓`dpp_etl_scheduler.cron_expression NOT NULL`及创建草稿必建调度行的实际链路；遗漏它也会落入数据库约束。文本拒绝null、空串和空白；长字段按本仓schema收紧：责任人/联系电话/类目码/执行策略/调度器/执行器32、名称128、类型10、调度表达式255。保留Default组的原projectCode和description约束。type省略仍由控制器默认1，code/status/调度时间等仍由服务端生成。

catCode数据库允许NULL，当前API支持无类目的根任务，本次只限长度，不把前端选类目习惯新增为API必填；scheduler/actuator在schema可NULL，本棒也不新增必填。执行策略合法值域与cron语法仍沿用已有业务检查，本次不建立新调度策略。

共用DTO的copyCreateEtl、updateReleaseTask、updateReleaseJobTask、updateReleaseSchedule、releaseTaskCrontab继续Default组（原projectCode必填）；它们从既有任务加载信息，不能要求重新提交责任人/名称/cron。getNodeUniqueKey为GET，未新增整单校验。Java直接调用服务不触发MVC校验；本次修复边界是HTTP入口，不冒称所有内部调用都获得校验。

公共处理器只调整异常信息组装/输出：未知RuntimeException/Exception始终通用提示；ServiceException与既有IllegalArgumentException输入拒绝保留可读文案，遇SQL/JDBC/Mapper/堆栈特征或数据库cause则通用提示。保留原业务code；无code仍500，鉴权403、依赖不可用HTTP/业务503不变。校验错误返回约束文案；JSON反序列化、查询参数转换/缺失有可读提示，不回显用户原值和Java类型。校验列表取首条且提供空列表兜底，避免只有对象级错误时再抛NPE。

完整异常仍进入服务端既有日志，日志模板不再依赖可能缺失的翻译资源；首轮离线HTTP验证发现该资源缺失会导致异常处理自身抛错，因此一起修复。没有向响应新增detail、stackTrace或异常对象。业务消息的特征过滤仅作为历史包装错误的防护，不是对全仓所有手工响应的安全保证。

## 同类审计结论

全仓静态清单见[BIN-124-write-endpoints.csv](BIN-124-write-endpoints.csv)：314个POST/PUT/PATCH普通具名DTO请求入口，251处Default校验、6处TaskSave、57处无参数级Valid/Validated，253条对应DTO没有NotNull/NotBlank/NotEmpty。数字是扫描线索，**不是253个已证实缺陷**：服务层可补字段/做校验，部分接口只接收ID。扫描不穷尽List/Map/数组、multipart/Excel导入、SSE和函数路由；这些通过下面人工审查补充关键路径。清单记录每项控制器、DTO、方法和约束概况，便于后续逐项裁定。

| 入口/范围 | 实际检查与结论 | 本棒处理/后续 |
|---|---|---|
| 上述6个任务写入口 | 共用DTO存在确定的必填缺口，原可直达约束异常 | 已补TaskSave；缺参0发码/0任务/0调度，合法草稿落库用离线MVC+H2验证 |
| 复制、上下线、调度及GET取码 | 复制从源任务取字段，发布/调度是局部操作 | 保留原参数要求；5个POST轻量接口离线MVC回归，业务服务用替身，不证明真实发布/复制执行成功 |
| POST/PUT /dpp/etlSqlTemp | DTO仅Size，name/type/content在schema非空；createDppEtlSqlTemp直接BeanUtils→Mapper.insert | 存在同形缺参到数据库路径；未加本任务字段语义，公共处理器已阻断异常细节。字段校验单独交技术总负责人-Atlas定范围 |
| POST/PUT /dpp/etlScheduler | DTO缺NotNull/NotBlank；schema taskId/taskCode/startTime/endTime/cronExpression/dsId非空；创建直接Mapper.insert | 同形风险，主服务异常兜底已脱敏；逐字段创建/局部更新语义待单独修复/验收 |
| /dpp/etlNode、etlTaskInstance、节点关系与日志独立写入口 | DTO不少只有Size，但控制器先调用requireVersionedTaskWrite，当前无条件AccessDeniedException，独立增改不可达Mapper | 不因注解缺失即判同形可达缺陷；保留禁止绕过版本化画布写入的鉴权边界。内部服务输入另外审计 |
| /att/taskCat、/att/dataDevCat、清洗类目 | DTO有名称约束；服务同时校验名称、项目、父级、排序、重名及编码分配 | 已有入口/服务双层防护；相邻离线隔离/编码套件回归，不改BIN-116/117语义 |
| /da/asset创建 | DTO无完整必填注解；真实入口createDaAssetNew先经AssetIntakeAccess.create（项目权限、名称、类型及登记方式检查），原始createDaAsset才直插入 | 不能把内部insert直接判为HTTP漏洞；保留按资产类型的服务校验，后续应按各类型逐项核实，不声称全资产字段验证完成 |
| /dp/model旧创建 | DTO已有模型码/名称/表类型等NotBlank；createDpModel当前直接拒绝旧入口，要求Hive五层建模 | 无本入口缺参直接落库路径；保留可读IllegalArgumentException业务提示 |
| 主服务通用导入/服务将e.getMessage拼入ServiceException | 包括GenTableServiceImpl导入、DPP执行/预检包装等 | SQL/Mapper/堆栈特征或数据库cause由公共处理器脱敏；不代表导入数据字段校验完善，未跑业务导入 |
| /common/upload、/common/uploads | 捕获Exception并直接AjaxResult.error(e.getMessage())，文件注册有数据库持久化，绕过全局处理器 | **残余外泄路径**，不受本次公共兜底保护；交技术总负责人-Atlas另行安排上传模块修复。未执行文件上传 |
| 独立AI服务GlobalExceptionHandlerAi、ChatMessageController流响应 | AI业务异常原文及SSE onErrorResume手工CommonResult.error(e.getMessage())，与主服务处理器不同 | **残余外泄路径**，本棒不跨AI流协议改造；须单独派单，未冒称全仓已消除外泄 |
| 验证码CaptchaController与McTaskExecutorController | 直接将错误消息组入响应，分别是验证码/执行入口，不是本任务的普通创建保存 | 相邻风险留清单，公共handler不能拦截已捕获并返回的异常，未修改其协议 |

因此本棒结论为：D3确定链路及主服务全局兜底已修；同类审计发现其余字段校验缺口和绕过兜底的响应路径，不能据此宣布全仓所有创建/保存接口均已修复。

## 错误码约定比对（提交技术总负责人-Atlas裁定项）

| 情形 | 当前约定 | 本次决定 |
|---|---|---|
| ServiceException无显式code；类目重名/非法名等 | AjaxResult.error默认业务500，HTTP通常200 | 沿用；与BIN-123已确认的D1口径一致 |
| Bean Validation、绑定失败、未知异常 | 默认业务500，HTTP200 | 沿用，明确缺参文案不改码 |
| 有显式code的ServiceException | 按原码返回，HTTP默认200 | 沿用；离线夹具409验证不被强改500，409不是本仓所有重名的现行约定 |
| AccessDeniedException | 业务403，该处理器原不设置HTTP403 | 沿用，不修改鉴权 |
| Recoverable/DataAccessResourceFailure与DependencyUnavailable | HTTP503/业务503 | 沿用 |
| 成功草稿创建 | @ResponseStatus(CREATED) HTTP201，CommonResult业务200 | 沿用，不混淆两者 |
| GlobalErrorCodeConstants.BAD_REQUEST=400等枚举、部分手工错误响应 | 枚举存在400，并非所有校验入口统一使用；一些模块自行选择错误码 | 存在跨路径差异；不在本棒统一为400/409，语义规范由技术总负责人-Atlas裁定 |

## 研发验证与限制

实际结果：JDK21.0.10，2026-10-17 03:26:08 +08:00，30相关模块离线package **BUILD SUCCESS**。TaskCreationValidationTest 38、GlobalExceptionResponseTest 21、GlobalExceptionHandlerDatabaseTest 2、CategoryProjectIsolationTest 31、TaskCategoryCodeAllocationTest 16、CleanCategoryCodeAllocationTest 20，合计**128/128**，0失败/异常/跳过。既有Scala/shade/MapStruct警告保留。TaskCreationValidationTest在进程内运行Spring MVC真实请求体反序列化、Bean Validation、Controller、DevelopmentResourceAccess权限SQL、任务/调度服务及MyBatis→H2；任务和调度合成表补齐本次相关NOT NULL约束。用户principal与角色/菜单为合成数据；成员可见项目固定，Redis发码为AtomicLong替身。5个轻量写入口只验证MVC分组及访问检查，业务返回用接口替身；三个异常用例人为注入数据库/业务/RuntimeException，检测响应不含实现细节。没有运行HTTP监听端口或登录安全过滤器。

GlobalExceptionResponseTest使用合成控制器验证真实异常解析/JSON输出，包含数据库异常及包装业务异常、堆栈/SQL文本、正常业务拒绝、参数错误、403及503；服务端ERROR/WARN日志为预期证据，不等于JUnit失败。CategoryProjectIsolationTest、TaskCategoryCodeAllocationTest、CleanCategoryCodeAllocationTest验证既有代码的H2合成数据边界。H2≠真实MySQL锁/隔离与95验收；未连接真实Redis/DS/DataX/Spark/Hive/HDFS，未验证完整qdata-server、独立AI或前端构建。

## 独立回归与交接

回交技术总负责人-Atlas审查；与BIN-123、BIN-125同批合入后，由技术总负责人-Atlas统一派测试与部署工程师-Sentinel在下一批95独立回归ADJ-01。本棒不部署或宣布业务验收通过。

1. 在约定测试项目分别构造缺责任人、任务名、项目ID/code、执行策略、cron，以及空白/超长文本：明确字段文案，响应不含SQLException、Mapper、SQL/堆栈；只读核对失败无任务/调度新增。
2. 使用合法字段创建type1/3草稿，HTTP201/业务200，每次1任务+1调度，字段与项目归属正确；同项目/跨项目同码类目场景结合BIN-123一起验证，禁止回退它的隔离修复。
3. 复制、上下线、调度等原有轻量请求继续可用，不因本次校验新增责任人/名称/cron要求。真实执行需测试与部署工程师-Sentinel按原授权安排；本地替身不能证明运行成功。
4. 主服务可控异常响应核对通用提示与服务端诊断日志，正常类目重名等业务拒绝仍可读，业务码及HTTP约定保持不变；不通过改写既有正式数据构造异常。
5. 只读核对bin11cat、40规则、9保护任务及2099调度；Hive/HDFS零删除、零清空、零覆盖，测试数据按既有规则留存。

版本规划管理工程师-Roadmap分别登记候选、实际合入、后续95部署/独立验收；本棒代码与离线验证完成不代表合入、部署或正式发布。

## 复现本地验证

```sh
# qdata-work目录；使用既有JDK21和依赖缓存，独立java.io.tmpdir
mvn -o -s /private/tmp/bin98-maven-settings.xml \
  -Dmaven.repo.local=/private/tmp/qdata-bin89-maven \
  -pl qdata-module-dpp/qdata-module-dpp-biz -am \
  -Dtest=TaskCreationValidationTest,GlobalExceptionResponseTest,GlobalExceptionHandlerDatabaseTest,CategoryProjectIsolationTest,TaskCategoryCodeAllocationTest,CleanCategoryCodeAllocationTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
```

JUnit XML、最终package日志、314项审计清单、源码与补丁随任务证据包交付。前序迭代发现翻译日志资源缺失导致异常处理再次失败，已修并在最终一致源码重跑；中间迭代不作为通过证据。
