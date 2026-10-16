import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref } from 'vue';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';

// Execute the real page handler and API URL builder. Only the modal and HTTP
// transport are substitutes; these tests never connect to a business environment.
const page = parse(readFileSync(process.env.BIN94_SOURCE_PAGE || new URL('../src/views/att/sourceSystem/index.vue', import.meta.url), 'utf8')).descriptor;
const script = page.scriptSetup.content;
const handler = script.slice(script.indexOf('function handleDelete(row)'), script.indexOf('// initialization'));
const api = readFileSync(new URL('../src/api/att/sourceSystem/sourceSystem.js', import.meta.url), 'utf8');
const deleteApi = api.slice(api.indexOf('export function delSourceSystem')).replace('export ', '');

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

const flush = () => new Promise(resolve => setImmediate(resolve));

function harness(rows = [], confirm = async () => {}, response = async () => ({ code: 200 })) {
  const confirmations = [], requests = [], warnings = [], successes = [];
  const store = { rows }, deleteLoading = ref(false);
  let refreshes = 0;
  const delSourceSystem = new Function('request', `${deleteApi}; return delSourceSystem;`)(options => {
    requests.push(options);
    return response(options);
  });
  const dependencies = {
    store, deleteLoading, delSourceSystem,
    tableRef: ref({ getList() { refreshes++; } }),
    td: (key, fallback, params) => params || fallback || key,
    proxy: { $modal: {
      confirm(message) { confirmations.push(message); return confirm(); },
      msgWarning(message) { warnings.push(message); },
      msgSuccess(message) { successes.push(message); },
    } },
  };
  const handleDelete = new Function(...Object.keys(dependencies), `${handler}; return handleDelete;`)(...Object.values(dependencies));
  return {
    handleDelete, store, deleteLoading, confirmations, requests, warnings, successes,
    get refreshes() { return refreshes; },
  };
}

function blocked(h) {
  assert.equal(h.confirmations.length, 0);
  assert.equal(h.requests.length, 0);
  assert.equal(h.warnings.length, 1);
  assert.match(h.warnings[0], /没有可删除.*已禁用/);
  assert.equal(h.refreshes, 0);
  assert.equal(h.successes.length, 0);
  assert.equal(h.deleteLoading.value, false);
}

test('行取消：只确认当前行的1/0数量，不使用工具栏选择，不发DELETE', async () => {
  const h = harness([{ id: 9, validFlag: false }, { id: 10, validFlag: true }], () => Promise.reject('cancel'));
  await h.handleDelete({ id: 1, validFlag: false });
  await flush();
  assert.deepEqual(h.confirmations, [{ deletable: 1, undeletable: 0 }]);
  assert.equal(h.requests.length, 0);
  assert.equal(h.successes.length, 0);
  assert.equal(h.refreshes, 0);
  assert.equal(h.deleteLoading.value, false);
});

test('禁用行确认：一次确认且仅请求DELETE /att/sourceSystem/1', async () => {
  const h = harness([{ id: 9, validFlag: false }]);
  await h.handleDelete({ id: 1, validFlag: false });
  await flush();
  assert.deepEqual(h.confirmations, [{ deletable: 1, undeletable: 0 }]);
  assert.deepEqual(h.requests, [{ url: '/att/sourceSystem/1', method: 'delete' }]);
  assert.equal(h.refreshes, 1);
  assert.equal(h.successes.length, 1);
  assert.equal(h.deleteLoading.value, false);
});

test('启用行即使直接调用也不能删除，且不回退删除工具栏已选禁用行', async () => {
  const h = harness([{ id: 9, validFlag: false }]);
  await h.handleDelete({ id: 1, validFlag: true });
  await flush();
  blocked(h);
});

for (const [name, rows] of [
  ['空选择', []],
  ['全部启用', [{ id: 1, validFlag: true }, { id: 2, validFlag: true }]],
  ['过滤后没有有效ID', [{ validFlag: false }, { id: null, validFlag: false }, { id: '', validFlag: false }]],
]) {
  test(`批量${name}：提示并停止确认和请求`, async () => {
    const h = harness(rows);
    // Toolbar click supplies a MouseEvent-shaped object without a row ID.
    await h.handleDelete({ type: 'click' });
    await flush();
    blocked(h);
  });
}

for (const ids of [[1], [1, 2]]) {
  test(`批量${ids.length}条禁用：确认数量与实际URL一致`, async () => {
    const h = harness(ids.map(id => ({ id, validFlag: false })));
    await h.handleDelete();
    await flush();
    assert.deepEqual(h.confirmations, [{ deletable: ids.length, undeletable: 0 }]);
    assert.deepEqual(h.requests, [{ url: `/att/sourceSystem/${ids.join(',')}`, method: 'delete' }]);
    assert.equal(h.refreshes, 1);
  });
}

test('混合选择：严格筛选禁用且有ID的行，启用/未知状态均不提交', async () => {
  const h = harness([
    { id: 1, validFlag: false }, { id: 2, validFlag: true },
    { id: 3, validFlag: false }, { id: 4 }, { id: null, validFlag: false },
  ]);
  await h.handleDelete();
  await flush();
  assert.deepEqual(h.confirmations, [{ deletable: 2, undeletable: 3 }]);
  assert.deepEqual(h.requests, [{ url: '/att/sourceSystem/1,3', method: 'delete' }]);
});

test('字符串Long ID原样传递，不产生JS精度丢失', async () => {
  const h = harness();
  await h.handleDelete({ id: '9007199254740999', validFlag: false });
  await flush();
  assert.deepEqual(h.requests, [{ url: '/att/sourceSystem/9007199254740999', method: 'delete' }]);
});

test('确认期间选择变化：提交保留已确认的ID快照与数量', async () => {
  const confirmation = deferred();
  const h = harness([{ id: 1, validFlag: false }, { id: 2, validFlag: true }], () => confirmation.promise);
  const deleting = h.handleDelete();
  h.store.rows[0].id = 99;
  h.store.rows = [{ id: 3, validFlag: false }];
  assert.equal(h.requests.length, 0);
  confirmation.resolve();
  await deleting;
  await flush();
  assert.deepEqual(h.confirmations, [{ deletable: 1, undeletable: 1 }]);
  assert.deepEqual(h.requests, [{ url: '/att/sourceSystem/1', method: 'delete' }]);
});

test('批量取消后不请求，锁释放后可以再次正常确认', async () => {
  let cancel = true;
  const h = harness([{ id: 1, validFlag: false }], () => cancel ? Promise.reject('cancel') : Promise.resolve());
  await h.handleDelete();
  await flush();
  assert.equal(h.requests.length, 0);
  assert.equal(h.deleteLoading.value, false);
  cancel = false;
  await h.handleDelete();
  await flush();
  assert.deepEqual(h.requests, [{ url: '/att/sourceSystem/1', method: 'delete' }]);
});

test('确认及HTTP等待期间重复点击不会增加弹窗或请求', async () => {
  const confirmation = deferred(), response = deferred();
  const h = harness([{ id: 1, validFlag: false }], () => confirmation.promise, () => response.promise);
  const deleting = h.handleDelete();
  assert.equal(h.deleteLoading.value, true);
  await h.handleDelete({ id: 2, validFlag: false });
  assert.equal(h.confirmations.length, 1);
  confirmation.resolve();
  await flush();
  await h.handleDelete();
  assert.equal(h.requests.length, 1);
  response.resolve({ code: 200 });
  await deleting;
  await flush();
  assert.equal(h.deleteLoading.value, false);
  assert.equal(h.refreshes, 1);
});

test('服务端拒绝/网络失败不报成功或刷新，释放锁后允许重试', async () => {
  let failure = true;
  const h = harness([{ id: 1, validFlag: false }], async () => {}, async () => {
    if (failure) throw new Error('server reference protection / network failure');
    return { code: 200 };
  });
  await h.handleDelete();
  await flush();
  assert.equal(h.successes.length, 0);
  assert.equal(h.refreshes, 0);
  assert.equal(h.deleteLoading.value, false);
  failure = false;
  await h.handleDelete();
  await flush();
  assert.equal(h.requests.length, 2);
  assert.equal(h.successes.length, 1);
});

test('SFC编译及事件接线：两个删除入口共用一次确认，移除未使用的二次确认组件', () => {
  assert.equal((page.template.content.match(/@click="handleDelete(?:\(row\))?"/g) || []).length, 2);
  assert.doesNotMatch(script + page.template.content, /DeleteConfirmDialog|confirm-delete/);
  assert.match(page.template.content, /:disabled="deleteLoading \|\| row.validFlag"/);
  assert.match(page.template.content, /:disabled="deleteLoading \|\| !store.rows.length"/);
  assert.match(script, /const deleteLoading = ref\(false\)/);
  const compiled = compileScript(page, { id: 'source-system-delete' });
  const template = compileTemplate({
    source: page.template.content, filename: 'index.vue', id: 'source-system-delete',
    compilerOptions: { bindingMetadata: compiled.bindings },
  });
  assert.deepEqual(template.errors, []);
});
