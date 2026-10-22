# BIN-199 数据开发新增任务防重复提交

作者：全栈研发工程师-Forge；日期：2026-10-22（Asia/Shanghai）。
来源：[BIN-199](http://localhost:3100/BIN/issues/BIN-199)，独立回归 [BIN-197](http://localhost:3100/BIN/issues/BIN-197#document-regression-report) rev `8c37b2f3` D-02-6。
基线：`2efe93a28057e028b21b9be9f3ec44dcc16ed835`；分支：`fix/bin199-save-submit-guard`。

## 问题与修改

新增任务弹窗的 `saveClose` / `saveData` 在异步表单校验及节点编码获取前没有互斥锁；父级 `createEtlTaskFront` 没有请求期守卫。弹窗又在事件发出后立即关闭，无法反映保存中的状态。之前详情页主保存的守卫没有覆盖该创建入口。

- `developTask/add/add.vue`：两个按钮统一调用 `submitForm`，首次 await 前同步设置 `submitting`，覆盖调度检查、表单校验、节点编码及保存请求。两个按钮绑定 loading/disabled，提交期间阻止遮罩/Escape/关闭按钮导致的提前关闭。
- `developTask/index.vue`：通过可等待的内部 `submitTask` 回调返回保存结果，并保留父级互斥守卫。仅成功关闭弹窗；「仅保存」刷新列表；「保存并配置流程」跳转原编辑页。失败保留输入并解除锁，显示业务或网络原因。
- 详情页配置继续使用原 `save` 事件，不调用创建接口。对外请求URL、字段、项目鉴权、后端名称唯一性均不变。
- 不使用固定时间窗替代请求生命周期，没有新增依赖、数据库迁移或后端改动。回退方式为回退该提交，本轮未执行回退。

## 研发自测

在本地隔离副本执行：

```bash
cd qdata-work/qdata-ui
node --test tests/developTaskSubmit.test.mjs tests/businessAcceptanceDefects.test.mjs tests/cronYearRoundtrip.test.mjs tests/taskCategoryProject.test.mjs tests/userDepartmentRefresh.test.mjs tests/projectOptionsRefresh.test.mjs tests/dataxTaskPayload.test.mjs tests/qualityTaskCronPayload.test.mjs
npm run build:prod
QDATA_PLAYWRIGHT_MODULE=/absolute/path/to/playwright-core/index.mjs node tests/developTaskSubmit.browser.mjs
```

浏览器脚本使用已安装的 Playwright Core 和 Google Chrome，可通过 `QDATA_CHROME_PATH` 指定浏览器。仅起本机 5199 端口夹具，实际渲染仓库弹窗、Vue、Element Plus，执行源码提取的父级处理函数；API、项目/权限数据、模板编辑器、Cron编辑器、分页和路由宿主使用替身。校验采用真实 Element Plus，首轮仅在真实 validate 前插入可控等待。夹具关闭浏览器与本机服务，不登录95，不创建共享数据。

实际结果：

- Node：124 项通过，0 失败、0 跳过。新增11项覆盖校验/编码/调度/网络等待期互斥、交替点击、父级守卫、各失败重试、详情配置兼容和精度安全ID。
- Vite生产构建成功（3m40s）；有既有 Sass / `::v-deep` / 大体积chunk警告。项目未提供独立类型检查脚本。未跑后端测试（后端无变更）。
- Chrome 1440×1080：同一事件循环三次点击在校验前只调用一次validate，节点编码与保存请求各1笔；网络等待期两个按钮 disabled，交替派发点击无新增请求；业务失败保留输入并恢复；成功重试跳转且保留字符串ID；空名称零请求；网络中断后恢复、重试仅增加1笔并关闭。另含真实鼠标三连点场景。
- 浏览器 pageerror=0。故意空表单校验产生字段错误日志，故意网络中断产生 `net::ERR_FAILED`，均属于失败夹具。
- 初次浏览器启动受系统沙箱限制，按工具审批后启动；早期夹具解析/选择器/计数等待失败已修正，未计为通过。

证据：仓库本地 `evidence/BIN-199/` 的 `node-tests.log`、`build.log`、`browser-final.log`、`browser-results.json` 及5张状态截图；证据包通过任务附件交付。

## 交测试与部署工程师-Sentinel的独立回归重点

1. 从本提交构建候选，部署/重启95须由技术总负责人-Atlas按候选申请授权，不能沿用只覆盖 `2efe93a` 的旧部署授权。
2. 约定测试账号登录95；新增数据开发任务，有效名称 `fix_` 前缀，完整选择类目、责任人、执行引擎、调度系统与周期。打开Network过滤 `createEtlTaskFront`。
3. 真实鼠标三连点「仅保存」及「保存并配置流程」；在表单校验慢、节点编码响应慢和保存响应慢时分别重复，并交替点击两个按钮。每次操作只允许1笔保存请求，期间两个按钮始终禁用。
4. 空名称/责任人/周期：零保存请求、具体字段提示、按钮恢复；修正后能提交。业务拒绝/网络失败：表单输入保留、具体反馈、按钮恢复，重试只增加1笔。
5. 成功「仅保存」关闭弹窗并刷新列表；「保存并配置流程」跳原编辑页；加载期遮罩/Escape/关闭按钮不能提前重置表单。
6. 编辑详情的任务配置保存事件兼容；详情主保存D-1/DEF-2具体错误继续显示；后端名称唯一性兜底保留。
7. 使用 `fix_` 自建应用任务并记录ID，仅清理本次创建且无依赖的对象。Hive/HDFS零操作；禁止删除历史/他人对象。

本轮是研发完成与自测，未推送、合入、发布或部署；真实95接口、权限、系统路由及独立验收均未覆盖。交技术总负责人-Atlas核证后安排测试与部署工程师-Sentinel；版本规划管理工程师-Roadmap据确切提交与独立结论登记。
