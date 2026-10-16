import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import axios from 'axios';
import * as vue from 'vue';
import { parse as parseModule } from '@babel/parser';
import { parse as parseSfc } from '@vue/compiler-sfc';
import { createRouter, createMemoryHistory, RouterView } from 'vue-router';

// Execute the real interceptors, API functions, page callbacks and lifecycle hooks.
// Only HTTP, auth/store/i18n/Element Plus and the host renderer are offline fixtures.
const read = path => readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');
const turn = () => new Promise(resolve => setImmediate(resolve));
const vueDeps = Object.fromEntries(Object.entries(vue).filter(([key]) => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(key)));
function evaluate(source, dependencies, result) {
  const ast = parseModule(source, { sourceType: 'module' });
  for (const node of [...ast.program.body].reverse()) {
    if (node.type === 'ImportDeclaration') source = source.slice(0, node.start) + source.slice(node.end);
  }
  source = source.replaceAll('import.meta.env', 'runtimeEnv')
    .replace(/export default service\s*;?/g, '').replace(/\bexport /g, '');
  return new Function(...Object.keys(dependencies), `${source}\nreturn ${result};`)(...Object.values(dependencies));
}

function client(name = 'request.js') {
  const messages = [], diagnostics = [];
  const ElMessage = options => messages.push(['message', options]);
  const note = kind => options => messages.push([kind, options]);
  const dependencies = {
    axios, runtimeEnv: {}, getToken: () => 'offline-token', getStoredLang: () => 'zh-CN',
    i18n: { global: { t: key => key } },
    errorCode: { default: 'default', 403: 'forbidden', 500: 'server', 601: 'warning' },
    tansParams: params => Object.entries(params).map(([k, v]) => `${k}=${v}&`).join(''),
    cache: { session: { getJSON: () => undefined, setJSON() {} } },
    ElMessage, ElNotification: { error: note('notification-error'), warning: note('notification-warning') },
    ElMessageBox: { confirm: () => Promise.reject(new Error('offline-dismiss')) },
    useUserStore: () => ({ logOut: async () => {} }),
    console: { log: (...args) => diagnostics.push(args), warn: (...args) => diagnostics.push(args) },
  };
  const loaded = evaluate(read(`utils/${name}`), dependencies, '{service, clearCancelTokens, cancelTokens}');
  return { ...loaded, messages, diagnostics };
}

function transport(service) {
  const requests = [];
  service.defaults.adapter = config => new Promise((resolve, reject) => {
    let done = false;
    function finish(action, value) {
      if (done) return;
      done = true;
      config.cancelToken?.unsubscribe(cancel);
      config.signal?.removeEventListener('abort', abort);
      action(value);
    }
    const cancel = error => finish(reject, error);
    const abort = () => cancel(new axios.CanceledError('signal aborted', config));
    config.cancelToken?.subscribe(cancel);
    config.signal?.addEventListener('abort', abort);
    requests.push({
      config, get done() { return done; },
      ok: (data = { code: 200, data: {} }) => finish(resolve,
        { config, data, request: { responseType: config.responseType }, status: 200, headers: {} }),
      fail: (status, data) => finish(reject, new axios.AxiosError(
        `Request failed with status code ${status}`, 'ERR_BAD_RESPONSE', config, {}, { status, data })),
      network: message => finish(reject, new axios.AxiosError(message, 'ERR_NETWORK', config)),
    });
  });
  return requests;
}

for (const name of ['request.js', 'requestAi.js', 'rpRequest.js']) {
  test(`${name}: explicit cancellation rejects, skips success, reaches finally, releases tokens`, async () => {
    const c = client(name), requests = transport(c.service);
    let succeeded = false, finalized = false;
    const result = c.service.get('/pending').then(() => { succeeded = true; })
      .catch(error => { assert.ok(axios.isCancel(error)); return 'cancelled'; })
      .finally(() => { finalized = true; });
    await turn();
    assert.equal(c.cancelTokens.size, 1);
    c.clearCancelTokens();
    assert.equal(await result, 'cancelled');
    assert.equal(requests[0].done, true);
    assert.equal(succeeded, false);
    assert.equal(finalized, true);
    assert.equal(c.cancelTokens.size, 0);
    assert.deepEqual(c.messages, []);
  });

  test(`${name}: caller token/signal survive; cancellation classification ignores translated text`, async () => {
    for (const kind of ['token', 'signal']) {
      const c = client(name), requests = transport(c.service);
      const owner = kind === 'token' ? axios.CancelToken.source() : new AbortController();
      const config = kind === 'token' ? { cancelToken: owner.token } : { signal: owner.signal };
      const result = c.service.get('/owned', config).then(() => assert.fail('cancelled request succeeded'),
        error => assert.ok(axios.isCancel(error)));
      await turn();
      assert.equal(requests[0].config[kind === 'token' ? 'cancelToken' : 'signal'],
        kind === 'token' ? owner.token : owner.signal);
      assert.equal(c.cancelTokens.size, 0);
      c.clearCancelTokens();
      assert.equal(requests[0].done, false);
      if (kind === 'token') owner.cancel('任意语言的取消原因'); else owner.abort();
      await result;
      assert.deepEqual(c.messages, []);
    }
  });

  test(`${name}: success/headers/binary unchanged and completed requests leave no tokens`, async () => {
    const c = client(name), requests = transport(c.service);
    const body = { code: 200, data: { rows: [{ id: '9007199254740993' }], total: 1 } };
    const result = c.service.get('/normal');
    await turn(); requests[0].ok(body);
    assert.deepEqual(await result, body);
    assert.equal(requests[0].config.headers[name === 'rpRequest.js' ? 'token' : 'Authorization'], 'Bearer offline-token');
    assert.equal(requests[0].config.headers['accept-language'], 'zh-CN');
    assert.equal(c.cancelTokens.size, 0);
    const binary = c.service.get('/export', { responseType: 'blob' });
    await turn(); const blob = new Blob(['offline']); requests[1].ok(blob);
    assert.equal(await binary, blob);
    c.clearCancelTokens();
    assert.deepEqual(c.messages, []);
  });

  test(`${name}: real HTTP/business/network failures stay rejected with existing UI feedback`, async () => {
    for (const status of [403, 500, 503]) {
      const c = client(name), requests = transport(c.service);
      const result = c.service.get('/error').then(() => assert.fail('HTTP failure succeeded'), error => error);
      await turn(); requests[0].fail(status, { code: status, msg: 'dependency unavailable' });
      assert.equal((await result).response.status, status);
      assert.equal(c.messages.length, 1);
      assert.equal(c.cancelTokens.size, 0);
      if (name === 'request.js' && status === 503)
        assert.equal(c.messages[0][1].message, 'dependency unavailable');
    }
    for (const code of [403, 500, 601]) {
      const c = client(name), requests = transport(c.service);
      const result = c.service.get('/business').then(() => assert.fail('business failure succeeded'), error => error);
      await turn(); requests[0].ok({ code, msg: 'business failed' });
      const error = await result;
      assert.equal(code === 403 ? error : error.message, code === 403 ? 'error' : code === 500 ? 'server' : 'warning');
      assert.equal(c.messages.length, 1);
      assert.equal(c.cancelTokens.size, 0);
    }
    for (const message of ['Network Error', 'timeout after 100ms', 'Route change: Request canceled']) {
      const c = client(name), requests = transport(c.service);
      const result = c.service.get('/network').then(() => assert.fail('network failure succeeded'), error => error);
      await turn(); requests[0].network(message);
      assert.equal((await result).message, message);
      assert.equal(c.messages.length, 1, 'ordinary errors must not be mistaken for cancellations by text');
      assert.equal(c.cancelTokens.size, 0);
    }
  });

  test(`${name}: request setup rejection remains the original failure`, async () => {
    const c = client(name), requests = transport(c.service), error = new Error('request setup failed');
    c.service.interceptors.request.use(() => Promise.reject(error));
    await assert.rejects(c.service.get('/setup'), actual => actual === error);
    assert.equal(requests.length, 0);
    assert.equal(c.cancelTokens.size, 0);
    assert.equal(c.messages.length, 1);
  });
}

test('main hidden-error option and AI compile whitelist keep their existing semantics', async () => {
  const main = client(), requests = transport(main.service);
  const result = main.service.get('/hidden', { headers: { hideErrorMessage: true } }).catch(error => error);
  await turn(); requests[0].ok({ code: 500 });
  assert.equal((await result).message, 'server');
  assert.deepEqual(main.messages, []);
  const ai = client('requestAi.js'), aiRequests = transport(ai.service);
  const aiResult = ai.service.get('/dpp/etlTask/compile/check').catch(error => error);
  await turn(); aiRequests[0].ok({ code: 500, msg: 'compile failed' });
  assert.deepEqual(await aiResult, { code: 500, msg: 'compile failed' });
  assert.deepEqual(ai.messages, []);
  assert.equal(ai.cancelTokens.size, 0);
});

function api(service) {
  const datasource = evaluate(read('api/da/dataSource/dataSource.js'), { request: service },
    '{ listDaDatasource, listDaDatasourceByProjectCode, datasourceCapabilities, noDppAdd }');
  const user = evaluate(read('api/system/system/user.js'), { request: service }, '{deptUserTree}');
  const dict = evaluate(read('api/system/system/dict/data.js'), { request: service }, '{getDicts}');
  return { ...datasource, ...user, ...dict };
}

function dictionary(apis, errors) {
  const cache = new Map();
  const useDict = evaluate(read('utils/dict.js'), {
    axios, ...vueDeps, getDicts: apis.getDicts,
    useDictStore: () => ({ getDict: key => cache.get(key), setDict: (key, value) => cache.set(key, value) }),
    i18n: { global: { t: key => key } }, console: { error: (...args) => errors.push(args) },
  }, 'useDict');
  return { useDict, cache };
}

// Minimal host renderer; Vue itself owns KeepAlive and component disposal.
const renderer = vue.createRenderer({
  createElement: type => ({ type, children: [] }), createText: text => ({ text }), createComment: text => ({ text }),
  insert(child, parent, anchor) {
    if (child.parent) { const children = child.parent.children; children.splice(children.indexOf(child), 1); }
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    parent.children.splice(index < 0 ? parent.children.length : index, 0, child); child.parent = parent;
  },
  remove(child) {
    if (child.parent) { const list = child.parent.children; list.splice(list.indexOf(child), 1); child.parent = null; }
  },
  parentNode: child => child.parent, nextSibling: child => child.parent?.children[child.parent.children.indexOf(child) + 1],
  setText(node, text) { node.text = text; }, setElementText(node, text) { node.text = text; }, patchProp() {},
});

function page(module, apis, dict, errors, state) {
  return {
    name: module === 'dpp' ? 'DppDataSource' : 'DataSource',
    setup() {
      const source = parseSfc(read(`views/${module}/datasource/index.vue`)).descriptor.scriptSetup.content;
      const nodes = parseModule(source, { sourceType: 'module' }).program.body;
      const functions = new Set(['getList', 'getProjectOptions', 'getListProject']);
      const bodies = nodes.filter(n => n.type === 'FunctionDeclaration' && functions.has(n.id.name))
        .map(n => source.slice(n.start, n.end)).join('\n');
      const lifecycle = source.slice(source.indexOf('let isDatasourcePageActive'), source.indexOf('/*** User Import Parameters */'));
      const watcher = nodes.find(n => n.type === 'ExpressionStatement'
        && source.slice(n.start, n.end).startsWith('watch(\n  () => userStore.projectCode'));
      const refs = Object.fromEntries(['daDatasourceList', 'projectOptions', 'connectionProjects', 'projectList',
        'projectIdAndCodeList'].map(key => [key, vue.ref([])]));
      Object.assign(refs, {
        loading: vue.ref(false), loadingProject: vue.ref(false), openProject: vue.ref(false),
        total: vue.ref(0), totalProject: vue.ref(0), connectionCanAdd: vue.ref(false),
        queryParams: vue.ref({}), queryParamsProject: vue.ref({}), form: vue.ref({ projectList: [] }),
        userStore: vue.reactive({ projectId: '5', projectCode: 'p5' }),
      });
      const selections = [];
      const dependencies = { ...vueDeps, axios, ...apis, ...refs, type: module === 'dpp' ? 1 : 0,
        proxy: { $refs: { projectTableRef: { toggleRowSelection: (...args) => selections.push(args) } } },
        console: { error: (...args) => errors.push(args) },
      };
      const callbacks = new Function(...Object.keys(dependencies),
        `let restoringProjectSelection = false;\n${lifecycle}\n${bodies}\n${watcher ? source.slice(watcher.start, watcher.end) : ''}
         return {getList, getProjectOptions, getListProject};`)(...Object.values(dependencies));
      const dictRefs = dict.useDict('datasource_type', 'sys_disable');
      Object.assign(state, refs, callbacks, { dictRefs, selections });
      // Execute the actual final initialization statements, after default sorting is set.
      const initial = source.slice(source.lastIndexOf('queryParams.value.orderByColumn ='));
      new Function('queryParams', 'defaultSort', 'getList', 'getProjectOptions', initial)(
        refs.queryParams, vue.ref({ prop: 'createTime', order: 'desc' }), callbacks.getList, callbacks.getProjectOptions);
      return () => null;
    },
  };
}

async function harness(t, module) {
  const c = client(), requests = transport(c.service), errors = [], state = {};
  const apis = api(c.service), dict = dictionary(apis, errors);
  const target = vue.ref(null);
  const routes = [
    { path: '/source', component: { render: () => null } },
    { path: '/datasource', component: page(module, apis, dict, errors, state) },
    { path: '/development', component: { setup() {
      c.service.get('/target').then(response => { target.value = response.data; }).catch(error => errors.push(error));
      return () => null;
    } } },
  ];
  const routerSource = read('router/index.js');
  const dependencies = {};
  for (const node of parseModule(routerSource, { sourceType: 'module' }).program.body) {
    if (node.type === 'ImportDeclaration') for (const spec of node.specifiers) dependencies[spec.local.name] = [];
  }
  Object.assign(dependencies, { createRouter: options => createRouter({ ...options, routes }), createWebHistory: createMemoryHistory,
    clearCancelTokens: c.clearCancelTokens });
  const router = evaluate(routerSource.replace('export default router;', ''), dependencies, 'router');
  const app = renderer.createApp({ render: () => vue.h(RouterView, {}, {
    default: ({ Component }) => vue.h(vue.KeepAlive, {}, () => Component ? vue.h(Component) : null),
  }) });
  app.config.errorHandler = error => errors.push(error);
  app.use(router);
  await router.push('/source');
  app.mount({ children: [] });
  t.after(() => app.unmount());
  await router.push('/datasource'); await turn();
  return { c, requests, errors, state, dict, target, router, app };
}

function successfulBody(request, marker = 'fresh') {
  const url = request.config.url;
  if (url.includes('/dict/')) return { code: 200, data: [{ dictLabel: marker, dictValue: '1' }] };
  if (url.includes('/userDeptTree')) return { code: 200, data: [{ userId: '1', nickName: marker }] };
  if (url.includes('/capabilities')) return { code: 200, data: { projects: [{ code: 'p5', managerId: '1', nickName: marker }], canAdd: true } };
  if (url === '/target') return { code: 200, data: { ready: true } };
  return { code: 200, data: { rows: [{ id: marker }], total: 1 } };
}

for (const module of ['dpp', 'da']) {
  test(`${module}: rapid navigation settles five original callback paths, ignores stale data, reloads cached page`, async t => {
    const h = await harness(t, module);
    const lists = h.requests.filter(r => r.config.url.includes('/list?'));
    assert.equal(lists.length, 1, 'initialization starts one list request');
    // Exercise the original second-list callback too, without reintroducing duplicate initialization.
    const second = h.state.getList(); await turn();
    assert.equal(h.requests.length, 5);
    await h.router.push('/development'); await turn();
    assert.equal(h.state.loading.value, false);
    for (const request of h.requests) request.ok(successfulBody(request, 'stale'));
    await second; await turn();
    assert.deepEqual(h.errors, []);
    assert.deepEqual(h.state.daDatasourceList.value, []);
    assert.deepEqual(h.state.projectOptions.value, []);
    assert.equal(h.target.value.ready, true);
    assert.equal(h.dict.cache.size, 2, 'shared dictionaries complete instead of being route-cancelled');
    assert.deepEqual(h.state.dictRefs.datasource_type.value, [], 'cached page is not written while inactive');
    assert.equal(h.c.cancelTokens.size, 0);
    await h.router.push('/datasource'); await turn();
    assert.equal(h.state.loading.value, true);
    assert.equal(h.state.dictRefs.datasource_type.value[0].label, 'stale', 'activation restores the shared dictionary cache');
    const fresh = h.requests.filter(r => !r.done);
    assert.equal(fresh.length, 2, 'returning to cache reloads list and options once');
    for (const request of fresh) request.ok(successfulBody(request));
    await turn();
    assert.equal(h.state.daDatasourceList.value[0].id, 'fresh');
    assert.equal(h.state.loading.value, false);
    assert.deepEqual(h.errors, []);
  });

  test(`${module}: older completion cannot overwrite newer result or clear its loading state`, async t => {
    const h = await harness(t, module);
    const first = h.requests.find(r => r.config.url.includes('/list?'));
    const next = h.state.getList(); await turn();
    const second = h.requests.at(-1);
    first.ok(successfulBody(first, 'old')); await turn();
    assert.equal(h.state.loading.value, true);
    assert.deepEqual(h.state.daDatasourceList.value, []);
    second.ok(successfulBody(second, 'new')); await next;
    assert.equal(h.state.daDatasourceList.value[0].id, 'new');
    assert.equal(h.state.loading.value, false);
    for (const request of h.requests) request.ok(successfulBody(request));
    await turn(); assert.deepEqual(h.errors, []);
  });

  test(`${module}: explicit transport cancellation is handled locally, with no success write or stuck loading`, async t => {
    const h = await harness(t, module);
    h.c.clearCancelTokens(); await turn();
    assert.ok(h.requests.every(r => r.done));
    assert.equal(h.c.cancelTokens.size, 0);
    assert.equal(h.state.loading.value, false);
    assert.deepEqual(h.state.daDatasourceList.value, []);
    assert.deepEqual(h.errors, []);
    assert.deepEqual(h.c.messages, []);
  });

  test(`${module}: real failure still displays feedback and diagnostics, then releases loading`, async t => {
    const h = await harness(t, module);
    for (const request of h.requests) {
      if (request.config.url.includes('/list?')) request.fail(503, { code: 503, msg: 'engine unavailable' });
      else request.ok(successfulBody(request));
    }
    await turn();
    assert.equal(h.c.messages[0][1].message, 'engine unavailable');
    assert.equal(h.errors.length, 1);
    assert.equal(h.errors[0][0], 'Failed to load datasources');
    assert.equal(h.state.loading.value, false);
  });

  test(`${module}: rejected navigation keeps the current page active`, async t => {
    const h = await harness(t, module);
    const removeGuard = h.router.beforeEach(to => to.path === '/development' ? false : undefined);
    await h.router.push('/development');
    assert.equal(h.router.currentRoute.value.path, '/datasource');
    for (const request of h.requests) request.ok(successfulBody(request));
    await turn(); removeGuard();
    assert.equal(h.state.daDatasourceList.value[0].id, 'fresh');
    assert.equal(h.state.loading.value, false);
    assert.deepEqual(h.errors, []);
  });

  test(`${module}: late responses from a previous activation cannot overwrite the returned page`, async t => {
    const h = await harness(t, module), old = [...h.requests];
    await h.router.push('/development'); await turn();
    await h.router.push('/datasource'); await turn();
    const current = h.requests.filter(r => !old.includes(r));
    for (const request of current) request.ok(successfulBody(request, 'current'));
    await turn();
    for (const request of old) request.ok(successfulBody(request, 'old'));
    await turn();
    assert.equal(h.state.daDatasourceList.value[0].id, 'current');
    assert.equal(h.state.projectOptions.value[0].nickName, 'current');
    assert.equal(h.state.loading.value, false);
    assert.deepEqual(h.errors, []);
  });

  test(`${module}: unmount ignores pending page/dictionary writes and still settles requests`, async t => {
    const h = await harness(t, module);
    h.app.unmount();
    for (const request of h.requests) request.ok(successfulBody(request));
    await turn();
    assert.deepEqual(h.state.daDatasourceList.value, []);
    assert.deepEqual(h.state.projectOptions.value, []);
    assert.deepEqual(h.state.dictRefs.datasource_type.value, []);
    assert.equal(h.dict.cache.size, 2);
    assert.equal(h.c.cancelTokens.size, 0);
    assert.deepEqual(h.errors, []);
  });

  test(`${module}: project-dialog loads have the same cancellation/stale/loading protection`, async t => {
    const h = await harness(t, module);
    for (const request of h.requests) request.ok(successfulBody(request));
    await turn();
    const first = h.state.getListProject(); await turn();
    const obsolete = h.requests.at(-1);
    const second = h.state.getListProject(); await turn();
    const current = h.requests.at(-1);
    obsolete.ok(successfulBody(obsolete, 'obsolete')); await first;
    assert.equal(h.state.loadingProject.value, true);
    current.ok(successfulBody(current, 'current')); await second;
    assert.equal(h.state.projectList.value[0].id, 'current');
    assert.equal(h.state.loadingProject.value, false);
    const pending = h.state.getListProject(); await turn();
    await h.router.push('/development'); await turn();
    for (const request of h.requests) request.ok(successfulBody(request, 'departed'));
    await pending; await turn();
    assert.equal(h.state.projectList.value[0].id, 'current');
    assert.equal(h.state.loadingProject.value, false);
    assert.deepEqual(h.errors, []);
  });
}

test('dpp: project changes start one scoped reload and discard the previous project response', async t => {
  const h = await harness(t, 'dpp');
  const first = h.requests.find(r => r.config.url.includes('/list?'));
  h.state.userStore.projectId = '6'; h.state.userStore.projectCode = 'p6';
  await turn();
  const lists = h.requests.filter(r => r.config.url.includes('/list?'));
  assert.equal(lists.length, 2);
  assert.ok(lists[1].config.url.includes('projectId=6&projectCode=p6'));
  lists[1].ok(successfulBody(lists[1], 'p6')); await turn();
  first.ok(successfulBody(first, 'p5'));
  for (const request of h.requests) request.ok(successfulBody(request));
  await turn();
  assert.equal(h.state.daDatasourceList.value[0].id, 'p6');
  assert.equal(h.state.loading.value, false);
  assert.deepEqual(h.errors, []);
});
