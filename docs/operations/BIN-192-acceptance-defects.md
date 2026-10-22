# BIN-192 DEF-1～DEF-6 研发交接

作者：全栈研发工程师-Forge。日期：2026-10-21，Asia/Shanghai。

来源：[BIN-192](http://localhost:3100/BIN/issues/BIN-192)，验收依据：[BIN-190 业务验收报告](http://localhost:3100/BIN/issues/BIN-190#document-acceptance-report) rev `f2db4dd8`。基线 `1ba2375`，修复分支 `fix/bin192-acceptance-defects`。确切修复提交、构建结果和证据下载入口见 BIN-192 的 `fix-report` 文档。

## 授权与协调

任务已授权 qdata 代码修改与95研发自测。本棒采用本地构建和离线验证，未登录或部署95、未重启服务、未修改权限、未连接生产或 Hive/HDFS。公共接口影响范围已在启动评论 `42da540d-e357-4b3d-a410-67da43a5da76` 说明，研发增量评论 `d7bb826e-1480-4715-829a-57ae4ac544ac` 补充定位事实。保留现有 HTTP/业务码和鉴权边界，无数据库迁移。回退办法为回退本次代码提交；本棒未执行环境回退。

## 根因与变更

| 缺陷 | 本次变更 | 边界与口径 |
|---|---|---|
| DEF-1 | 任务创建和改名按项目检查当前有效名称；按项目行加锁后使用当前读，阻止并发创建；新输入去首尾空白；返回“当前项目已存在同名任务，请使用其他名称” | 同项目未删除记录占用名称，跨项目允许同名，软删除释放名称。校验覆盖正常任务服务写入口；不新增唯一索引、不处理历史重复、不承诺绕过服务的直接 SQL/旧导入写入获得保护 |
| DEF-2 | 编辑配置合并保留持久化字段；保存显式携带字符串责任人、Cron和原任务项目身份；任务详情 taskConfig 补项目ID/code；失败透出 res.msg；保存中禁止重复提交 | 原任务项目身份不会被全局项目切换替换。三个缺参错误先通过真实 MVC 校验复现。报告中的历史任务已被前序清理，本棒未复原其数据或声称复现原95浏览器请求 |
| DEF-3 | 草稿首次完整保存初始化任务/节点版本和依赖，注册暂停的 Quartz 并绑定任务与调度行；兼容把草稿送到 update 的旧编辑器；已保存任务转入版本化更新；修正后置节点ID查找使用错误版本；离线完整任务可执行一次；注册异常不再被吞成0 | 保存不启动周期调度。未完成草稿和未绑定的历史任务返回明确处理提示。历史有效版本但绑定为0的开发任务可重新保存补注册；受管批次冻结边界保留。实际执行日志需95部署候选后回归 |
| DEF-4 | 新建表单项目必选；既有服务的项目关联写入检查批量写入结果，新建不走删除旧关联；数据源分页批量装载 projectList；项目ID筛选转换为经授权的项目code，同时传入时校验一致性 | 源码原已存在关联写入与项目授权，不能仅凭列表 projectList=null 断言数据库未写关联。另确认原分页参数不含projectId、研发分页仅识别projectCode，可能造成空列表。未自动补绑历史对象 |
| DEF-5 | 用户删除和启停确认将插值传入翻译函数第三参数；批量ID逗号展示；启停含用户名和ID；中英文文案同步 | 只改确认文案，未调用用户删除、禁用或权限接口 |
| DEF-6 | 新显式年上限为当前年+5（2026年为2031），区间端点和年份步长受限；新输入越界不能确认；显示长期配置提示 | 产品建议：显式年仅用于近期计划，周期任务使用六段或每年。已有2099等表达式保留原样展示、取消与原样回填，不静默改写；修改表达式后需要选择窗口内年份。后端Quartz通用语法能力保持原样；此项不是全系统调度寿命限制 |

名称校验复用事务中的项目行锁，正常服务写入在同项目内串行；没有对存量数据做破坏性迁移。DPP服务原有远端调用仍在原事务结构中，未借本棒重构调度架构。

## 公共接口影响

- `/dpp/etlTask/updateQuery/{id}`：taskConfig 增加已有顶层的 projectId/projectCode；原响应字段保留。
- 完整任务创建、保存和改名：重名时沿用业务500的可读拒绝；草稿误投 updateProcessDefinition 时执行首次完整保存。
- `/dpp/etlTask/startDppEtlTask/{id}`：草稿及未注册任务获得可恢复提示；成功仍表示提交，不表示业务执行成功。
- `/da/dataSource/list` 与研发数据源列表：支持 projectId，传入项目code时须一致；仍执行可见项目和操作权限检查。
- Cron为共享前端组件，影响开发、集成、质量、采集及系统调度中的生成器；历史表达式往返行为保留。

## 验证方法与限制

后端使用 JDK21 和项目现有 Maven 依赖。本地任务集成夹具运行真实 MVC、校验、项目权限SQL、事务、MyBatis Join、任务/调度/节点/版本及依赖表，存储为 H2 MySQL模式。成员关系、登录principal、Redis发码、Quartz适配器和最后实例查询使用合成数据或替身；没有 HTTP 登录过滤器、实际 Quartz 触发线程或外部 SQL 引擎执行。完整保存后的详情读取验证责任人、Cron、项目ID和SQL，提交验证绑定的 Quartz ID。执行状态与日志分支另由既有 DataDevelopmentRunRegressionTest 验证，该套件的 JDBC执行、Redis和实例持久化也是替身。

数据源测试运行真实归属校验、DTO转换及服务流程，数据库与Redis为替身，覆盖写关联、分页装载、越权/项目不一致拒绝和关联写入失败。不能据此宣称已完成真实MySQL事务或95数据源创建验收。

前端测试执行真实页面处理函数、Vue SFC脚本/模板、响应式更新及i18n；HTTP、Element Plus控件和渲染宿主为替身。未执行真实浏览器测试。项目未配置独立TypeScript类型检查命令，本棒执行Vite生产构建，不将它表述为独立类型检查。

构建首次受Scala临时目录权限及依赖缓存完整性影响，后续使用沙箱外本地验证与固定依赖下载；前端锁文件安装跳过脚本后差异组件缺入口，补跑已有v-code-diff/vue-demi脚本，未升级依赖。扩展H2夹具补齐MyBatis Join插件和SYSTEM_USER关键字兼容设置。中间失败日志不作为最终通过证据。

实际结果：2026-10-21 18:31（Asia/Shanghai）37模块编译与核心回归成功，108项通过；18:33同一编译结果37模块打包成功（`maven.main.skip=true`复用刚验证的类），相邻回归37项通过、2项真实MySQL集成因未配置环境变量跳过。合计后端145项通过、2项跳过，失败/异常0。前端生产构建成功，104项相关回归全部通过，失败/跳过0。没有将两项跳过计为通过。

复现命令（在 `qdata-work`，使用JDK21；Maven缓存路径是本棒临时缓存，换环境时按实际依赖缓存调整）：

```sh
mvn -o -Dmaven.repo.local=/private/tmp/bin192-maven \
  -pl qdata-module-dpp/qdata-module-dpp-biz,qdata-module-da/qdata-module-da-biz -am \
  -Dtest=TaskCreationValidationTest,QuartzDefinitionVersioningTest,QuartzDefinitionLogRegressionTest,DppTaskQuartzServiceTest,LocalTaskSubmissionRegressionTest,DataDevelopmentRunRegressionTest,DatasourceProjectBindingRegressionTest,DatasourceDetailCredentialBoundaryTest,DatasourceCacheMetadataTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 仅紧接上一步、同一份源码和编译输出时可跳过重复编译。
mvn -o -Dmaven.repo.local=/private/tmp/bin192-maven \
  -pl qdata-module-dpp/qdata-module-dpp-biz,qdata-module-da/qdata-module-da-biz -am \
  -Dmaven.main.skip=true -Dtest=CategoryProjectIsolationTest,DppPublishedExecutionPlanTest \
  -Dsurefire.failIfNoSpecifiedTests=false package

cd qdata-ui
npm ci --ignore-scripts --no-audit --no-fund
npm rebuild v-code-diff vue-demi
npm run build:prod
node --test tests/businessAcceptanceDefects.test.mjs tests/cronYearRoundtrip.test.mjs \
  tests/taskCategoryProject.test.mjs tests/userDepartmentRefresh.test.mjs \
  tests/projectOptionsRefresh.test.mjs tests/dataxTaskPayload.test.mjs tests/qualityTaskCronPayload.test.mjs
```

首次构建应正常编译，不能把 `maven.main.skip=true` 用于干净环境。构建警告包含既有Scala版本、shade重复资源、Vue深度选择器和大chunk提示。本棒未统一处理这些无关警告。

## 测试与部署工程师-Sentinel 回归重点

受测候选为本修复分支的确切提交。由技术总负责人-Atlas核证并安排候选部署与独立回归；本棒不代替独立验收。沿用约定测试账号，凭据只按安全位置读取，不写入证据。

1. D-05：同项目连续及并发创建同名任务，仅一份任务/调度；明确重名提示；跨项目同名允许；同ID保存允许；改名冲突拒绝；仅软删除的自建对象释放名称。存量重复对象不自动处理。
2. D-02：新建 `fix_` SQL开发任务，在详情页直接保存及修改配置后保存，抓取请求确认责任人/Cron/projectId/projectCode齐全；打开编辑后切换全局项目，任务身份仍属原项目；空白/缺参与后端拒绝显示具体原因。
3. D-03：先只保存草稿，执行按钮不可用且直调提示先完成保存；首次完整保存后任务/节点为版本1，依赖指向真实后置节点；任务与调度行均有正数Quartz ID，Quartz保持暂停；离线完整任务可“执行一次”，任务实例及节点日志可查询，并核对实际SQL结果。提交成功不等于执行成功。再离线编辑检查版本递增和日志保留；注册失败不允许写成功状态或0绑定。
4. C-04：新建数据源必须选择所属项目，失败确认新增的连接应禁用；启用且关联成功后，用projectId和projectCode两种方式查同项目列表，结果一致；管理页projectList非空；项目错配、无权项目拒绝。新建失败不残留孤立数据源，列表不按行额外查关联。
5. I-03：单个/批量删除确认显示完整ID，取消不调用删除；启停显示动作、用户名及ID，取消恢复开关。使用自建 `fix_` 账号，避免对原账号做状态或权限修改。
6. F-03：2026年新输入2031可确认、2032/2099不可确认；区间/间隔/列表及六段/每年都检查；旧2099表达式打开、原样确认和取消均不变，修改后不得携带越界年提交；检查质量、开发、集成和系统调度共享入口。既有保护任务不改动。

真实执行只使用无副作用的已授权查询，例如 `SELECT 1`。Hive/HDFS不创建、不删除、不清空、不覆盖；若后续确需写Hive数据，必须保留并遵守用户手工删除约束。共享环境自建 `fix_` 应用对象的登记和收尾由实际创建者在当棒授权范围处理。

## 版本规划管理工程师-Roadmap 登记增量

关联 DEF-1～DEF-6 和 BIN-190 D-02/D-03/D-05、C-04、I-03、F-03。登记为研发修复候选；没有GitHub推送、合入、正式发布、95部署或业务验收的事实时，不使用相应结论。当前 v1.0.0 业务验收“有条件通过”的历史口径保持，候选独立回归完成前不能改为“可上线”。

## 数据与清理留痕

本棒共享环境业务对象新增0、删除0；生产、Hive、HDFS操作0。离线H2对象仅存在于测试JVM，JVM退出后释放，不运行任何共享环境清理脚本。前序BIN-190对象不恢复、不修改、不清理。原有备份保留。
