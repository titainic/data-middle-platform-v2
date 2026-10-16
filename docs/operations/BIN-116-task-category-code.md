# BIN-116 数据集成类目编码修复与回归交接

作者：全栈研发工程师-Forge。来源：BIN-116 / BIN-113；基线：main c87348e3。

## 问题与实现

POST /att/taskCat 新增时，旧 createCode 只按 parentId 找最大 code，跨项目选中旧根 bin11cat，再将末两位 at 转成整数，抛出业务500。即使限定项目，项目内存在异前缀、纯字母或空编码仍可能异常。

- createCode 显式接收 projectId。只读目标项目的编码占用，父类目必须属于该项目、存在且启用。
- 历史父编码作为不透明前缀保留，例如 bin11cat 下新增 bin11catA01；只对内部生成的 A01..Z99 片段调用公共自增工具。空父编码返回明确业务错误，禁止自动改旧数据。
- 与清洗类目已有策略一致，选择当前层第一个未占用片段；包括本项目逻辑删除历史、孤立后代在内的路径前缀均保留。层级用尽时明确拒绝，不用 Z99A01 侵占下层。保守编码长度上限128。
- 用项目行 FOR UPDATE 将分配、同父重名检查和插入串行化，覆盖空类目。父节点、占用和重名均使用锁定读取，以免控制器早先建立的 MySQL REPEATABLE READ 快照造成漏查。不同项目锁不同的项目行。
- REST 请求/响应和公共 YouBianCodeUtil 不变。内部 IAttTaskCatService.createCode 改为三参数，仓库唯一调用已同步，需整体重建。
- 来源系统中文确认去掉多余 <；中文、英文、日文成功提示去掉多余引号。

无迁移、配置、依赖升级或数据修复。40条既有规则、旧编码和正式数据均不修改。

## 离线验证边界

后端测试运行真实 AttTaskCatServiceImpl、MyBatis Mapper SQL、Spring 事务代理；仅用进程内 H2 MySQL模式合成数据，不接外部数据库、Redis、HTTP业务接口或集群。并发8请求覆盖空项目、旧编码父节点、同名竞争；不能替代真实 MySQL 的隔离级别与多实例验证。合成40条规则快照前后核对不等于读过95实际40条规则。

前端文案测试使用真实三语言资源和 vue-i18n 插值。相邻来源系统删除、类目项目参数测试使用其既有 modal/HTTP 替身；不代表真实权限或运行态通过。构建产物仅保存在本地临时目录，不部署95。

## 实际研发结果

- 修复前相同后端用例：16项中1通过、11失败、4异常；捕获原始 For input string: "at"。
- 修复后：TaskCategoryCodeAllocationTest 16/16、CleanCategoryCodeAllocationTest 20/20，合计36/36，无跳过；35相关模块package成功。
- 前端原文案6例均失败；修复后文案及相邻聚焦测试31/31通过，Vite生产构建通过（既有动态资源/大chunk等警告保留）。
- 尝试完整qdata-server离线构建因缓存缺少认证模块依赖而停止，未报告为通过；依赖包含spring-boot-starter-thymeleaf和sa-token相关包。未为此修改pom或下载/升级依赖。
- 全部检查在最新main c87348e3的独立临时工作树进行，未纳入或覆盖共享工作区现有修改。

实际命令（JDK21；工作目录qdata-work）：

```sh
mvn -o -s /private/tmp/bin98-maven-settings.xml -Dmaven.repo.local=/private/tmp/qdata-bin89-maven -pl qdata-module-att/qdata-module-att-biz -am -Dtest=TaskCategoryCodeAllocationTest,CleanCategoryCodeAllocationTest -Dsurefire.failIfNoSpecifiedTests=false package
```

前端工作目录qdata-work/qdata-ui：

```sh
node --test tests/sourceSystemStatusText.test.mjs tests/sourceSystemDelete.test.mjs tests/taskCategoryProject.test.mjs
npm run build:prod -- --outDir /private/tmp/bin116-1e4a740f/ui-dist
```

## 测试与部署工程师-Sentinel 回归重点

由技术总负责人-Atlas审查候选后另行派单；本棒不直接操作95。

1. 项目5根新增：名称校验业务200；POST业务200返回新ID；只读核对project_id、parent_id、name/code；旧项目1的bin11cat保持原样。
2. 按授权范围在bin68_前缀清单内验证同父跨项目同名、同项目重复名、根/子类目、异常父节点和失败后重试。不要修改正式数据制造异常，不自动清理Hive/HDFS。
3. 在隔离测试数据上验证同时新增不同名仅得到唯一code，同时同名只插入一条。重点核对MySQL当前隔离级别下先读再等待项目锁的路径；不以H2替代此项。
4. 来源系统启用/禁用确认与成功文案正常，取消/状态回退及既有删除契约回归。
5. 项目内编码唯一；跨项目可出现相同编码。当前 getCountByCatCode 及类目删除的子节点计数仍有未限定项目的旧实现，属于既存消费者隔离风险：审查时请技术总负责人-Atlas协调后续修复，独立回归须关注跨项目任务引用/计数，不能把本次新增通过等同于全模块隔离验收。
6. 不执行BIN-85恢复；不改40条既有规则；Hive/HDFS删除、清空和覆盖始终禁止。

## 交接

技术总负责人-Atlas审查；测试与部署工程师-Sentinel独立验证；版本规划管理工程师-Roadmap依据提交与独立验收结果分别登记代码候选、已合入、已部署与已验收，不混用状态。
