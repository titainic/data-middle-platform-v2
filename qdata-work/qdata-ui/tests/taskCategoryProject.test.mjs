import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref } from 'vue';
import { parse } from '@vue/compiler-sfc';

// Exercise the real page handlers and API adapter; HTTP and Element Plus are
// replaced so this regression never connects to a server or writes live data.
const page = parse(readFileSync(new URL('../src/views/dpp/setting/taskCat/index.vue', import.meta.url), 'utf8'))
  .descriptor.scriptSetup.content;
const handlers = page.slice(page.indexOf('function submitForm()'), page.indexOf('/** Change enabled status value */'));
const adapter = readFileSync(new URL('../src/api/att/cat/taskCat/taskCat.js', import.meta.url), 'utf8')
  .replace(/^import request[^\n]*$/m, '').replace(/export function /g, 'function ');

function harness(response = async () => ({ data: false })) {
  const requests = [], errors = [], warnings = [];
  const userStore = { projectId: '4', projectCode: '9007199254740993' };
  const form = ref({ id: null, parentId: 0, name: 'bin97_integration_category', validFlag: true });
  const submitLoading = ref(false), open = ref(true);
  let validation, handlersApi;
  const request = async options => {
    requests.push(JSON.parse(JSON.stringify(options)));
    return options.method === 'get' ? response(options.params) : { data: 'new-category' };
  };
  const api = new Function('request', `${adapter}; return { isTaskCatNameUsed, addAttTaskCat };`)(request);
  const dependencies = {
    ...api, form, userStore, submitLoading, open,
    td: (key, fallback) => fallback || key,
    warningRequestOptions: { hideErrorMessage: true },
    ElMessage: { warning: ({ message }) => {
      warnings.push(message);
      return { close() {} };
    } },
    proxy: {
      $refs: { AttTaskCatRef: { validate: callback => {
        validation = handlersApi.validateTaskCatName(null, form.value.name, error => {
          if (error) errors.push(error.message);
          callback(!error);
        });
      } } },
      $modal: { msgSuccess() {} },
    },
    getList() {}, showRequestWarning: error => errors.push(error.message),
  };
  handlersApi = new Function(...Object.keys(dependencies), `
    let taskCatNameMessageInstance = null;
    let taskCatNameMessageText = '';
    ${handlers}; return { submitForm, validateTaskCatName };
  `)(...Object.values(dependencies));
  return {
    ...handlersApi, form, userStore, requests, errors, warnings, submitLoading, open,
    async submit() {
      handlersApi.submitForm();
      await validation;
      await Promise.resolve();
    },
    async validate(value = form.value.name) {
      let result;
      await handlersApi.validateTaskCatName(null, value, error => { result = error; });
      return result;
    },
  };
}

test('root validation carries the current project before creating an available name', async () => {
  const h = harness(async query => {
    if (!query.projectId || !query.projectCode) throw new Error('ambiguous project');
    return { data: false };
  });
  await h.submit();
  assert.deepEqual(h.errors, []);
  assert.deepEqual(h.warnings, []);
  assert.equal(h.requests.length, 2);
  const [check, create] = h.requests;
  assert.deepEqual(check, {
    url: '/att/taskCat/nameUsed', method: 'get',
    params: { parentId: 0, name: 'bin97_integration_category', projectId: '4', projectCode: '9007199254740993' },
    headers: { hideErrorMessage: true },
  });
  assert.equal(create.url, '/att/taskCat');
  assert.equal(create.method, 'post');
  assert.equal(create.data.projectId, check.params.projectId);
  assert.equal(create.data.projectCode, check.params.projectCode);
  assert.equal(h.submitLoading.value, false);
  assert.equal(h.open.value, false);
});

test('child validation preserves the parent, trims the name and retains precision-safe IDs', async () => {
  const h = harness();
  h.form.value.parentId = '9007199254740995';
  h.userStore.projectId = '9007199254740997';
  assert.equal(await h.validate('  child  '), undefined);
  assert.deepEqual(h.requests[0].params, {
    parentId: '9007199254740995', name: 'child',
    projectId: '9007199254740997', projectCode: '9007199254740993',
  });
});

test('validation and create use a newly selected project instead of stale form context', async () => {
  const h = harness();
  h.form.value.projectId = 'stale-id';
  h.form.value.projectCode = 'stale-code';
  await h.validate();
  h.userStore.projectId = '5';
  h.userStore.projectCode = 'new-project';
  await h.submit();
  assert.equal(h.requests[0].params.projectId, '4');
  assert.equal(h.requests[1].params.projectId, '5');
  assert.equal(h.requests[1].params.projectCode, 'new-project');
  assert.equal(h.requests[2].data.projectId, '5');
  assert.equal(h.requests[2].data.projectCode, 'new-project');
});

for (const parentId of [0, '9007199254740995']) {
  test(`a same-parent duplicate under ${parentId} warns and blocks create`, async () => {
    const h = harness(async () => ({ data: true }));
    h.form.value.parentId = parentId;
    await h.submit();
    assert.deepEqual(h.errors, ['名称已被使用']);
    assert.deepEqual(h.warnings, ['名称已被使用']);
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].params.parentId, parentId);
    assert.equal(h.open.value, true);
    assert.equal(h.submitLoading.value, false);
  });
}

for (const message of ['HTTP 200 / business 403 rejected by interceptor', 'network unavailable']) {
  test(`${message} fails validation without sending a create request`, async () => {
    const h = harness(async () => { throw new Error(message); });
    await h.submit();
    assert.deepEqual(h.errors, ['名称校验失败']);
    assert.deepEqual(h.warnings, ['名称校验失败']);
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].method, 'get');
    assert.equal(h.open.value, true);
    assert.equal(h.submitLoading.value, false);
  });
}

test('a rejection with no valid selected project never falls back to create', async () => {
  const h = harness(async () => { throw new Error('no valid project (403)'); });
  h.userStore.projectId = undefined;
  h.userStore.projectCode = undefined;
  await h.submit();
  assert.deepEqual(h.errors, ['名称校验失败']);
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].method, 'get');
  assert.equal(h.open.value, true);
  assert.equal(h.submitLoading.value, false);
});

test('empty and whitespace-only names keep required validation and send no requests', async () => {
  for (const name of ['', '   ']) {
    const h = harness();
    h.form.value.name = name;
    await h.submit();
    assert.deepEqual(h.errors, ['dpp.setting.taskCat.nameRequired']);
    assert.deepEqual(h.warnings, ['dpp.setting.taskCat.nameRequired']);
    assert.equal(h.requests.length, 0);
    assert.equal(h.open.value, true);
    assert.equal(h.submitLoading.value, false);
  }
});

test('editing an existing category retains the existing name-validation flow', async () => {
  const h = harness();
  h.form.value.id = '10';
  assert.equal(await h.validate(), undefined);
  assert.equal(h.requests.length, 0);
});
