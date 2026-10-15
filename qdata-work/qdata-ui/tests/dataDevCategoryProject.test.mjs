import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ref } from 'vue';
import { parse } from '@vue/compiler-sfc';

// Run the real Vue validation/submission handlers through the real API adapter.
// Only HTTP and Element Plus form/modal dependencies are replaced; no live writes.
const page = parse(readFileSync(new URL('../src/views/dpp/setting/dataDevCat/index.vue', import.meta.url), 'utf8'))
  .descriptor.scriptSetup.content;
const handlers = page.slice(page.indexOf('function submitForm()'), page.indexOf('/** Delete button action */'));
const adapter = readFileSync(new URL('../src/api/att/cat/dataDevCat/dataDevCat.js', import.meta.url), 'utf8')
  .replace(/^import request[^\n]*$/m, '').replace(/export function /g, 'function ');

function harness(response = async () => ({ data: false })) {
  const requests = [], errors = [];
  const userStore = { projectId: '1', projectCode: '9007199254740993' };
  const form = ref({ id: null, parentId: 0, name: 'Sentinel_BIN44_五库验收', validFlag: true });
  const submitLoading = ref(false), open = ref(true);
  let validation;
  const request = async options => {
    requests.push(JSON.parse(JSON.stringify(options)));
    return options.method === 'get' ? response(options.params) : { data: 'new-category' };
  };
  const api = new Function('request', `${adapter}; return { isDataDevCatNameUsed, addAttDataDevCat };`)(request);
  let handlersApi;
  const dependencies = {
    ...api, form, userStore, submitLoading, open,
    td: (_key, fallback) => fallback,
    warningRequestOptions: { hideErrorMessage: true },
    proxy: {
      $refs: { AttDataDevCatRef: { validate: callback => {
        validation = handlersApi.validateDataDevCatName(null, form.value.name, error => {
          if (error) errors.push(error.message);
          callback(!error);
        });
      } } },
      $modal: { msgSuccess() {} },
    },
    getList() {}, showRequestWarning: error => errors.push(error.message),
  };
  handlersApi = new Function(...Object.keys(dependencies), `${handlers}; return { submitForm, validateDataDevCatName };`)
    (...Object.values(dependencies));
  return {
    ...handlersApi, form, userStore, requests, errors, submitLoading, open,
    async submit() {
      handlersApi.submitForm();
      await validation;
      await Promise.resolve();
    },
    async validate(value = form.value.name) {
      let result;
      await handlersApi.validateDataDevCatName(null, value, error => { result = error; });
      return result;
    },
  };
}

test('root name validation carries the current project before a successful create', async () => {
  const h = harness(async query => {
    // Model a multi-project author: an unscoped root request cannot resolve its owner.
    if (!query.projectId || !query.projectCode) throw new Error('ambiguous project');
    return { data: false };
  });
  await h.submit();
  assert.deepEqual(h.errors, []);
  assert.equal(h.requests.length, 2);
  const [check, create] = h.requests;
  assert.deepEqual(check, {
    url: '/att/dataDevCat/nameUsed', method: 'get',
    params: { parentId: 0, name: 'Sentinel_BIN44_五库验收', projectId: '1', projectCode: '9007199254740993' },
    headers: { hideErrorMessage: true },
  });
  assert.equal(create.url, '/att/dataDevCat');
  assert.equal(create.method, 'post');
  assert.equal(create.data.projectId, check.params.projectId);
  assert.equal(create.data.projectCode, check.params.projectCode);
  assert.equal(h.submitLoading.value, false);
  assert.equal(h.open.value, false);
});

test('child names retain parent identity, trimmed name and precision-safe project values', async () => {
  const h = harness();
  h.form.value.parentId = '9007199254740995';
  h.userStore.projectId = '9007199254740997';
  assert.equal(await h.validate('  child  '), undefined);
  assert.deepEqual(h.requests[0].params, {
    parentId: '9007199254740995', name: 'child',
    projectId: '9007199254740997', projectCode: '9007199254740993',
  });
});

test('validation reads a newly selected project instead of a previous list query', async () => {
  const h = harness();
  await h.validate();
  h.userStore.projectId = '2'; h.userStore.projectCode = 'new-project';
  await h.validate();
  assert.equal(h.requests[0].params.projectId, '1');
  assert.equal(h.requests[1].params.projectId, '2');
  assert.equal(h.requests[1].params.projectCode, 'new-project');
});

test('a duplicate in the selected project blocks create and leaves the dialog open', async () => {
  const h = harness(async () => ({ data: true }));
  await h.submit();
  assert.deepEqual(h.errors, ['名称已被使用']);
  assert.equal(h.requests.length, 1);
  assert.equal(h.open.value, true);
  assert.equal(h.submitLoading.value, false);
});

for (const message of ['project permission denied (403)', 'network unavailable']) {
  test(`${message} fails validation without sending a create request`, async () => {
    const h = harness(async () => { throw new Error(message); });
    await h.submit();
    assert.deepEqual(h.errors, ['名称校验失败']);
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].method, 'get');
    assert.equal(h.open.value, true);
    assert.equal(h.submitLoading.value, false);
  });
}

test('empty names and existing-category edits retain their existing validation flow', async () => {
  const h = harness();
  assert.equal(await h.validate(''), undefined);
  h.form.value.id = '10';
  assert.equal(await h.validate(), undefined);
  assert.equal(h.requests.length, 0);
});
