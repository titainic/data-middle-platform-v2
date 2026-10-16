import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { parse } from '@vue/compiler-sfc';
import { ref, reactive } from 'vue';
import zh from '../src/locales/zh-CN/dpp/index.js';
import en from '../src/locales/en-US/dpp/index.js';
import ja from '../src/locales/ja-JP/dpp/index.js';
import sysZh from '../src/locales/zh-CN/sys/index.js';

// Execute real handlers/template expressions and QtTable settlement with substituted HTTP/state.
// No browser, server, credentials or database is used.
const page = path => parse(readFileSync(new URL('../src/' + path, import.meta.url), 'utf8')).descriptor;
const lookup = (dict, key) => key.split('.').slice(1).reduce((value, part) => value?.[part], dict);
const td = key => lookup(zh, key) || key;
const evaluate = (expression, deps) => new Function(...Object.keys(deps), `return (${expression});`)(...Object.values(deps));
const walk = (node, predicate) => [ ...(predicate(node) ? [node] : []), ...(node.children || []).flatMap(child => walk(child, predicate)) ];
const directive = (node, name, arg) => node.props?.find(p => p.type === 7 && p.name === name && (arg === undefined || p.arg?.content === arg));
const attribute = (node, name) => node.props?.find(p => p.type === 6 && p.name === name)?.value?.content;
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const previewPage = page('views/da/asset/detail/table/preview.vue');
const previewScript = previewPage.scriptSetup.content.replace(/^import .*;$/gm, '');
const qtScript = page('components/QtTable/index.vue').scriptSetup.content;
const listHandler = qtScript.slice(qtScript.indexOf('function getList()'), qtScript.indexOf('// Reset data'));
function previewHarness() {
  let response = { data: { columns: [{ en: 'id', cn: '编号', field: 'id' }], tableData: [{ id: 1 }, { id: 2 }, { id: 3 }], total: 3 } }, failure;
  const route = { query: { id: "9007199254740993" }, path: "/da/asset/detail" };
  let routeWatcher;
  const requests = [], dependencies = {
    ref, reactive, watch(source, callback) { routeWatcher = callback; }, defineProps: () => ({ form1: { type: '6' } }),
    useRoute: () => route, useDefaultLang: () => ({ td }),
    getCurrentInstance: () => ({ proxy: {} }),
    preview: payload => { requests.push(payload); return failure ? Promise.reject(failure) : Promise.resolve(response); },
  };
  const state = new Function(...Object.keys(dependencies), `${previewScript}; return { tableStore, previewError: typeof previewError === "undefined" ? ref("") : previewError, loading };`)(...Object.values(dependencies));
  const store = { params: { pageNum: 1, pageSize: 10 }, data: [], total: 0 };
  const getList = new Function('store', 'props', 'config', `${listHandler}; return getList;`)(store, state.tableStore, { value: state.tableStore.config });
  return { ...state, store, requests, getList, switchAsset: id => { route.query.id = id; routeWatcher(id); }, fail: error => { failure = error; }, respond: value => { response = value; failure = undefined; } };
}
test('BIN-108 repeat rejection preserves three successful rows/count/columns', async () => {
  const h = previewHarness(); h.getList(); await flush(); assert.equal(h.store.total, 3);
  const columns = JSON.stringify(h.tableStore.columns);
  h.fail(Object.assign(new Error('repeat'), { isRepeatSubmit: true })); h.getList(); await flush();
  assert.deepEqual(h.store.data, [{ id: 1 }, { id: 2 }, { id: 3 }]); assert.equal(h.store.total, 3);
  assert.equal(JSON.stringify(h.tableStore.columns), columns); assert.equal(h.previewError.value, '');
  assert.equal(h.loading.value, false); assert.equal(h.store.loading, false);
});
test('BIN-108 transport failure marks stale results; retry and real empty response update normally', async () => {
  const h = previewHarness(); h.getList(); await flush();
  h.fail(new Error('network reset')); h.getList(); await flush();
  assert.equal(h.store.total, 3); assert.equal(h.store.data.length, 3); assert.match(h.previewError.value, /未更新/);
  h.respond({ data: { columns: [], tableData: [], total: 0 } }); h.getList(); await flush();
  assert.equal(h.store.total, 0); assert.deepEqual(h.store.data, []); assert.equal(h.previewError.value, '');
});
test('BIN-108 initial failure is visibly different from success with zero rows', async () => {
  const h = previewHarness(); h.fail(new Error('offline')); h.getList(); await flush();
  assert.match(h.previewError.value, /加载失败/); assert.equal(h.store.total, 0);
  const alert = walk(previewPage.template.ast, n => n.tag === 'el-alert' && directive(n, 'if')?.exp?.content === 'previewError')[0];
  assert.ok(alert, 'Initial preview failure needs a visible failure state');
  assert.equal(directive(alert, 'bind', 'title').exp.content, 'previewError');
});
test('BIN-108 pagination and Long ID reach preview unchanged', async () => {
  const h = previewHarness(); h.store.params = { pageNum: 2, pageSize: 20 }; h.getList(); await flush();
  assert.equal(h.requests[0].pageNum, 2); assert.equal(h.requests[0].pageSize, 20); assert.equal(h.requests[0].id, '9007199254740993');
});
const apiForm = page('views/da/asset/add/apiAdd.vue');
for (const [locale, dict] of Object.entries({ zh, en, ja })) test(`BIN-105 ${locale} required labels/placeholders/validators resolve`, () => {
  const translate = key => lookup(dict, key);
  const fields = walk(apiForm.template.ast, n => n.tag === 'el-form-item' && attribute(n, 'prop')?.startsWith('daAssetApi.'));
  assert.equal(fields.length, 4);
  for (const field of fields) {
    assert.ok(evaluate(directive(field, 'bind', 'label').exp.content, { td: translate })?.trim());
    const validators = evaluate(directive(field, 'bind', 'rules').exp.content, { td: translate });
    assert.equal(validators[0].required, true); assert.ok(validators[0].message?.trim());
    const input = walk(field, n => ['el-input', 'el-select'].includes(n.tag))[0];
    assert.ok(evaluate(directive(input, 'bind', 'placeholder').exp.content, { td: translate })?.trim());
  }
});
test('BIN-79 name/nickname labels differ and each real toolbar action toggles only its column', () => {
  const source = page('views/sys/system/user/index.vue').scriptSetup.content;
  const columns = evaluate(source.match(/const columns = ref\((\[[\s\S]*?\])\);/)[1], { td: key => key.startsWith('sys.') ? lookup(sysZh, key) : key });
  assert.equal(columns[1].label, '用户名称'); assert.equal(columns[2].label, '用户昵称');
  assert.equal(new Set(columns.map(c => c.label)).size, columns.length);
  const handler = page('components/RightToolbar/index.vue').scriptSetup.content.match(/function checkboxChange\([^]*?\n\}/)[0];
  const toggle = new Function('props', `${handler}; return checkboxChange;`)({ columns });
  for (const index of [1, 2]) {
    toggle(false, columns[index].label); assert.deepEqual(columns.map(c => c.visible), columns.map((_, i) => i !== index));
    toggle(true, columns[index].label);
  }
});
const qualityScript = page('views/da/asset/detail/components/qualityTaskAdd.vue').scriptSetup.content;
const convert = qualityScript.slice(qualityScript.indexOf('function convertAssetToTask'), qualityScript.indexOf('let loadingInstance'));
const inspect = qualityScript.slice(qualityScript.indexOf('async function selectInspectionRule()'), qualityScript.indexOf('const inspectionTargetDialog'));
async function qualityRequest(asset) {
  const requests = [], loading = ref(false), deps = { loading, originList: ref([]), dppQualityTaskEvaluateSaveReqVO: ref([]), td,
    dppQualityTaskObjSaveReqVO: ref([]), renameRuleToRuleConfig: () => [], ElMessage: {},
    getColumnByAssetId: async payload => { requests.push(payload); return { data: [] }; } };
  const run = new Function(...Object.keys(deps), `${convert}; ${inspect}; return async asset => {
    dppQualityTaskObjSaveReqVO.value = convertAssetToTask(asset); await selectInspectionRule(); return dppQualityTaskObjSaveReqVO.value;
  };`)(...Object.values(deps));
  const objects = await run(asset); assert.equal(loading.value, false); return { requests, objects };
}
test('BIN-111 same short table in different Hive databases stays distinct', async () => {
  for (const databaseName of ['ods_iot_db', 'dwd_iot_db']) {
    const h = await qualityRequest({ datasourceId: '9007199254740993', datasourceType: 'Hive', databaseName, tableName: 'water_quality' });
    assert.deepEqual(h.requests, [{ id: '9007199254740993', withRule: 1, tableName: databaseName + '.water_quality' }]);
    assert.equal(h.objects[0].databaseName, databaseName); assert.equal(h.objects[0].tableName, 'water_quality');
  }
});
test('BIN-111 non-Hive, legacy defaults and qualified names retain their contract', async () => {
  for (const asset of [{ datasourceType: 'MySql', databaseName: 'business', tableName: 'orders' },
    { datasourceType: 'Hive', tableName: 'legacy_table' }, { datasourceType: 'hive', databaseName: 'ods_iot_db', tableName: 'ods_iot_db.water_quality' }]) {
    const h = await qualityRequest({ ...asset, datasourceId: 3 }); assert.equal(h.requests[0].tableName, asset.tableName);
  }
});
test('BIN-106 refresh menu is limited to editable/queryable database assets', () => {
  const menu = walk(page('views/da/asset/index.vue').template.ast, n => n.tag === 'el-dropdown-item')
    .find(n => walk(n, child => directive(child, 'on', 'click')?.exp?.content === 'handleRefresh(item)').length);
  const condition = directive(menu, 'if').exp.content;
  for (const type of ['1', 1, '2', 2, '6', 6]) assert.equal(Boolean(evaluate(condition,
    { item: { type, writeCapabilities: { edit: true }, readCapabilities: { query: true } }, unregistered: () => true })), String(type) === '1');
  assert.equal(Boolean(evaluate(condition, { item: { type: 1, writeCapabilities: { edit: true }, readCapabilities: {} }, unregistered: () => true })), false);
});
test('BIN-109 unavailable binding is disabled with an explanation in three languages', () => {
  const tooltip = walk(page('views/da/asset/detail/table/column.vue').template.ast, n => n.tag === 'el-tooltip')
    .find(n => walk(n, child => child.tag === 'el-button' && attribute(child, 'icon') === 'Pointer').length);
  assert.ok(tooltip, 'Unavailable action needs an explanatory tooltip');
  const button = walk(tooltip, n => n.tag === 'el-button')[0];
  assert.ok(button.props.some(p => p.type === 6 && p.name === 'disabled')); assert.equal(directive(button, 'on', 'click'), undefined);
  for (const dict of [zh, en, ja]) assert.ok(evaluate(directive(tooltip, 'bind', 'content').exp.content, { td: key => lookup(dict, key) })?.trim());
});


test('BIN-108 asset switches remount an empty table and ignore the previous asset response', async () => {
  const h = previewHarness();
  // Settle the first request only after the watcher has switched asset identity.
  h.getList();
  h.switchAsset('9007199254740994');
  await flush();
  assert.deepEqual(h.store.data, []); assert.equal(h.store.total, 0); assert.equal(h.previewError.value, '');
  const table = walk(previewPage.template.ast, n => n.tag === 'qt-table')[0];
  assert.equal(directive(table, 'bind', 'key').exp.content, 'route.query.id');
});
