import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRenderer, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { parse } from '@vue/compiler-sfc';
import bus from '../src/utils/bus.js';
import { PROJECT_OPTIONS_CHANGED, notifyProjectOptionsChanged } from '../src/utils/projectOptions.js';

// Execute the real composable with Vue lifecycle and the real bus; HTTP/storage are offline substitutes.
const source = readFileSync(new URL('../src/composables/useProjectOptions.js', import.meta.url), 'utf8')
  .replace(/^import .*;\n/gm, '').replace('export default function', 'function');
const renderer = createRenderer({
  createComment: () => ({}), createText: () => ({}), createElement: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null,
});
const projects = [
  { id: 1, name: '一', code: 'one', validFlag: true },
  { id: 2, name: '二', code: 'two', validFlag: true },
];
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const flush = () => new Promise(resolve => setImmediate(resolve));

function mount(t, { currentId = 2, savedId = '1', list, routes, store } = {}) {
  const requests = [], menuRequests = [], menuUpdates = [], errors = [];
  const storage = new Map(savedId == null ? [] : [['qdataProjectId', savedId]]);
  const localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key),
  };
  const userStore = store || reactive({ id: 1, projectId: currentId, projectCode: 'old' });
  const permissionStore = {
    topbarRouters: [{ path: '/att', children: [{ path: 'project' }] }, { path: '/dpp', children: [{ path: 'old' }] }],
    updateTopbarRoutes(data) { menuUpdates.push(data); this.topbarRouters = data; },
  };
  const useProjectOptions = new Function('onBeforeUnmount', 'onMounted', 'ref', 'currentUser',
    'getRoutersDpp', 'bus', 'PROJECT_OPTIONS_CHANGED', 'localStorage', `${source}; return useProjectOptions;`)(
    onBeforeUnmount, onMounted, ref,
    () => { requests.push('currentUser'); return list ? list() : Promise.resolve({ code: 200, data: projects }); },
    id => {
      menuRequests.push(id);
      return routes ? routes(id) : Promise.resolve({ code: 200, data: [
        { path: '/att', children: [{ path: 'project' }] }, { path: '/dpp', children: [{ path: String(id) }] },
      ] });
    }, bus, PROJECT_OPTIONS_CHANGED, localStorage,
  );
  let api;
  const app = renderer.createApp({ setup() { api = useProjectOptions(userStore, permissionStore); return () => null; } });
  app.config.errorHandler = error => errors.push(error);
  app.mount({});
  let unmounted = false;
  const unmount = () => { if (!unmounted) { app.unmount(); unmounted = true; } };
  t.after(unmount);
  return { ...api, userStore, permissionStore, requests, menuRequests, menuUpdates, storage, errors, unmount };
}

function loadApi(relativePath, request) {
  const script = readFileSync(new URL(relativePath, import.meta.url), 'utf8')
    .replace(/^import .*;\n/gm, '').replace(/export function /g, 'function ');
  return new Function('request', 'notifyProjectOptionsChanged', `${script}; return {
    ${[...script.matchAll(/^function (\w+)\(/gm)].map(match => match[1]).join(',')}
  };`)(request, notifyProjectOptionsChanged);
}

const mutationCases = [
  ['项目新增', '../src/api/att/project/project.js', 'addAttProject', '/att/project', 'post'],
  ['项目编辑及负责人变更', '../src/api/att/project/project.js', 'updateAttProject', '/att/project', 'put'],
  ['项目启停', '../src/api/att/project/project.js', 'editProjectStatus', '/att/project/editProjectStatus/4/1', 'get'],
  ['项目删除', '../src/api/att/project/project.js', 'delAttProject', '/att/project/4', 'delete'],
  ['新增成员', '../src/api/att/projectUserRel/attProjectUserRel.js', 'addAttProjectUserRel', '/att/projectUserRel', 'post'],
  ['批量新增成员', '../src/api/att/projectUserRel/attProjectUserRel.js', 'addUserListAndRoleList', '/att/projectUserRel/addUserListAndRoleList', 'post'],
  ['编辑成员', '../src/api/att/projectUserRel/attProjectUserRel.js', 'updateAttProjectUserRel', '/att/projectUserRel', 'put'],
  ['编辑成员和角色', '../src/api/att/projectUserRel/attProjectUserRel.js', 'editUserListAndRoleList', '/att/projectUserRel/editUserListAndRoleList', 'put'],
  ['移除成员', '../src/api/att/projectUserRel/attProjectUserRel.js', 'delAttProjectUserRel', '/att/projectUserRel/4', 'delete'],
];

for (const [label, path, name, url, method] of mutationCases) {
  test(`${label}：仅业务成功通知，保留请求及响应/拒绝契约`, async t => {
    const events = [], requests = [];
    const handler = () => events.push(label);
    bus.on(PROJECT_OPTIONS_CHANGED, handler);
    t.after(() => bus.off(PROJECT_OPTIONS_CHANGED, handler));
    const response = { code: 200, data: { id: 4 } };
    let result = response;
    const error = new Error('HTTP/business rejected');
    const api = loadApi(path, config => { requests.push(config); return result === error ? Promise.reject(error) : Promise.resolve(result); });
    assert.equal(await api[name](4, 1), response);
    assert.deepEqual(events, [label]);
    assert.equal(requests[0].url, url);
    assert.equal(requests[0].method, method);
    result = { code: 500 };
    assert.equal(await api[name](4, 1), result);
    result = error;
    await assert.rejects(api[name](4, 1), error);
    assert.equal(events.length, 1);
  });
}

test('只读项目/成员 API 不发变更通知', async t => {
  let events = 0;
  const handler = () => events++;
  bus.on(PROJECT_OPTIONS_CHANGED, handler);
  t.after(() => bus.off(PROJECT_OPTIONS_CHANGED, handler));
  const request = async () => ({ code: 200, data: [] });
  const projectApi = loadApi('../src/api/att/project/project.js', request);
  const memberApi = loadApi('../src/api/att/projectUserRel/attProjectUserRel.js', request);
  await Promise.all([projectApi.currentUser(), projectApi.getAttProject(1), projectApi.listAttProject({}), memberApi.listAttProjectUserRel({})]);
  assert.equal(events, 0);
});

test('新增项目成功后同会话更新选项，保留有效当前项目并刷新其权限', async t => {
  let data = projects;
  const h = mount(t, { list: async () => ({ code: 200, data }) });
  await flush();
  data = [...projects, { id: 4, code: 'new', name: '新增项目', validFlag: true }];
  const api = loadApi('../src/api/att/project/project.js', async () => ({ code: 200 }));
  await api.addAttProject({ name: '新增项目' });
  await flush();
  assert.deepEqual(h.projectOptions.value.map(p => p.id), [1, 2, 4]);
  assert.equal(h.userStore.projectId, 2);
  assert.equal(h.userStore.projectCode, 'two');
  assert.equal(h.storage.get('qdataProjectId'), '2');
  assert.deepEqual(h.menuRequests, [2, 2]);
});

test('编辑项目名称/代码后保留当前项目并显示新内容', async t => {
  const h = mount(t, { list: async () => ({ code: 200, data: [projects[0], { ...projects[1], name: '改名', code: 'renamed' }] }) });
  await flush();
  assert.equal(h.projectOptions.value[1].name, '改名');
  assert.equal(h.userStore.projectId, 2);
  assert.equal(h.userStore.projectCode, 'renamed');
});

for (const mode of ['disabled', 'removed']) {
  test(`${mode}：禁用或失去访问权时降级到首个有效项目并覆盖缓存`, async t => {
    const data = mode === 'disabled' ? [projects[0], { ...projects[1], validFlag: false }] : [projects[0]];
    const h = mount(t, { list: async () => ({ code: 200, data }) });
    await flush();
    assert.deepEqual(h.projectOptions.value.map(p => p.id), [1]);
    assert.equal(h.userStore.projectId, 1);
    assert.equal(h.userStore.projectCode, 'one');
    assert.equal(h.storage.get('qdataProjectId'), '1');
    assert.deepEqual(h.menuRequests, [1]);
    assert.equal(h.permissionStore.topbarRouters[0].path, '/att');
  });
}

test('没有可用项目时清除 ID/代码/缓存和研发菜单，保留其他导航', async t => {
  const h = mount(t, { list: async () => ({ code: 200, data: projects.map(p => ({ ...p, validFlag: false })) }) });
  await flush();
  assert.equal(h.userStore.projectId, null);
  assert.equal(h.userStore.projectCode, '');
  assert.equal(h.storage.has('qdataProjectId'), false);
  assert.deepEqual(h.menuRequests, []);
  assert.deepEqual(h.permissionStore.topbarRouters, [
    { path: '/att', children: [{ path: 'project' }] }, { path: '/dpp', children: [] },
  ]);
});

test('初次挂载恢复缓存仅接受列表内 ID，Long 字符串不丢精度', async t => {
  const id = '9007199254740999';
  const h = mount(t, { currentId: '', savedId: id, list: async () => ({ code: 200, data: [...projects, { id, code: 'long', validFlag: true }] }) });
  await flush();
  assert.equal(h.userStore.projectId, id);
  assert.deepEqual(h.menuRequests, [id]);
  h.unmount();
  const expired = mount(t, { currentId: '', savedId: 'expired' });
  await flush();
  assert.equal(expired.userStore.projectId, 1);
  assert.equal(expired.storage.get('qdataProjectId'), '1');
});

test('同批重复通知只请求一次，进行中变更只补一次且旧响应不覆盖', async t => {
  const first = deferred();
  let calls = 0;
  const h = mount(t, { list: () => ++calls === 1 ? first.promise : Promise.resolve({ code: 200, data: projects }) });
  bus.emit(PROJECT_OPTIONS_CHANGED);
  bus.emit(PROJECT_OPTIONS_CHANGED);
  await flush();
  assert.equal(calls, 1);
  bus.emit(PROJECT_OPTIONS_CHANGED);
  bus.emit(PROJECT_OPTIONS_CHANGED);
  first.resolve({ code: 200, data: [{ id: 'stale' }] });
  await flush();
  assert.equal(calls, 2);
  assert.equal(h.userStore.projectId, 2);
  assert.deepEqual(h.menuRequests, [2]);
});

test('卸载移除同一监听，晚到响应不写状态，再挂载无叠加请求', async t => {
  const first = deferred();
  const count = () => bus.events[PROJECT_OPTIONS_CHANGED]?.length || 0;
  const initial = count();
  const h = mount(t, { list: () => first.promise });
  await flush();
  assert.equal(count(), initial + 1);
  h.unmount();
  assert.equal(count(), initial);
  first.resolve({ code: 200, data: projects });
  await flush();
  assert.deepEqual(h.projectOptions.value, []);
  assert.equal(h.menuUpdates.length, 0);
  assert.deepEqual(h.errors, []);
  const next = mount(t);
  await flush();
  bus.emit(PROJECT_OPTIONS_CHANGED);
  await flush();
  assert.equal(next.requests.length, 2);
  assert.equal(h.requests.length, 1);
  next.unmount();
  assert.equal(count(), initial);
});

test('请求收尾微任务中的变更通知不会丢失', async t => {
  const h = mount(t);
  const update = h.permissionStore.updateTopbarRoutes.bind(h.permissionStore);
  let notified = false;
  h.permissionStore.updateTopbarRoutes = data => {
    update(data);
    if (!notified) {
      notified = true;
      queueMicrotask(() => bus.emit(PROJECT_OPTIONS_CHANGED));
    }
  };
  await flush();
  assert.equal(h.requests.length, 2);
  assert.equal(h.projectOptionsLoading.value, false);
});

for (const mode of ['network', 'business', 'malformed', 'routes']) {
  test(`${mode} 失败不保留未核实项目，重试可恢复`, async t => {
    let failed = true;
    const h = mount(t, {
      list: async () => {
        if (failed && mode === 'network') throw new Error('request failed');
        if (failed && mode === 'business') return { code: 403, data: projects };
        if (failed && mode === 'malformed') return { code: 200, data: null };
        return { code: 200, data: projects };
      },
      routes: async () => {
        if (failed && mode === 'routes') throw new Error('forbidden');
        return { code: 200, data: [{ path: '/dpp', children: [] }] };
      },
    });
    await flush();
    assert.equal(h.userStore.projectId, null);
    assert.equal(h.storage.has('qdataProjectId'), false);
    assert.equal(h.projectOptionsLoading.value, false);
    failed = false;
    await h.refreshProjectOptions();
    assert.equal(h.userStore.projectId, 1);
    assert.equal(h.projectOptions.value.length, 2);
  });
}

test('用户退出后的列表响应和手工切换后的旧菜单响应不能写回', async t => {
  const first = deferred();
  const h = mount(t, { list: () => first.promise });
  await flush();
  h.userStore.id = '';
  h.userStore.projectId = '';
  first.resolve({ code: 200, data: projects });
  await flush();
  assert.deepEqual(h.projectOptions.value, []);
  assert.deepEqual(h.menuRequests, []);
  const menu = deferred();
  const next = mount(t, { routes: () => menu.promise });
  await flush();
  next.userStore.projectId = 1;
  menu.resolve({ code: 200, data: [{ path: '/stale' }] });
  await flush();
  assert.equal(next.menuUpdates.length, 0);
});

test('旧项目菜单请求失败不会清除后来手工选中的项目', async t => {
  const menu = deferred();
  const h = mount(t, { routes: () => menu.promise });
  await flush();
  h.userStore.projectId = 1;
  h.userStore.projectCode = 'one';
  h.storage.set('qdataProjectId', '1');
  menu.reject(new Error('old project forbidden'));
  await flush();
  assert.equal(h.userStore.projectId, 1);
  assert.equal(h.userStore.projectCode, 'one');
  assert.equal(h.storage.get('qdataProjectId'), '1');
});

test('Navbar 复用单一生命周期链路并在展开时刷新，无旧挂载请求', () => {
  const page = parse(readFileSync(new URL('../src/layout/components/Navbar.vue', import.meta.url), 'utf8')).descriptor;
  assert.match(page.scriptSetup.content, /useProjectOptions\(\s*userStore, permissionStore, beginDppMenuUpdate\s*\)/);
  assert.doesNotMatch(page.scriptSetup.content, /const listProject|listProject\(\)/);
  assert.match(page.template.content, /:loading="projectOptionsLoading"/);
  assert.match(page.template.content, /@visible-change="visible => visible && refreshProjectOptions\(\)"/);
  assert.match(page.scriptSetup.content, /String\(requestedProjectId\) === String\(userStore.projectId\)/);
});
