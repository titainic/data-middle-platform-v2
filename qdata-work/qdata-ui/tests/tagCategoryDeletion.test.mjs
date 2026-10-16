import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';

const page = fs.readFileSync(new URL('../src/views/att/cat/components/CatPage.vue', import.meta.url), 'utf8');
const tagPage = fs.readFileSync(new URL('../src/views/att/cat/tagCat/index.vue', import.meta.url), 'utf8');
const api = fs.readFileSync(new URL('../src/api/att/cat/tagCat/tagCat.js', import.meta.url), 'utf8');

function apiHarness() {
  const requests = [];
  const context = vm.createContext({ request: async config => { requests.push(config); return { code: 200 }; } });
  vm.runInContext(api.replace(/^import .*$/mg, '').replace(/export /g, '') + '\nglobalThis.remove = delAttTagCat;', context);
  return { remove: context.remove, requests };
}

function pageHarness({ enabledGuard = true, rejectDelete = false, cancel = false } = {}) {
  const calls = [], messages = [];
  let reloads = 0;
  const selection = { rows: [] };
  const props = { blockEnabledDelete: enabledGuard, delFunc: async ids => {
    calls.push(ids); if (rejectDelete) throw new Error('reference blocks deletion'); return { code: 200 };
  } };
  const context = vm.createContext({ props, selection,
    computed: fn => ({ get value() { return fn(); } }), ref: value => ({ value }),
    effectiveTitleBase: { value: '标签类目' }, td: key => key,
    ElMessageBox: { confirm: () => cancel ? Promise.reject('cancel') : Promise.resolve() },
    ElMessage: { success: message => messages.push(message) },
    tableRef: { value: { getList: () => reloads++ } },
    handleQueryClick: () => reloads++,
    proxy: { $modal: { confirm: () => cancel ? Promise.reject('cancel') : Promise.resolve(),
      msgSuccess: message => messages.push(message) } },
  });
  const computed = page.slice(page.indexOf('const deleteSelectionDisabled'), page.indexOf('const treeOptions'));
  const handlers = page.slice(page.indexOf('function handleDelete(row)'), page.indexOf('const data = reactive'));
  vm.runInContext(computed + handlers + '\nglobalThis.actions = { handleDelete, handleDeleteSelected, deleteSelectionDisabled };', context);
  const settle = () => new Promise(resolve => setImmediate(resolve));
  return { selection, props, calls, messages, settle, actions: context.actions, get reloads() { return reloads; } };
}

test('single, two, many and duplicate ID requests use one comma contract and preserve Long strings', async () => {
  const h = apiHarness();
  for (const ids of [1, ['1'], [1, 2], ['1', '2', '9007199254740993'], [1, '1', 2]]) await h.remove(ids);
  assert.deepEqual(h.requests.map(r => r.url), [
    '/att/tagCat/1', '/att/tagCat/1', '/att/tagCat/1,2', '/att/tagCat/1,2,9007199254740993', '/att/tagCat/1,2',
  ]);
  assert.ok(h.requests.every(r => r.method === 'delete'));
});

test('empty, malformed, nonpositive, overflow and imprecise numeric IDs never send a request', async () => {
  const h = apiHarness();
  for (const ids of [[], null, undefined, '', 'x', 0, -1, '1,2', ['1', null], '9223372036854775808', 9007199254740993]) {
    await assert.rejects(h.remove(ids), /有效/);
  }
  assert.equal(h.requests.length, 0);
});

test('zero selection disables toolbar and guards handler', async () => {
  const h = pageHarness(); assert.equal(h.actions.deleteSelectionDisabled.value, true);
  h.actions.handleDeleteSelected(); await h.settle(); assert.equal(h.calls.length, 0);
});

for (const count of [1, 2, 4]) test(`${count} disabled rows submit the full selection and refresh only after success`, async () => {
  const h = pageHarness(); h.selection.rows = Array.from({ length: count }, (_, i) => ({ id: String(i + 1), validFlag: false }));
  assert.equal(h.actions.deleteSelectionDisabled.value, false);
  h.actions.handleDeleteSelected(); await h.settle();
  assert.deepEqual(Array.from(h.calls[0]), Array.from({ length: count }, (_, i) => String(i + 1)));
  assert.equal(h.selection.rows.length, 0); assert.equal(h.reloads, 1); assert.equal(h.messages.length, 1);
});

test('enabled single or mixed selections and direct row invocation cannot bypass the guard', async () => {
  const h = pageHarness();
  for (const rows of [[{ id: '1', validFlag: true }], [{ id: '1', validFlag: false }, { id: '2', validFlag: true }]]) {
    h.selection.rows = rows; assert.equal(h.actions.deleteSelectionDisabled.value, true);
    h.actions.handleDeleteSelected(); h.actions.handleDelete(rows.at(-1)); await h.settle();
  }
  assert.equal(h.calls.length, 0);
});

test('inline disabled leaf deletion uses the same API contract', async () => {
  const h = pageHarness(); h.actions.handleDelete({ id: '9007199254740993', validFlag: false }); await h.settle();
  assert.equal(h.calls[0], '9007199254740993'); assert.equal(h.reloads, 1);
});

test('reference/business failure retains selection and shows no success or list refresh', async () => {
  const h = pageHarness({ rejectDelete: true }); h.selection.rows = [{ id: '1', validFlag: false }, { id: '2', validFlag: false }];
  h.actions.handleDeleteSelected(); await h.settle();
  assert.equal(h.calls.length, 1); assert.equal(h.selection.rows.length, 2);
  assert.equal(h.messages.length, 0); assert.equal(h.reloads, 0);
});

test('cancelled confirmation never sends deletion', async () => {
  const h = pageHarness({ cancel: true }); h.selection.rows = [{ id: '1', validFlag: false }];
  h.actions.handleDeleteSelected(); await h.settle(); assert.equal(h.calls.length, 0);
});

test('other category pages retain their prior toolbar policy', async () => {
  const h = pageHarness({ enabledGuard: false }); h.selection.rows = [{ id: 1, validFlag: true }];
  assert.equal(h.actions.deleteSelectionDisabled.value, false);
});

test('actual SFC bindings compile and guard is explicitly opted in only by tag category', () => {
  assert.match(page, /:disabled="deleteSelectionDisabled"/);
  assert.match(page, /:disabled="row.validFlag"/);
  assert.match(tagPage, /\sblock-enabled-delete\s/);
  for (const [filename, source] of [['CatPage.vue', page], ['tagCat.vue', tagPage]]) {
    const parsed = parse(source, { filename }); assert.deepEqual(parsed.errors, []);
    const script = compileScript(parsed.descriptor, { id: filename });
    const template = compileTemplate({ source: parsed.descriptor.template.content, filename, id: filename,
      compilerOptions: { bindingMetadata: script.bindings } });
    assert.deepEqual(template.errors, []);
  }
});
