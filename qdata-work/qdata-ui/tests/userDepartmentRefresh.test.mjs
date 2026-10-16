import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref, nextTick } from 'vue';
import { parse } from '@vue/compiler-sfc';

// 执行真实页面处理函数；仅替换 HTTP 和 Element Plus 实例，不访问或修改业务数据。
const page = parse(readFileSync(new URL('../src/views/sys/system/user/index.vue', import.meta.url), 'utf8')).descriptor;
const script = page.scriptSetup.content;
const state = script.slice(script.indexOf('const deptOptions ='), script.indexOf('const initPassword ='));
const refresh = script.slice(script.indexOf('function getDeptTree()'), script.indexOf('/** Query user list */'));
const dialog = script.slice(script.indexOf('function reset()'), script.indexOf('/** submit button */'));
const hooks = script.match(/onMounted\(getDeptTree\);\s*onActivated\(getDeptTree\);/)[0];

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function harness(treeResponse = async () => ({ data: [] }), userResponse = async () => ({ posts: [], roles: [] })) {
  const requests = [], filters = [], selected = [], mounted = [], activated = [];
  const form = ref({}), open = ref(false), title = ref('');
  const postOptions = ref([]), roleOptions = ref([]), ids = ref(['9007199254740999']);
  const deptName = ref('数据'), queryParams = ref({ deptId: '100' });
  const dependencies = {
    ref, nextTick, form, open, title, postOptions, roleOptions, ids, deptName, queryParams,
    initPassword: ref(undefined), td: key => key,
    deptTreeSelect: () => { requests.push({ url: '/system/user/deptTree' }); return treeResponse(); },
    getUser: id => { requests.push({ url: '/system/user', id }); return userResponse(id); },
    proxy: { resetForm() {}, $refs: { deptTreeRef: {
      filter: value => filters.push(value), setCurrentKey: value => selected.push(value),
    } } },
    onMounted: handler => mounted.push(handler), onActivated: handler => activated.push(handler),
  };
  const api = new Function(...Object.keys(dependencies), `${state}\n${refresh}\n${dialog}\n${hooks}
    return { getDeptTree, handleAdd, handleUpdate, cancel, deptOptions, deptTreeLoading, deptTreeFailed, dialogLoading };`)(...Object.values(dependencies));
  return { ...api, form, open, title, requests, filters, selected, mounted, activated, postOptions, roleOptions, ids };
}

test('首次挂载和激活共用请求，缓存页再次激活重新取树并保留筛选', async () => {
  const first = deferred();
  let tree = [{ id: '100', label: '大数据部' }], calls = 0;
  const h = harness(() => ++calls === 1 ? first.promise : Promise.resolve({ data: tree }));
  const mounting = h.mounted[0](), activating = h.activated[0]();
  assert.equal(mounting, activating);
  assert.equal(h.deptTreeLoading.value, true);
  first.resolve({ data: tree });
  await mounting;
  tree = [{ id: '100', label: '大数据部', children: [{ id: '104', label: '新增部门' }] }];
  await h.activated[0]();
  assert.equal(calls, 2);
  assert.deepEqual(h.deptOptions.value, tree);
  assert.deepEqual(h.filters, ['数据', '数据']);
  assert.deepEqual(h.selected, ['100', '100']);
  assert.equal(h.deptTreeLoading.value, false);
});

test('新增弹窗必须等待最新树，连续两次打开会重新读取部门', async () => {
  const waiting = deferred();
  let result = waiting.promise;
  const h = harness(() => result);
  const opening = h.handleAdd();
  await nextTick();
  assert.equal(h.open.value, false);
  assert.equal(h.dialogLoading.value, true);
  waiting.resolve({ data: [{ id: '104', label: '新增部门' }] });
  await opening;
  assert.equal(h.open.value, true);
  assert.equal(h.deptOptions.value[0].id, '104');
  h.cancel();
  result = Promise.resolve({ data: [{ id: '105', label: '再新增部门' }] });
  await h.handleAdd();
  assert.equal(h.deptOptions.value[0].id, '105');
  assert.equal(h.requests.filter(r => r.url.endsWith('deptTree')).length, 2);
  assert.equal(h.dialogLoading.value, false);
});

test('编辑弹窗等待树和用户详情，保留部门、岗位、角色及精度安全 ID', async () => {
  const waiting = deferred();
  const user = { userId: '9007199254740999', deptId: '9007199254740997', nickName: '回归用户' };
  const h = harness(async () => ({ data: [{ id: user.deptId, label: '授权部门' }] }), () => waiting.promise);
  const opening = h.handleUpdate({ userId: user.userId });
  await h.getDeptTree();
  assert.equal(h.open.value, false);
  waiting.resolve({ data: user, posts: [{ postId: '2' }], roles: [{ roleId: '3' }], postIds: ['2'], roleIds: ['3'] });
  await opening;
  assert.equal(h.open.value, true);
  assert.equal(h.form.value.userId, user.userId);
  assert.equal(h.form.value.deptId, user.deptId);
  assert.deepEqual(h.form.value.postIds, ['2']);
  assert.deepEqual(h.form.value.roleIds, ['3']);
  assert.equal(h.requests.find(r => r.url === '/system/user').id, user.userId);
});

for (const mode of ['add', 'edit']) {
  test(`${mode}：树加载失败清除过期数据且不开弹窗，重试可以恢复`, async () => {
    let failed = true;
    const h = harness(async () => {
      if (failed) throw new Error('network unavailable / forbidden');
      return { data: [{ id: '104', label: '新增部门' }] };
    }, async () => ({ data: { userId: '2', deptId: '104' }, posts: [], roles: [], postIds: [], roleIds: [] }));
    h.deptOptions.value = [{ id: '100', label: '旧部门' }];
    const openDialog = () => mode === 'add' ? h.handleAdd() : h.handleUpdate({ userId: '2' });
    await openDialog();
    assert.deepEqual(h.deptOptions.value, []);
    assert.equal(h.deptTreeFailed.value, true);
    assert.equal(h.open.value, false);
    assert.equal(h.deptTreeLoading.value, false);
    assert.equal(h.dialogLoading.value, false);
    failed = false;
    await openDialog();
    assert.equal(h.open.value, true);
    assert.equal(h.deptTreeFailed.value, false);
    assert.equal(h.deptOptions.value[0].id, '104');
  });
}

test('激活刷新和重复新增/编辑点击只加载一次并且不会互相覆盖', async () => {
  const waiting = deferred();
  const h = harness(() => waiting.promise);
  const activation = h.activated[0]();
  const opening = h.handleAdd();
  await h.handleAdd();
  await h.handleUpdate({ userId: 'other' });
  assert.equal(h.requests.length, 2);
  waiting.resolve({ data: [] });
  await Promise.all([activation, opening]);
  assert.equal(h.title.value, 'sys.system.user.addUser');
  assert.equal(h.dialogLoading.value, false);
});

test('用户详情失败不会打开弹窗，按钮恢复且下次请求能成功', async () => {
  let failed = true;
  const h = harness(async () => ({ data: [] }), async () => {
    if (failed) throw new Error('user detail failed');
    return { posts: [], roles: [] };
  });
  await h.handleAdd();
  assert.equal(h.open.value, false);
  assert.equal(h.dialogLoading.value, false);
  failed = false;
  await h.handleAdd();
  assert.equal(h.open.value, true);
});

test('仅使用接口返回的授权部门，空树正常展示且不回退到旧选项', async () => {
  const h = harness(async () => ({ data: [{ id: '101', label: '授权部门' }] }));
  h.deptOptions.value = [{ id: '104', label: '旧权限部门' }];
  await h.handleAdd();
  assert.deepEqual(h.deptOptions.value, [{ id: '101', label: '授权部门' }]);
  const empty = harness(async () => ({ data: [] }));
  await empty.handleAdd();
  assert.deepEqual(empty.deptOptions.value, []);
  assert.equal(empty.deptTreeFailed.value, false);
  assert.equal(empty.open.value, true);
});

test('页面展示加载、失败与重试，加载失败期间禁用部门选择和提交', () => {
  const template = page.template.content;
  assert.match(template, /v-loading="deptTreeLoading"/);
  assert.match(template, /v-if="deptTreeFailed"/);
  assert.match(template, /@click="getDeptTree"/);
  assert.match(template, /<el-tree-select[^>]*:disabled="deptTreeLoading \|\| deptTreeFailed"/);
  assert.match(template, /:disabled="deptTreeLoading \|\| deptTreeFailed" @click="submitForm"/);
});
