# BIN-195 D-1～D-5 研发交接

作者：全栈研发工程师-Forge。日期：2026-10-21，Asia/Shanghai。

来源：[BIN-195](http://localhost:3100/BIN/issues/BIN-195)；父任务 [BIN-192](http://localhost:3100/BIN/issues/BIN-192)；依据：[BIN-193 独立回归报告](http://localhost:3100/BIN/issues/BIN-193#document-regression-report) rev `a5f9a878`。基线 `a1da43322a1c3a6eb4c580ae30d87a99929a4bc0`，分支 `fix/bin192-acceptance-defects`。确切提交及证据包入口见 BIN-195 的 `fix-report` 文档。

## 授权与协作

本棒按派单只修改 qdata 代码、进行本地构建及离线研发自测、中文提交和交接。未部署、未重启服务、未登录测试环境、未修改权限、未连接生产或 Hive/HDFS、未创建或删除共享业务数据，无数据库迁移。所有原有备份保留。回退办法为回退本次代码提交；本棒没有执行回退。

启动评论 `57be811f-c797-409c-a1de-e878bebcfd42` 已盘点操作范围；父任务协调评论 `68c93ca2-f9e9-42b2-9481-4ee3189b6696` 已说明接口行为影响和文件主责。URL、字段结构、响应码及项目鉴权沿用现状。本棒修改 DPP 任务控制器/服务、数据源归属校验、开发详情保存与对应中英文文案、回归测试；未修改其他角色日志或项目总状态。

## 根因、实现及兼容影响

| 缺陷 | 根因与修复 | 影响范围 |
|---|---|---|
| D-1 / DEF-2 | Element Plus validate 拒绝字段对象被当作 Error，丢失字段 message。现在汇总去重显示字段错误，并打开任务配置；支持 HTTP response.data.msg、data.msg、msg、字符串及 Error.message。SQL/名称为空、validate=false 均有反馈。空响应和无 msg 失败明确提示服务没有返回错误原因。保留 loading 全链路守卫和 finally 恢复。 | 仅开发详情主保存页面。已知具体原因优先显示；服务不提供原因时如实提示，不能凭空生成根因。移除对原始错误对象的控制台输出。 |
| D-2 | 控制器固定写 type=1 导致已有 type=3/4 在鉴权阶段被拒。移除该覆盖；鉴权仍拒绝伪造类型并从持久化任务填充类型；服务也采用原类型。type=3/4 转入原有定义保存流程，避免访问不存在的数据集成扩展记录。 | updateEtlTask 接受原 type=3/4 或省略 type；原 type=1 数据集成链路保留。不能通过请求改变任务类型。 |
| D-3 | 通用上下线入口对 Quartz 仍调用 DolphinScheduler，下线分支缺 return。现在按持久化 scheduler 将 Quartz 路由至 updateReleaseJobTask；DS 下线处理后立即返回。修正 Quartz 特殊状态 -3 下线为 -2，避免下线后仍落为 -3；调度已上线时仍要求先下线调度。 | Quartz 不调用 DS ONLINE/OFFLINE；DS 下线不继续发布/上线。任务发布状态与周期调度状态仍分开，未改变周期调度功能。 |
| D-4 | createProcessDefinitionEx 对已初始化任务自动转更新，绕过首次保存守卫。现只接受草稿的首次初始化；已保存任务明确拒绝“任务已完成首次保存，请刷新后重试”。 | Quartz/DS 首次定义入口均执行守卫；正常更新仍用 updateProcessDefinition。依赖旧入口重复保存的客户端需改用更新入口。草稿误投更新入口的既有兼容仍保留。 |
| D-5 | validateSave 从 projectId 反查并覆盖显式错配 code，掩盖调用方错误。现显式非空 code 与授权项目实际 code 不一致时拒绝“项目ID与项目编码不一致，请重新选择所属项目”。 | 数据源创建、编辑及复用 validateSave 的导入/测试连接路径；省略/空白 code 仍从合法 projectId 补齐。项目权限及技术字段服务端归一化保留，不迁移历史关联。 |

D-4/D-5 本批均已实现明确拒绝，不留待产品裁定。

## 验证方法与限制

- 前端执行真实页面保存和异常处理函数；Vue ref 为真实实现，Element Plus validate、HTTP 接口和模态提示为替身。覆盖字段 reject、业务拒绝、异常对象、空响应、校验失败恢复及校验/API等待期间重复点击。没有运行真实浏览器；不能据此宣称 D-02-6 浏览器验收完成。
- TaskCreationValidationTest 运行真实 MVC、参数校验、项目权限、MyBatis、事务和 H2 MySQL 模式的任务/节点/版本/调度表。覆盖 type=3/4 显式与省略类型、首次及再次保存、类型保持、重复初始化拒绝后版本/日志不增加、正常更新可继续。Quartz 注册/更新和身份为替身，不触发真实任务。
- TaskReleaseRoutingRegressionTest 运行真实上下线服务；数据库和远端调度为 Mockito 替身，核对 Quartz 及特殊状态上下线、调度上线冲突、DS 下线无 ONLINE/额外调用、失败不写成功状态。
- 数据源测试执行真实校验及服务逻辑，JdbcTemplate/权限/数据库/Redis为替身；覆盖错配拒绝、缺 code 兼容和既有关联行为。补齐独立执行所需 MyBatis 元数据初始化，避免测试顺序依赖。
- MySQL 集成夹具同步 D-5 创建/编辑错配拒绝预期。未设置 `QDATA_NAMESPACE_INTEGRATION`、`QDATA_NAMESPACE_URL` 或 `QDATA_DATASOURCE_PROJECT_URL`，对应真实数据库用例跳过，不算通过。
- 前后端分别构建；本棒不组装或发布安装包。后续部署应从确切提交按既有 `deploy/build.sh` 流程生成包含最新前端的发布包，不直接将本地验证 JAR 视为完整部署交付。
- 后端使用 Homebrew JDK21 与已有 Maven 缓存 `/private/tmp/bin192-maven`，无框架/依赖版本变更；前端没有独立类型检查命令，执行 Vite 生产构建，不将其称作独立类型检查。

2026-10-21 22:56（Asia/Shanghai）后端13套件176项：174通过、2项真实MySQL依赖跳过、失败/异常0；前端113项全部通过，生产构建成功。主服务正常编译阶段，DatasourceProjectCapabilityIntegrationTest 另有30项因环境开关未开启全部跳过；合计未覆盖真实数据库集成32项。后端回归阶段复用本轮刚正常编译的生产类（`maven.main.skip=true`），未跳过测试编译或执行。最终正常编译打包结果及精确数量见 BIN-195 `fix-report` 与证据包 `summary.json`。构建与离线自测通过不等于独立回归通过、已部署、业务验收或可上线。

可复现的完整验证命令（仓库已有依赖及 JDK21；不配置共享数据库变量）。本轮实际先在37模块执行上述13套件，再单独正常编译打包主服务48模块并检查禁用的真实MySQL集成夹具；各阶段命令与最终结果留在证据包：

```sh
cd qdata-work
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
mvn -o -Dmaven.repo.local=/private/tmp/bin192-maven -pl qdata-server -am \
  -Dtest=TaskCreationValidationTest,TaskReleaseRoutingRegressionTest,DataXUpdateSaveRegressionTest,DatasourceProjectBindingRegressionTest,QuartzDefinitionVersioningTest,QuartzDefinitionLogRegressionTest,DppTaskQuartzServiceTest,LocalTaskSubmissionRegressionTest,DataDevelopmentRunRegressionTest,DatasourceDetailCredentialBoundaryTest,DatasourceCacheMetadataTest,CategoryProjectIsolationTest,DppPublishedExecutionPlanTest,DatasourceProjectCapabilityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false package

cd qdata-ui
npm run build:prod
node --test tests/businessAcceptanceDefects.test.mjs tests/cronYearRoundtrip.test.mjs \
  tests/taskCategoryProject.test.mjs tests/userDepartmentRefresh.test.mjs \
  tests/projectOptionsRefresh.test.mjs tests/dataxTaskPayload.test.mjs tests/qualityTaskCronPayload.test.mjs
```

中间问题如实保留：沙箱内 Mockito 无法 attach JVM agent，已通过获准的沙箱外离线验证继续；数据源夹具独立执行缺元数据已修正；新增路由测试的重载 any() 编译歧义已改为明确类型；二次保存覆盖补齐 H2 实例表及真实节点替换 XML/类型别名，旧 DataX 名称冲突查询显式模拟 SQL 空结果 null；首次扩展到主服务时复用旧输出失败，最终改为正常编译全部选中模块。主服务正常编译完成后，离线打包缺少 Spring Boot 插件的 Micrometer 依赖，后续联网补齐并复用刚编译的48模块继续打包；未改变源码依赖版本。中间失败不作为最终成功证据。既有 Scala 版本、shade 资源、Vue 深度选择器及大 chunk 等警告未作无关重构。

## 测试与部署工程师-Sentinel 独立回归重点

由技术总负责人-Atlas 核证候选并安排独立回归。部署、服务重启和测试账号登录按下一棒实际授权执行；本棒不扩大 BIN-193 旧授权。

1. D-02-5：真实浏览器创建自建 `fix_` SQL 开发任务。SQL/名称/责任人/Cron缺失时均有具体提示；责任人及Cron同时无效时显示两个字段错误，修正后可保存；前端校验失败不发保存请求。后端缺参与业务异常也显示具体原因；无错误原因时给出可重试提示。
2. D-02-6：有效输入下，分别在表单校验和网络等待时连续三次点击，只出现一笔保存请求；失败恢复按钮，成功跳转。需要独立浏览器证据，不能以离线守卫测试替代。
3. D-2：type=3/4 任务显式传原 type 与省略 type，分别首次完整保存和已有版本再保存；类型、项目、节点类型保持，版本正常递增；发送不匹配 type 仍拒绝；type=1 原数据集成保存不回退。
4. D-3：Quartz 已上线任务用 updateReleaseTask 下线，状态1→0；特殊状态-3→-2；不会发 DS ONLINE/OFFLINE。周期调度仍在线时要求先下线调度。DS 下线后核对无紧随 ONLINE/周期调度上线调用；DS拒绝时本地不能写成功。再核对 Quartz 上线及原 updateReleaseJobTask 兼容。
5. D-4：草稿首次 createProcessDefinitionEx 成功，重复调用明确拒绝，任务/节点版本、日志和Quartz绑定均不增加；updateProcessDefinition 仍可正常保存新版本。Quartz和DS各核对一次；受管批次冻结边界保持。
6. D-5：数据源创建与编辑显式 projectId/code 错配均拒绝，无新增或被改写关联；一致值和省略code成功；无权项目、重复项目仍拒绝；核对导入/测试连接复用路径。既有历史数据不自动处理。

只对下一棒本人创建且登记的应用测试对象执行获准清理；不得清理 BIN-193 或其他角色数据。Hive/HDFS 零写入、零删除；原有备份保留。

## 交接和版本登记

交技术总负责人-Atlas：核对确切提交、构建与研发证据后派测试与部署工程师-Sentinel 独立回归，重点 D-1/DEF-2 及 D-02-6；本棒不宣布 BIN-192 整体闭环。

交版本规划管理工程师-Roadmap：关联 BIN-195 D-1～D-5、BIN-193 D-02-5/D-02-6/C-04-6、BIN-192 DEF-2。登记为本地研发修复候选；当前未推送、未合入、未发布、未部署。确切提交以 `fix-report` 为准；D-4/D-5 是本批明确拒绝的兼容变化。

本棒共享环境测试数据新增0、删除0；H2夹具仅存在本地测试JVM，退出释放，不执行共享清理脚本。本棒本地构建、日志和证据保留供复核。
